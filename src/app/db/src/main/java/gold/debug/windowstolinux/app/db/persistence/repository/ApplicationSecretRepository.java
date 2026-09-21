package gold.debug.windowstolinux.app.db.persistence.repository;

import gold.debug.windowstolinux.app.db.persistence.connection.DesktopConnectionFactory;
import gold.debug.windowstolinux.app.db.entity.StoredApplicationSecretRevision;
import gold.debug.windowstolinux.shared.config.secretref.SecretReference;
import gold.debug.windowstolinux.shared.model.security.CredentialStorageMode;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Stores immutable application-secret metadata and release bindings, never secret values. / 保存不可变应用秘密元数据与发布绑定，绝不保存秘密值。
 */
public final class ApplicationSecretRepository {
    /**
     * Factory for scoped database connections.
     * <p>限定作用域数据库连接的工厂。
     */
    private final DesktopConnectionFactory connections;

    /**
     * Creates the repository. / 创建仓库。
     *
     * @param connections factory for scoped database connections / 限定作用域数据库连接的工厂
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public ApplicationSecretRepository(DesktopConnectionFactory connections) {
        this.connections = Objects.requireNonNull(connections, "connections");
    }

    /**
     * Saves immutable secret-revision metadata. / 保存不可变秘密修订元数据。
     *
     * @param revision immutable configuration or secret revision number / 不可变配置或秘密修订号
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public void saveRevision(StoredApplicationSecretRevision revision) throws SQLException {
        Objects.requireNonNull(revision, "revision");
        try (Connection connection = connections.open()) {
            RepositoryTransactionExecutor.execute(connection, () -> {
                Optional<StoredApplicationSecretRevision> existing = findRevision(connection, revision.reference());
                if (existing.isPresent()) {
                    StoredApplicationSecretRevision stored = existing.orElseThrow();
                    if (!stored.credentialKey().equals(revision.credentialKey())
                            || stored.credentialMode() != revision.credentialMode()
                            || stored.createdAt().toEpochMilli() != revision.createdAt().toEpochMilli()) {
                        throw new SQLException("application secret revisions are immutable");
                    }
                    return;
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO application_secret_revision (
                            secret_identifier, revision, credential_key, credential_mode, created_at
                        ) VALUES (?, ?, ?, ?, ?)
                        """)) {
                    statement.setString(1, revision.reference().identifier());
                    statement.setLong(2, revision.reference().revision());
                    statement.setString(3, revision.credentialKey());
                    statement.setString(4, revision.credentialMode().name());
                    statement.setLong(5, revision.createdAt().toEpochMilli());
                    statement.executeUpdate();
                }
            });
        }
    }

    /**
     * Finds immutable secret-revision metadata. / 查找不可变秘密修订元数据。
     *
     * @param reference immutable public secret identity / 不可变公开秘密身份
     * @return matching result, or empty when no admitted value exists / 匹配结果；不存在已准入内容时为空
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     */
    public Optional<StoredApplicationSecretRevision> findRevision(SecretReference reference) throws SQLException {
        try (Connection connection = connections.open()) {
            return findRevision(connection, reference);
        }
    }

    /**
     * Finds the exact secret-revision set used by one release, including an explicit empty set. / 查找一个发布实际使用的精确秘密修订集合，包括显式空集合。
     *
     * @param applicationId managed application identifier / 受管应用标识
     * @param releaseIdentity digest identifying the exact published release / 标识精确已发布版本的摘要
     * @return matching result, or empty when no admitted value exists / 匹配结果；不存在已准入内容时为空
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     */
    public Optional<List<SecretReference>> findRelease(String applicationId, String releaseIdentity)
            throws SQLException {
        applicationId = identity(applicationId, "applicationId");
        releaseIdentity = identity(releaseIdentity, "releaseIdentity");
        try (Connection connection = connections.open()) {
            if (!bindingExists(connection, applicationId, releaseIdentity)) {
                return Optional.empty();
            }
            return Optional.of(findReleaseReferences(connection, applicationId, releaseIdentity));
        }
    }

    /**
     * Binds exact stored secret revisions to the selected release within a transaction.
     * <p>在事务内将精确持久化秘密修订绑定到所选发布。
     *
     * @param applicationId managed application identifier / 受管应用标识
     * @param releaseIdentity digest identifying the exact published release / 标识精确已发布版本的摘要
     * @param references references / 引用集合
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public void bindRelease(String applicationId, String releaseIdentity, List<SecretReference> references) throws SQLException {
        applicationId = identity(applicationId, "applicationId");
        releaseIdentity = identity(releaseIdentity, "releaseIdentity");
        Set<SecretReference> expected = Set.copyOf(Objects.requireNonNull(references, "references"));
        if (expected.size() != references.size()) {
            throw new IllegalArgumentException("release secret references must be unique");
        }
        String application = applicationId;
        String release = releaseIdentity;
        try (Connection connection = connections.open()) {
            RepositoryTransactionExecutor.execute(connection, () -> bindRelease(connection, application, release, expected));
        }
    }

    /**
     * Binds exact stored secret revisions to the selected release within a transaction.
     * <p>在事务内将精确持久化秘密修订绑定到所选发布。
     *
     * @param connection connection scoped to the current database or remote operation / 限定于当前数据库或远端操作的连接
     * @param application managed target with its server and ownership identity / 携带服务器及归属身份的受管目标
     * @param release release / 发布
     * @param expected identity, value or state required for verification / 验证要求的身份、内容或状态
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     */
    static void bindRelease(Connection connection, String application, String release,
                            Set<SecretReference> expected) throws SQLException {
        for (SecretReference reference : expected) {
            if (findRevision(connection, reference).isEmpty()) {
                throw new SQLException("release secret reference has not been registered");
            }
        }
        boolean exists = bindingExists(connection, application, release);
        Set<SecretReference> stored = Set.copyOf(findReleaseReferences(connection, application, release));
        if (exists) {
            if (!stored.equals(expected)) {
                throw new SQLException("application release secret references are immutable");
            }
            return;
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO application_release_secret_binding (application_id, release_identity) VALUES (?, ?)
                """)) {
            statement.setString(1, application);
            statement.setString(2, release);
            statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO application_release_secret_reference (
                    application_id, release_identity, secret_identifier, secret_revision
                ) VALUES (?, ?, ?, ?)
                """)) {
            for (SecretReference reference : expected) {
                statement.setString(1, application);
                statement.setString(2, release);
                statement.setString(3, reference.identifier());
                statement.setLong(4, reference.revision());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    /**
     * Reports whether a secret revision is retained by a release. / 报告秘密修订是否由发布保留。
     *
     * @param reference immutable public secret identity / 不可变公开秘密身份
     * @return true when a secret revision is retained by a release, false otherwise / 报告秘密修订是否由发布保留时为 true，否则为 false
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     */
    public boolean isReferenced(SecretReference reference) throws SQLException {
        try (Connection connection = connections.open();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT 1 FROM application_release_secret_reference
                     WHERE secret_identifier=? AND secret_revision=? LIMIT 1
                     """)) {
            statement.setString(1, reference.identifier());
            statement.setLong(2, reference.revision());
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    /**
     * Finds immutable configuration or secret revision number.
     * <p>查找不可变配置或秘密修订号。
     *
     * @param connection connection scoped to the current database or remote operation / 限定于当前数据库或远端操作的连接
     * @param reference immutable public secret identity / 不可变公开秘密身份
     * @return matching result, or empty when no admitted value exists / 匹配结果；不存在已准入内容时为空
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    private static Optional<StoredApplicationSecretRevision> findRevision(Connection connection, SecretReference reference)
            throws SQLException {
        Objects.requireNonNull(reference, "reference");
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT credential_key, credential_mode, created_at FROM application_secret_revision
                WHERE secret_identifier=? AND revision=?
                """)) {
            statement.setString(1, reference.identifier());
            statement.setLong(2, reference.revision());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return Optional.empty();
                }
                try {
                    return Optional.of(new StoredApplicationSecretRevision(reference, result.getString("credential_key"),
                            CredentialStorageMode.valueOf(result.getString("credential_mode")),
                            Instant.ofEpochMilli(result.getLong("created_at"))));
                } catch (IllegalArgumentException exception) {
                    throw new SQLException("saved application secret revision violates current validation rules", exception);
                }
            }
        }
    }

    /**
     * Finds release references.
     * <p>查找发布引用集合。
     *
     * @param connection connection scoped to the current database or remote operation / 限定于当前数据库或远端操作的连接
     * @param application managed target with its server and ownership identity / 携带服务器及归属身份的受管目标
     * @param release release / 发布
     * @return release references / 发布引用集合
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     */
    private static List<SecretReference> findReleaseReferences(Connection connection, String application, String release)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT secret_identifier, secret_revision FROM application_release_secret_reference
                WHERE application_id=? AND release_identity=? ORDER BY secret_identifier, secret_revision
                """)) {
            statement.setString(1, application);
            statement.setString(2, release);
            try (ResultSet result = statement.executeQuery()) {
                List<SecretReference> references = new ArrayList<>();
                while (result.next()) {
                    references.add(new SecretReference(result.getString("secret_identifier"),
                            result.getLong("secret_revision")));
                }
                return List.copyOf(references);
            }
        }
    }

    /**
     * Tests the binding exists predicate against the supplied evidence.
     * <p>根据所提供证据检查绑定存在条件。
     *
     * @param connection connection scoped to the current database or remote operation / 限定于当前数据库或远端操作的连接
     * @param application managed target with its server and ownership identity / 携带服务器及归属身份的受管目标
     * @param release release / 发布
     * @return true when binding exists predicate against the supplied evidence, false otherwise / 根据所提供证据检查绑定存在条件时为 true，否则为 false
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     */
    private static boolean bindingExists(Connection connection, String application, String release) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT 1 FROM application_release_secret_binding WHERE application_id=? AND release_identity=?
                """)) {
            statement.setString(1, application);
            statement.setString(2, release);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    /**
     * Checks identity syntax and bounds before returning the admitted content.
     * <p>在返回已准入内容前检查身份语法及边界。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     * @param name human-readable name or diagnostic field label / 可读名称或诊断字段标签
     * @return identity text / 身份文本
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    private static String identity(String value, String name) {
        value = Objects.requireNonNull(value, name).trim();
        if (!value.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
            throw new IllegalArgumentException(name + " must be a bounded release identity");
        }
        return value;
    }
}
