package gold.debug.windowstolinux.mod.deployment.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import gold.debug.windowstolinux.appapi.db.ModDatabase;
import gold.debug.windowstolinux.appapi.task.ApiException;
import gold.debug.windowstolinux.appapi.task.TaskContext;
import gold.debug.windowstolinux.mod.deployment.service.core.config.*;

/**
 * Stores immutable ordinary-configuration snapshots, entries, and exact release bindings. / 保存不可变的普通配置快照、条目及精确发布绑定。
 */
public final class DeploymentConfigurationRepository {
    /**
     * Owned task-bound database capability.
     * <p>所属任务绑定数据库能力。
     */
    private final ModDatabase database;

    /**
     * Creates the repository. / 创建仓库。
     *
     * @param database owned public database capability / 所属公开数据库能力
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public DeploymentConfigurationRepository(ModDatabase database) {
        this.database = Objects.requireNonNull(database, "database");
    }

    /**
     * Persists an immutable configuration revision with its canonical typed entries.
     * <p>持久化不可变配置修订及其规范类型化条目。
     *
     * @param snapshot immutable observation or configuration revision used by the operation / 操作使用的不可变观测或配置修订
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
      * @param task original active author task / 原活动作者任务
     * @throws ApiException if the original task or capability is unavailable / 原任务或能力不可用时
     */
    public void save(TaskContext task, DeploymentConfigurationSnapshot snapshot) throws SQLException, ApiException {
        Objects.requireNonNull(snapshot, "snapshot");
        Connection connection = connection(task);
        DeploymentDataTransaction.execute(connection, () -> save(connection, snapshot));
    }

    /**
     * Finds the exact immutable configuration used by one release identity. / 查找一个发布身份实际使用的精确不可变配置。
     *
     * @param applicationId managed application identifier / 受管应用标识
     * @param releaseIdentity digest identifying the exact published release / 标识精确已发布版本的摘要
     * @return matching result, or empty when no admitted value exists / 匹配结果；不存在已准入内容时为空
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
      * @param task original active author task / 原活动作者任务
     * @throws ApiException if the original task or capability is unavailable / 原任务或能力不可用时
     */
    public Optional<DeploymentConfigurationSnapshot> findRelease(TaskContext task, String applicationId,
            String releaseIdentity) throws SQLException, ApiException {
        applicationId = boundedIdentity(applicationId, "applicationId");
        releaseIdentity = boundedIdentity(releaseIdentity, "releaseIdentity");
        try (Connection connection = connection(task); PreparedStatement statement = connection.prepareStatement("""
                SELECT configuration_revision, configuration_sha256
                FROM deployment_unit_release_binding
                WHERE deployment_unit_id=? AND release_sha256=?
                """)) {
            statement.setString(1, applicationId);
            statement.setString(2, releaseIdentity);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next() || result.getObject("configuration_revision") == null)
                    return Optional.empty();
                DeploymentConfigurationSnapshot snapshot = find(connection, applicationId,
                        result.getLong("configuration_revision")).orElseThrow(
                                () -> new SQLException("release configuration binding references a missing snapshot"));
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
      * @param task original active author task / 原活动作者任务
     * @throws ApiException if the original task or capability is unavailable / 原任务或能力不可用时
     */
    public Optional<DeploymentConfigurationSnapshot> find(TaskContext task, String applicationId, long revision)
            throws SQLException, ApiException {
        try (Connection connection = connection(task)) {
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
    private static Optional<DeploymentConfigurationSnapshot> find(Connection connection, String applicationId,
            long revision) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT schema_version, created_at, configuration_sha256 FROM deployment_unit_configuration_snapshot
                WHERE deployment_unit_id=? AND configuration_revision=?
                """)) {
            statement.setString(1, applicationId);
            statement.setLong(2, revision);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return Optional.empty();
                }
                String schemaVersion = result.getString("schema_version");
                Instant createdAt = Instant.ofEpochMilli(result.getLong("created_at"));
                String sha256 = result.getString("configuration_sha256");
                try {
                    List<DeploymentConfigurationEntry> entries = new ArrayList<>();
                    try (PreparedStatement entriesStatement = connection.prepareStatement(
                            """
                                    SELECT configuration_key, value_type, configuration_scope, value_text FROM deployment_unit_configuration_entry
                                    WHERE deployment_unit_id=? AND configuration_revision=? ORDER BY configuration_key, configuration_scope
                                    """)) {
                        entriesStatement.setString(1, applicationId);
                        entriesStatement.setLong(2, revision);
                        try (ResultSet entry = entriesStatement.executeQuery()) {
                            while (entry.next()) {
                                entries.add(new DeploymentConfigurationEntry(entry.getString("configuration_key"),
                                        DeploymentConfigurationScopeType
                                                .valueOf(entry.getString("configuration_scope")),
                                        value(entry.getString("value_type"), entry.getString("value_text"))));
                            }
                        }
                    }
                    return Optional.of(new DeploymentConfigurationSnapshot(applicationId, revision, schemaVersion,
                            createdAt, entries, sha256));
                } catch (IllegalArgumentException exception) {
                    throw new SQLException("saved application configuration snapshot violates current validation rules",
                            exception);
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
    static void saveAndBindRelease(Connection connection, DeploymentConfigurationSnapshot snapshot,
            String releaseIdentity) throws SQLException {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(snapshot, "snapshot");
        String application = boundedIdentity(snapshot.applicationId(), "applicationId");
        String release = boundedIdentity(releaseIdentity, "releaseIdentity");
        save(connection, snapshot);
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT configuration_revision, configuration_sha256
                FROM deployment_unit_release_binding
                WHERE deployment_unit_id=? AND release_sha256=? FOR UPDATE
                """)) {
            statement.setString(1, application);
            statement.setString(2, release);
            try (ResultSet result = statement.executeQuery()) {
                if (result.next() && result.getObject("configuration_revision") != null) {
                    if (result.getLong("configuration_revision") != snapshot.revision()
                            || !result.getString("configuration_sha256").equals(snapshot.sha256())) {
                        throw new SQLException("release configuration bindings are immutable");
                    }
                    return;
                }
            }
        }
        try (PreparedStatement update = connection.prepareStatement("""
                UPDATE deployment_unit_release_binding SET configuration_revision=?,configuration_sha256=?
                WHERE deployment_unit_id=? AND release_sha256=? AND configuration_revision IS NULL
                """)) {
            update.setLong(1, snapshot.revision());
            update.setString(2, snapshot.sha256());
            update.setString(3, application);
            update.setString(4, release);
            if (update.executeUpdate() == 1)
                return;
        }
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO deployment_unit_release_binding
                (deployment_unit_id,release_sha256,configuration_revision,configuration_sha256) VALUES(?,?,?,?)
                """)) {
            insert.setString(1, application);
            insert.setString(2, release);
            insert.setLong(3, snapshot.revision());
            insert.setString(4, snapshot.sha256());
            insert.executeUpdate();
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
    private static void save(Connection connection, DeploymentConfigurationSnapshot snapshot) throws SQLException {
        Optional<DeploymentConfigurationSnapshot> existing = find(connection, snapshot.applicationId(),
                snapshot.revision());
        if (existing.isPresent()) {
            DeploymentConfigurationSnapshot stored = existing.orElseThrow();
            // Stored milliseconds and canonical ordering define equality across reads. / 存储毫秒和规范排序定义跨读取相等性。
            if (!stored.sha256().equals(snapshot.sha256())
                    || stored.createdAt().toEpochMilli() != snapshot.createdAt().toEpochMilli()) {
                throw new SQLException("application configuration revisions are immutable");
            }
            return;
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO deployment_unit_configuration_snapshot (
                    deployment_unit_id, configuration_revision, schema_version, created_at, configuration_sha256
                ) VALUES (?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, snapshot.applicationId());
            statement.setLong(2, snapshot.revision());
            statement.setString(3, snapshot.schemaVersion());
            statement.setLong(4, snapshot.createdAt().toEpochMilli());
            statement.setString(5, snapshot.sha256());
            statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement(
                """
                        INSERT INTO deployment_unit_configuration_entry (
                            deployment_unit_id, configuration_revision, configuration_key, value_type, configuration_scope, value_text
                        ) VALUES (?, ?, ?, ?, ?, ?)
                        """)) {
            for (DeploymentConfigurationEntry entry : snapshot.entries()) {
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
    private static String valueType(DeploymentConfigurationValue value) {
        return switch (value) {
            case DeploymentConfigurationValue.Text ignored -> "TEXT";
            case DeploymentConfigurationValue.Number ignored -> "NUMBER";
            case DeploymentConfigurationValue.Flag ignored -> "FLAG";
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
    private static DeploymentConfigurationValue value(String type, String text) throws SQLException {
        try {
            return switch (type) {
                case "TEXT" -> new DeploymentConfigurationValue.Text(text);
                case "NUMBER" -> new DeploymentConfigurationValue.Number(Long.parseLong(text));
                case "FLAG" -> {
                    if (!"true".equals(text) && !"false".equals(text)) {
                        throw new IllegalArgumentException("flag configuration values must be true or false");
                    }
                    yield new DeploymentConfigurationValue.Flag(Boolean.parseBoolean(text));
                }
                default -> throw new IllegalArgumentException("unknown configuration value type");
            };
        } catch (IllegalArgumentException exception) {
            throw new SQLException("saved application configuration entry violates current validation rules",
                    exception);
        }
    }

    /** Saves the immutable snapshot and exact release binding in one owned transaction. / 在一个所属事务内保存不可变快照及精确发布绑定。
     * @param task original active author task / 原活动作者任务
     * @param snapshot original accepted configuration / 原已接受配置
     * @param releaseIdentity exact release digest / 精确发布摘要
     * @throws SQLException if a known read or write fails / 已知读取或写入失败时
     * @throws ApiException if access fails or commit cannot be established / 访问失败或无法确定提交时 */
    public void saveAndBindRelease(TaskContext task, DeploymentConfigurationSnapshot snapshot, String releaseIdentity)
            throws SQLException, ApiException {
        var connection = connection(task);
        DeploymentDataTransaction.execute(connection, () -> saveAndBindRelease(connection, snapshot, releaseIdentity));
    }

    /** Opens only the bound deployment generation through the original task. / 仅经原任务打开绑定的部署代次。
     * @param task original active author task / 原活动作者任务
     * @return managed owned connection / 受管所属连接
     * @throws SQLException if JDBC access fails / JDBC访问失败时
     * @throws ApiException if the capability is unavailable / 能力不可用时 */
    private Connection connection(TaskContext task) throws SQLException, ApiException {
        DeploymentSchema.requireIdentity(database.identity());
        return database.openConnection(Objects.requireNonNull(task, "task"));
    }

    /** Validates persisted typed rows and their original content digests without writes. / 不写入地核验已存类型化记录及其原内容摘要。
     * @param connection original schema validation reader / 原结构核验读取连接
     * @throws SQLException if any stored snapshot is unreadable or invalid / 任一存储快照不可读或无效时 */
    static void validate(Connection connection) throws SQLException {
        try (var statement = connection.createStatement();
                var rows = statement.executeQuery(
                        "SELECT deployment_unit_id,configuration_revision FROM deployment_unit_configuration_snapshot")) {
            while (rows.next())
                find(connection, rows.getString(1), rows.getLong(2))
                        .orElseThrow(() -> new SQLException("Configuration snapshot disappeared during validation"));
        }
    }
}
