package gold.debug.windowstolinux.app.db.repository;

import gold.debug.windowstolinux.app.db.connection.DesktopConnectionFactory;
import gold.debug.windowstolinux.shared.config.definition.ConfigurationScope;
import gold.debug.windowstolinux.shared.config.definition.ConfigurationValue;
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

/** Stores immutable ordinary-configuration snapshots and entries. / 保存不可变的普通配置快照与条目。 */
public final class ConfigurationSnapshotRepository {
    private final DesktopConnectionFactory connections;

    /** Creates the repository. / 创建仓库。 */
    public ConfigurationSnapshotRepository(DesktopConnectionFactory connections) {
        this.connections = Objects.requireNonNull(connections, "connections");
    }

    /** Saves an immutable configuration revision. / 保存不可变配置修订。 */
    public void save(ConfigurationSnapshot snapshot) throws SQLException {
        Objects.requireNonNull(snapshot, "snapshot");
        try (Connection connection = connections.open()) {
            RepositoryTransactionExecutor.execute(connection, () -> {
                Optional<ConfigurationSnapshot> existing = find(connection, snapshot.applicationId(), snapshot.revision());
                if (existing.isPresent()) {
                    ConfigurationSnapshot stored = existing.orElseThrow();
                    if (!stored.schemaVersion().equals(snapshot.schemaVersion())
                            || !stored.createdAt().equals(snapshot.createdAt()) || !stored.sha256().equals(snapshot.sha256())) {
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
            });
        }
    }

    /** Finds an immutable configuration revision. / 查找不可变配置修订。 */
    public Optional<ConfigurationSnapshot> find(String applicationId, long revision) throws SQLException {
        try (Connection connection = connections.open()) {
            return find(connection, applicationId, revision);
        }
    }

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

    private static String valueType(ConfigurationValue value) {
        return switch (value) {
            case ConfigurationValue.Text ignored -> "TEXT";
            case ConfigurationValue.Number ignored -> "NUMBER";
            case ConfigurationValue.Flag ignored -> "FLAG";
        };
    }

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
