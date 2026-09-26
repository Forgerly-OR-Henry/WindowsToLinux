package gold.debug.windowstolinux.app.db.persistence.repository;

import java.security.*;
import java.sql.*;
import java.util.*;

import gold.debug.windowstolinux.app.db.persistence.connection.RepositoryConnections;
import gold.debug.windowstolinux.app.db.schema.SchemaCoordinates;
import gold.debug.windowstolinux.appapi.db.SchemaIdentity;
import gold.debug.windowstolinux.appapi.secret.*;
import gold.debug.windowstolinux.appapi.server.*;

/** Persists Mod-owned credential associations without exposing protected values. / 持久化 Mod 所属凭据关联，不暴露受保护值。 */
public final class CredentialReferenceRepository {
    /** Shared platform transaction ownership. / 共享平台事务归属。 */
    private final RepositoryConnections connections;

    /** Binds the platform transaction source. / 绑定平台事务来源。
     * @param connections shared connection scope / 共享连接作用域 */
    public CredentialReferenceRepository(RepositoryConnections connections) {
        this.connections = Objects.requireNonNull(connections);
    }

    /** Creates an opaque identity whose namespace binds owner and exact purpose. / 创建命名空间绑定所有者和精确用途的不透明身份。
     * @param owner exact Mod ID / 精确 Mod 标识
     * @param purpose exact declared purpose / 精确声明用途
     * @return new immutable credential identity / 新的不可变凭据身份 */
    public static SecretRef newSecret(String owner, String purpose) {
        return new SecretRef(prefix(owner, purpose) + UUID.randomUUID(), purpose);
    }

    /** Rejects foreign, altered-purpose and non-platform-issued identities. / 拒绝他方、用途改变和非平台签发格式的身份。
     * @param owner capability owner / 能力所有者
     * @param secret supplied opaque identity / 提供的不透明身份
     * @throws SQLException if the identity does not belong to this scope / 身份不属于当前作用域时 */
    public static void requireOwned(String owner, SecretRef secret) throws SQLException {
        String prefix = prefix(owner, secret.purpose());
        try {
            if (!secret.id().startsWith(prefix))
                throw new IllegalArgumentException();
            String suffix = secret.id().substring(prefix.length());
            if (!UUID.fromString(suffix).toString().equals(suffix))
                throw new IllegalArgumentException();
        } catch (IllegalArgumentException invalid) {
            throw new SQLException("Credential identity belongs to another owner or purpose", "42501");
        }
    }

    /** Encodes bounded exact metadata independently of Mod release or Schema version. / 编码有界精确元数据，不绑定 Mod 发布或 Schema 版本。
     * @param owner exact Mod ID / 精确 Mod 标识
     * @param purpose exact purpose / 精确用途
     * @return bounded credential namespace / 有界凭据命名空间 */
    private static String prefix(String owner, String purpose) {
        return "mod-secret-v1:" + hash(owner) + ":" + hash(purpose) + ":";
    }

    /** Hashes exact UTF-16 code units without lossy Unicode replacement. / 对精确 UTF-16 码元求摘要，不作有损 Unicode 替换。
     * @param value non-secret identity metadata / 非秘密身份元数据
     * @return full SHA-256 hex digest / 完整 SHA-256 十六进制摘要 */
    private static String hash(String value) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            for (int i = 0; i < value.length(); i++) {
                digest.update((byte) (value.charAt(i) >>> 8));
                digest.update((byte) value.charAt(i));
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("JDK SHA-256 unavailable", unavailable);
        }
    }

    /**
     * Reserves a credential association without downgrading existing state. / 预留凭据关联，不降低已有状态。
     * @param schema admitted data generation / 已准入数据代次
     * @param secret owned credential / 所属凭据
     * @param businessKey stable association key / 稳定关联键
     * @return original or new reservation / 原预留或新预留
     * @throws SQLException if ownership or storage is unavailable / 归属或存储不可用时
     */
    public SecretReference reserve(SchemaIdentity schema, SecretRef secret, String businessKey) throws SQLException {
        requireOwned(schema.ownerId(), secret);
        var result = new java.util.concurrent.atomic.AtomicReference<SecretReference>();
        connections.transaction(() -> {
            try (var connection = connections.open()) {
                RepositoryConnections.lockPlatformState(connection);
                new SchemaCoordinates(schema).requireRegistered(connection, Set.of("READY"));
                lockCredential(connection, secret.id());
                try (var statement = connection.prepareStatement(
                        "SELECT reference_id,purpose,state FROM mod_credential_reference WHERE mod_id=? AND schema_version=? AND schema_generation=? AND credential_id=? AND business_key=?")) {
                    statement.setString(1, schema.ownerId());
                    statement.setString(2, schema.schemaVersion());
                    statement.setObject(3, schema.generation());
                    statement.setString(4, secret.id());
                    statement.setString(5, businessKey);
                    try (var rows = statement.executeQuery()) {
                        if (rows.next()) {
                            if (!secret.purpose().equals(rows.getString(2)))
                                throw new SQLException("Credential purpose is inconsistent", "55000");
                            result.set(new SecretReference(rows.getObject(1, UUID.class), secret, businessKey,
                                    ReferenceState.valueOf(rows.getString(3))));
                            return;
                        }
                    }
                }
                UUID id = UUID.randomUUID();
                try (var statement = connection.prepareStatement(
                        "INSERT INTO mod_credential_reference(reference_id,credential_id,mod_id,schema_version,schema_generation,business_key,purpose,state,updated_at) VALUES(?,?,?,?,?,?,?,'RESERVED',?)")) {
                    statement.setObject(1, id);
                    statement.setString(2, secret.id());
                    statement.setString(3, schema.ownerId());
                    statement.setString(4, schema.schemaVersion());
                    statement.setObject(5, schema.generation());
                    statement.setString(6, businessKey);
                    statement.setString(7, secret.purpose());
                    statement.setLong(8, System.currentTimeMillis());
                    statement.executeUpdate();
                }
                result.set(new SecretReference(id, secret, businessKey, ReferenceState.RESERVED));
            }
        });
        return result.get();
    }

    /**
     * Resolves an existing association in its exact owning generation. / 在精确所属代次中核对已有关系。
     * @param schema admitted data generation / 已准入数据代次
     * @param referenceId original association / 原关联
     * @param result author-confirmed result / 作者确认的结果
     * @throws SQLException if ownership or persistence cannot be verified / 无法核实归属或持久化时
     */
    public void resolve(SchemaIdentity schema, UUID referenceId, ReferenceResolution result) throws SQLException {
        connections.transaction(() -> {
            try (var connection = connections.open()) {
                RepositoryConnections.lockPlatformState(connection);
                new SchemaCoordinates(schema).requireRegistered(connection, Set.of("READY"));
                String credential;
                try (var statement = connection.prepareStatement(
                        "SELECT mod_id,schema_version,schema_generation,credential_id,purpose FROM mod_credential_reference WHERE reference_id=?")) {
                    statement.setObject(1, referenceId);
                    try (var row = statement.executeQuery()) {
                        if (!row.next()) {
                            if (result == ReferenceResolution.NOT_REFERENCED)
                                return;
                            throw new SQLException("Credential reservation is absent", "02000");
                        }
                        if (!schema.ownerId().equals(row.getString(1))
                                || !schema.schemaVersion().equals(row.getString(2))
                                || !schema.generation().equals(row.getObject(3, UUID.class)))
                            throw new SQLException("Credential reference belongs to another generation", "42501");
                        credential = row.getString(4);
                        requireOwned(schema.ownerId(), new SecretRef(credential, row.getString(5)));
                    }
                }
                lockCredential(connection, credential);
                if (result == ReferenceResolution.NOT_REFERENCED) {
                    try (var statement = connection
                            .prepareStatement("DELETE FROM mod_credential_reference WHERE reference_id=?")) {
                        statement.setObject(1, referenceId);
                        if (statement.executeUpdate() != 1)
                            throw new SQLException("Credential reference release was not confirmed");
                    }
                } else {
                    try (var statement = connection.prepareStatement(
                            "UPDATE mod_credential_reference SET state=?,updated_at=? WHERE reference_id=?")) {
                        statement.setString(1, result == ReferenceResolution.REFERENCED ? "ACTIVE" : "UNKNOWN");
                        statement.setLong(2, System.currentTimeMillis());
                        statement.setObject(3, referenceId);
                        if (statement.executeUpdate() != 1)
                            throw new SQLException("Credential reference result was not confirmed");
                    }
                }
            }
        });
    }

    /** Locks the actual stored credential before reference mutation. / 引用变更前锁定实际存储的凭据。
     * @param connection current transaction / 当前事务
     * @param id exact credential identity / 精确凭据身份
     * @throws SQLException if the credential is absent / 凭据不存在时 */
    private static void lockCredential(Connection connection, String id) throws SQLException {
        try (var statement = connection
                .prepareStatement("SELECT credential_id FROM credential WHERE credential_id=? FOR UPDATE")) {
            statement.setString(1, id);
            try (var rows = statement.executeQuery()) {
                if (!rows.next())
                    throw new SQLException("Credential is absent", "02000");
            }
        }
    }
}
