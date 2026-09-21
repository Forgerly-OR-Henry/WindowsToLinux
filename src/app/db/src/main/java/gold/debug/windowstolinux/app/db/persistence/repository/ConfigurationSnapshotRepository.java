package gold.debug.windowstolinux.app.db.persistence.repository;

import gold.debug.windowstolinux.app.db.persistence.connection.DesktopConnectionFactory;
import gold.debug.windowstolinux.shared.config.contract.definition.ConfigurationScope;
import gold.debug.windowstolinux.shared.config.contract.definition.ConfigurationValue;
import gold.debug.windowstolinux.shared.config.revision.ConfigurationEntry;
import gold.debug.windowstolinux.shared.config.revision.ConfigurationSnapshot;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Stores immutable ordinary-configuration snapshots, entries, and exact release bindings. / 保存不可变的普通配置快照、条目及精确发布绑定。
 */
public final class ConfigurationSnapshotRepository {
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
    public ConfigurationSnapshotRepository(DesktopConnectionFactory connections) {
        this.connections = Objects.requireNonNull(connections, "connections");
    }

    /**
     * Persists an immutable configuration revision with its canonical typed entries.
     * <p>持久化不可变配置修订及其规范类型化条目。
     *
     * @param snapshot immutable observation or configuration revision used by the operation / 操作使用的不可变观测或配置修订
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public void save(ConfigurationSnapshot snapshot) throws SQLException {
        Objects.requireNonNull(snapshot, "snapshot");
        try (Connection connection = connections.open()) {
            RepositoryTransactionExecutor.execute(connection, () -> save(connection, snapshot));
        }
    }

    /**
     * Finds the exact immutable configuration used by one release identity. / 查找一个发布身份实际使用的精确不可变配置。
     *
     * @param applicationId managed application identifier / 受管应用标识
     * @param releaseIdentity digest identifying the exact published release / 标识精确已发布版本的摘要
     * @return matching result, or empty when no admitted value exists / 匹配结果；不存在已准入内容时为空
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     */
    public Optional<ConfigurationSnapshot> findRelease(String applicationId, String releaseIdentity)
            throws SQLException {
        applicationId = boundedIdentity(applicationId, "applicationId");
        releaseIdentity = boundedIdentity(releaseIdentity, "releaseIdentity");
        try (Connection connection = connections.open(); PreparedStatement statement = connection.prepareStatement("""
                SELECT configuration_revision, configuration_sha256
                FROM application_release_configuration_binding
                WHERE application_id=? AND release_identity=?
                """)) {
            statement.setString(1, applicationId);
            statement.setString(2, releaseIdentity);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) return Optional.empty();
                ConfigurationSnapshot snapshot = find(connection, applicationId,
                        result.getLong("configuration_revision")).orElseThrow(() ->
                        new SQLException("release configuration binding references a missing snapshot"));
                if (!snapshot.sha256().equals(result.getString("configuration_sha256"))) {
                    throw new SQLException("release configuration binding digest is inconsistent");
                }
                return Optional.of(snapshot);
            }
        }
    }

    /**
     * Reads the requested immutable configuration revision and reconstructs its typed entries.
     * <p>读取请求的不可变配置修订，并重建其类型化条目。
     *
     * @param applicationId managed application identifier / 受管应用标识
     * @param revision immutable configuration or secret revision number / 不可变配置或秘密修订号
     * @return matching result, or empty when no admitted value exists / 匹配结果；不存在已准入内容时为空
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     */
    public Optional<ConfigurationSnapshot> find(String applicationId, long revision) throws SQLException {
        try (Connection connection = connections.open()) {
            return find(connection, applicationId, revision);
        }
    }

    /**
     * Reads the requested immutable configuration revision and reconstructs its typed entries.
     * <p>读取请求的不可变配置修订，并重建其类型化条目。
     *
     * @param connection connection scoped to the current database or remote operation / 限定于当前数据库或远端操作的连接
     * @param applicationId managed application identifier / 受管应用标识
     * @param revision immutable configuration or secret revision number / 不可变配置或秘密修订号
     * @return matching result, or empty when no admitted value exists / 匹配结果；不存在已准入内容时为空
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     */
    private static Optional<ConfigurationSnapshot> find(Connection connection, String applicationId, long revision)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT schema_version, created_at, sha256 FROM application_configuration_snapshot
                WHERE application_id=? AND revision=?
                """)) {
            statement.setString(1, applicationId);
            statement.setLong(2, revision);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return Optional.empty();
                }
                String schemaVersion = result.getString("schema_version");
                Instant createdAt = Instant.ofEpochMilli(result.getLong("created_at"));
                String sha256 = result.getString("sha256");
                List<ConfigurationEntry> entries = new ArrayList<>();
                try (PreparedStatement entriesStatement = connection.prepareStatement("""
                        SELECT config_key, value_type, config_scope, value_text FROM application_configuration_entry
                        WHERE application_id=? AND revision=? ORDER BY config_key, config_scope
                        """)) {
                    entriesStatement.setString(1, applicationId);
                    entriesStatement.setLong(2, revision);
                    try (ResultSet entry = entriesStatement.executeQuery()) {
                        while (entry.next()) {
                            entries.add(new ConfigurationEntry(entry.getString("config_key"),
                                    ConfigurationScope.valueOf(entry.getString("config_scope")),
                                    value(entry.getString("value_type"), entry.getString("value_text"))));
                        }
                    }
                }
                try {
                    return Optional.of(new ConfigurationSnapshot(applicationId, revision, schemaVersion, createdAt,
                            entries, sha256));
                } catch (IllegalArgumentException exception) {
                    throw new SQLException("saved application configuration snapshot violates current validation rules", exception);
                }
            }
        }
    }

    /**
     * Saves the immutable configuration snapshot and binds it to the successful release atomically.
     * <p>原子保存不可变配置快照并将其绑定到成功发布。
     *
     * @param connection connection scoped to the current database or remote operation / 限定于当前数据库或远端操作的连接
     * @param snapshot immutable observation or configuration revision used by the operation / 操作使用的不可变观测或配置修订
     * @param releaseIdentity digest identifying the exact published release / 标识精确已发布版本的摘要
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    static void saveAndBindRelease(Connection connection, ConfigurationSnapshot snapshot, String releaseIdentity)
            throws SQLException {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(snapshot, "snapshot");
        String application = boundedIdentity(snapshot.applicationId(), "applicationId");
        String release = boundedIdentity(releaseIdentity, "releaseIdentity");
        save(connection, snapshot);
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT configuration_revision, configuration_sha256
                FROM application_release_configuration_binding
                WHERE application_id=? AND release_identity=?
                """)) {
            statement.setString(1, application);
            statement.setString(2, release);
            try (ResultSet result = statement.executeQuery()) {
                if (result.next()) {
                    if (result.getLong("configuration_revision") != snapshot.revision()
                            || !result.getString("configuration_sha256").equals(snapshot.sha256())) {
                        throw new SQLException("release configuration bindings are immutable");
                    }
                    return;
                }
            }
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO application_release_configuration_binding (
                    application_id, release_identity, configuration_revision, configuration_sha256
                ) VALUES (?, ?, ?, ?)
                """)) {
            statement.setString(1, application);
            statement.setString(2, release);
            statement.setLong(3, snapshot.revision());
            statement.setString(4, snapshot.sha256());
            statement.executeUpdate();
        }
    }

    /**
     * Persists an immutable configuration revision with its canonical typed entries.
     * <p>持久化不可变配置修订及其规范类型化条目。
     *
     * @param connection connection scoped to the current database or remote operation / 限定于当前数据库或远端操作的连接
     * @param snapshot immutable observation or configuration revision used by the operation / 操作使用的不可变观测或配置修订
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     */
    private static void save(Connection connection, ConfigurationSnapshot snapshot) throws SQLException {
        Optional<ConfigurationSnapshot> existing = find(connection, snapshot.applicationId(), snapshot.revision());
        if (existing.isPresent()) {
            ConfigurationSnapshot stored = existing.orElseThrow();
            // SQLite stores milliseconds and reads entries in key order; the digest binds their canonical content. / SQLite 保存毫秒并按键顺序读取条目，摘要绑定其规范化内容。
            // SQLite 保存毫秒并按键读取条目，摘要绑定规范内容，避免精度与排序差异被误判为修改。
            if (!stored.sha256().equals(snapshot.sha256())
                    || stored.createdAt().toEpochMilli() != snapshot.createdAt().toEpochMilli()) {
                throw new SQLException("application configuration revisions are immutable");
            }
            return;
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO application_configuration_snapshot (
                    application_id, revision, schema_version, created_at, sha256
                ) VALUES (?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, snapshot.applicationId());
            statement.setLong(2, snapshot.revision());
            statement.setString(3, snapshot.schemaVersion());
            statement.setLong(4, snapshot.createdAt().toEpochMilli());
            statement.setString(5, snapshot.sha256());
            statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO application_configuration_entry (
                    application_id, revision, config_key, value_type, config_scope, value_text
                ) VALUES (?, ?, ?, ?, ?, ?)
                """)) {
            for (ConfigurationEntry entry : snapshot.entries()) {
                statement.setString(1, snapshot.applicationId());
                statement.setLong(2, snapshot.revision());
                statement.setString(3, entry.key());
                statement.setString(4, valueType(entry.value()));
                statement.setString(5, entry.scope().name());
                statement.setString(6, entry.value().canonicalValue());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    /**
     * Validates and produces bounded identity for the next contract boundary.
     * <p>校验并生成供下一契约边界使用的有界身份。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     * @param field field name or input definition being validated / 正在校验的字段名或输入定义
     * @return bounded identity text / 有界身份文本
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    private static String boundedIdentity(String value, String field) {
        value = Objects.requireNonNull(value, field).trim();
        if (!value.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
            throw new IllegalArgumentException(field + " must be a bounded release identity");
        }
        return value;
    }

    /**
     * Encodes the configuration value variant as its stable TEXT, NUMBER or FLAG discriminator.
     * <p>将配置值变体编码为稳定的 TEXT、NUMBER 或 FLAG 判别码。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     * @return the configuration value variant as its stable TEXT, NUMBER or FLAG discriminator / 将配置值变体编码为稳定的 TEXT、NUMBER 或 FLAG 判别码
     */
    private static String valueType(ConfigurationValue value) {
        return switch (value) {
            case ConfigurationValue.Text ignored -> "TEXT";
            case ConfigurationValue.Number ignored -> "NUMBER";
            case ConfigurationValue.Flag ignored -> "FLAG";
        };
    }

    /**
     * Reconstructs a typed configuration value from the stored discriminator and text, rejecting malformed rows.
     * <p>根据持久化判别码及文本重建配置值，并拒绝格式无效的数据行。
     *
     * @param type selected member of the supported type set / 受支持类型集合中的所选项
     * @param text bounded text consumed or produced by the current formatter / 当前格式化器消费或生成的有界文本
     * @return constructed or resolved configuration value / 构造或解析得到的配置内容
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     */
    private static ConfigurationValue value(String type, String text) throws SQLException {
        try {
            return switch (type) {
                case "TEXT" -> new ConfigurationValue.Text(text);
                case "NUMBER" -> new ConfigurationValue.Number(Long.parseLong(text));
                case "FLAG" -> {
                    if (!"true".equals(text) && !"false".equals(text)) {
                        throw new IllegalArgumentException("flag configuration values must be true or false");
                    }
                    yield new ConfigurationValue.Flag(Boolean.parseBoolean(text));
                }
                default -> throw new IllegalArgumentException("unknown configuration value type");
            };
        } catch (IllegalArgumentException exception) {
            throw new SQLException("saved application configuration entry violates current validation rules", exception);
        }
    }
}
