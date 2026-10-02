package gold.debug.windowstolinux.app.db.security;

import java.sql.*;
import java.util.*;

import gold.debug.windowstolinux.app.db.security.StoredCredential;
import gold.debug.windowstolinux.app.db.security.StoredSecurityProfile;
import gold.debug.windowstolinux.app.db.connect.RepositoryConnections;

/** Persists opaque security records and atomic credential/owner changes. / 持久化不透明安全记录及原子凭据归属变更。 */
public final class SecurityRepository {
    /** Scoped database connection factory. / 限定数据库连接工厂。
     */
    private final RepositoryConnections connections;

    /** Query for retained business credential references. / 保留业务凭据引用的查询。 */
    private final String referenceQuery;

    /** Binds H2 platform transactions and generation-aware Mod references. / 绑定 H2 平台事务及带代次的插件引用。
     * @param connections platform transaction scope / 平台事务作用域
     */
    public SecurityRepository(RepositoryConnections connections) {
        this.connections = Objects.requireNonNull(connections);
        this.referenceQuery = "SELECT 1 FROM mod_credential_reference WHERE credential_id=? LIMIT 1";
    }

    /** Loads the singleton security profile. / 读取唯一安全配置。
     * @return the validated result or retained scoped handle / 已验证结果或保留的限定句柄
     * @throws SQLException if validation or the scoped operation fails / 校验或限定操作失败时
     */
    public Optional<StoredSecurityProfile> profile() throws SQLException {
        try (var c = connections.open();
             var s = c.prepareStatement("SELECT * FROM security_profile WHERE security_profile_id=1");
             var r = s.executeQuery()) {
            return r.next()
                ? Optional.of(new StoredSecurityProfile(r.getBoolean("is_encryption_enabled"),
                r.getString("password_verifier"), r.getBytes("wrapped_data_key"),
                r.getString("key_wrap_algorithm"), r.getBytes("key_wrap_nonce"),
                r.getString("key_derivation_algorithm"), r.getBytes("key_derivation_salt"),
                r.getString("key_derivation_parameters"), r.getInt("format_version")))
                : Optional.empty();
        }
    }

    /** Creates the singleton security profile for an unconfigured database. / 为尚未配置的数据库创建唯一安全配置。
     * @param profile profile supplied to this boundary / 传入当前边界的profile值
     * @throws SQLException if validation or the scoped operation fails / 校验或限定操作失败时
     */
    public void initialize(StoredSecurityProfile profile) throws SQLException {
        transaction(() -> {
            if (profile().isPresent() || !credentialIds().isEmpty())
                throw new SQLException("security is already initialized or inconsistent");
            writeProfile(profile, false);
        });
    }

    /** Updates the existing singleton security profile. / 更新已有唯一安全配置。
     * @param profile profile supplied to this boundary / 传入当前边界的profile值
     * @throws SQLException if validation or the scoped operation fails / 校验或限定操作失败时
     */
    public void updateProfile(StoredSecurityProfile profile) throws SQLException {
        writeProfile(profile, true);
    }

    /** Binds the security profile fields to one insert or update. / 将安全配置字段绑定到一次插入或更新。
     * @param profile profile supplied to this boundary / 传入当前边界的profile值
     * @param update update supplied to this boundary / 传入当前边界的update值
     * @throws SQLException if validation or the scoped operation fails / 校验或限定操作失败时
     */
    private void writeProfile(StoredSecurityProfile profile, boolean update) throws SQLException {
        String sql = update
            ? "UPDATE security_profile SET is_encryption_enabled=?,password_verifier=?,wrapped_data_key=?,key_wrap_algorithm=?,key_wrap_nonce=?,key_derivation_algorithm=?,key_derivation_salt=?,key_derivation_parameters=?,format_version=? WHERE security_profile_id=1"
            : "INSERT INTO security_profile(is_encryption_enabled,password_verifier,wrapped_data_key,key_wrap_algorithm,key_wrap_nonce,key_derivation_algorithm,key_derivation_salt,key_derivation_parameters,format_version,security_profile_id) VALUES(?,?,?,?,?,?,?,?,?,1)";
        try (var c = connections.open(); var s = c.prepareStatement(sql)) {
            s.setInt(1, profile.encryptionEnabled() ? 1 : 0);
            s.setString(2, profile.passwordVerifier());
            s.setBytes(3, profile.wrappedDataKey());
            s.setString(4, profile.keyWrapAlgorithm());
            s.setBytes(5, profile.keyWrapNonce());
            s.setString(6, profile.keyDerivationAlgorithm());
            s.setBytes(7, profile.keyDerivationSalt());
            s.setString(8, profile.keyDerivationParameters());
            s.setInt(9, profile.formatVersion());
            if (s.executeUpdate() != 1)
                throw new SQLException("security profile missing");
        }
    }

    /** Reads one credential record without interpreting its protected payload. / 读取一条凭据记录，不解释其受保护载荷。
     * @param id stable persisted identifier / 稳定持久化标识
     * @return the validated result or retained scoped handle / 已验证结果或保留的限定句柄
     * @throws SQLException if validation or the scoped operation fails / 校验或限定操作失败时
     */
    public Optional<StoredCredential> find(String id) throws SQLException {
        try (var c = connections.open();
             var s = c.prepareStatement(
                 "SELECT storage_format,payload,nonce FROM credential WHERE credential_id=?")) {
            s.setString(1, id);
            try (var r = s.executeQuery()) {
                return r.next()
                    ? Optional.of(new StoredCredential(id, r.getString(1), r.getBytes(2), r.getBytes(3)))
                    : Optional.empty();
            }
        }
    }

    /** Lists credential identities for transactional conversion. / 列出凭据标识以进行事务转换。
     * @return the validated result or retained scoped handle / 已验证结果或保留的限定句柄
     * @throws SQLException if validation or the scoped operation fails / 校验或限定操作失败时
     */
    public List<String> credentialIds() throws SQLException {
        try (var c = connections.open();
             var s = c.prepareStatement("SELECT credential_id FROM credential ORDER BY credential_id");
             var r = s.executeQuery()) {
            var ids = new ArrayList<String>();
            while (r.next())
                ids.add(r.getString(1));
            return List.copyOf(ids);
        }
    }

    /** Validates and persists the supplied state in its transaction boundary. / 在对应事务边界内验证并保存传入状态。
     * @param credential credential supplied to this boundary / 传入当前边界的credential值
     * @param contentChanged whether actual credential content changed / 实际凭据内容是否改变
     * @throws SQLException if validation or the scoped operation fails / 校验或限定操作失败时
     */
    public void save(StoredCredential credential, boolean contentChanged) throws SQLException {
        transaction(() -> {
            try (var c = connections.open()) {
                RepositoryConnections.lockPlatformState(c);
                byte[] payload = credential.payload();
                try {
                    int updated;
                    try (var s = c.prepareStatement(
                        "UPDATE credential SET storage_format=?,payload=?,nonce=? WHERE credential_id=?")) {
                        s.setString(1, credential.storageFormat());
                        s.setBytes(2, payload);
                        s.setBytes(3, credential.nonce());
                        s.setString(4, credential.id());
                        updated = s.executeUpdate();
                    }
                    if (updated == 0)
                        try (var s = c.prepareStatement(
                            "INSERT INTO credential(credential_id,storage_format,payload,nonce) VALUES(?,?,?,?)")) {
                            s.setString(1, credential.id());
                            s.setString(2, credential.storageFormat());
                            s.setBytes(3, payload);
                            s.setBytes(4, credential.nonce());
                            s.executeUpdate();
                        }
                } finally {
                    Arrays.fill(payload, (byte) 0);
                }
                if (contentChanged) {
                    try (var update = c.prepareStatement(
                        "UPDATE ai_model SET configuration_revision=configuration_revision+1 WHERE credential_id=?")) {
                        update.setString(1, credential.id());
                        update.executeUpdate();
                    }
                }
            }
        });
    }

    /** Deletes an unreferenced credential while preserving foreign-key protection. / 删除未被引用的凭据并保留外键保护。
     * @param id stable persisted identifier / 稳定持久化标识
     * @return the validated result or retained scoped handle / 已验证结果或保留的限定句柄
     * @throws SQLException if validation or the scoped operation fails / 校验或限定操作失败时
     */
    public boolean delete(String id) throws SQLException {
        try (var c = connections.open(); var s = c.prepareStatement("DELETE FROM credential WHERE credential_id=?")) {
            s.setString(1, id);
            return s.executeUpdate() == 1;
        }
    }

    /** Checks whether immutable business-secret history owns this credential. / 检查不可变业务秘密历史是否引用此凭据。
     * @param id stable persisted identifier / 稳定持久化标识
     * @return the validated result or retained scoped handle / 已验证结果或保留的限定句柄
     * @throws SQLException if validation or the scoped operation fails / 校验或限定操作失败时
     */
    public boolean isVersioned(String id) throws SQLException {
        try (var c = connections.open(); var s = c.prepareStatement(referenceQuery)) {
            s.setString(1, id);
            try (var rows = s.executeQuery()) {
                return rows.next();
            }
        }
    }

    /**
     * Checks all platform configuration owners and persistent business reservations. / 检查全部平台配置所有者及持久业务预留。
     * @param id exact credential identity / 精确凭据身份
     * @return whether any owner still requires the credential / 是否仍有所有者需要该凭据
     * @throws SQLException if ownership cannot be verified / 无法核实归属时
     */
    public boolean hasOwners(String id) throws SQLException {
        try (var connection = connections.open()) {
            for (String query : java.util.List.of("SELECT 1 FROM server_profile WHERE credential_id=? LIMIT 1",
                "SELECT 1 FROM ai_model WHERE credential_id=? LIMIT 1", referenceQuery)) {
                try (var statement = connection.prepareStatement(query)) {
                    statement.setString(1, id);
                    try (var rows = statement.executeQuery()) {
                        if (rows.next())
                            return true;
                    }
                }
            }
        }
        return false;
    }

    /** Runs related repository and credential writes on one database transaction. / 在同一数据库事务中执行关联仓库及凭据写入。
     * @param work operation executed within the retained boundary / 保留边界内执行的操作
     * @throws SQLException if validation or the scoped operation fails / 校验或限定操作失败时
     */
    public void transaction(RepositoryConnections.Work work) throws SQLException {
        connections.transaction(work);
    }
}
