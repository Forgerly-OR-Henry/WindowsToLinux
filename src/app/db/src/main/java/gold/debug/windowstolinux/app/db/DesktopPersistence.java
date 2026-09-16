package gold.debug.windowstolinux.app.db;

import gold.debug.windowstolinux.app.db.failure.DesktopPersistenceException;
import gold.debug.windowstolinux.app.db.failure.DesktopPersistenceFailureType;
import gold.debug.windowstolinux.app.db.persistence.connection.DesktopConnectionFactory;
import gold.debug.windowstolinux.app.db.execution.migration.DesktopSchemaMigrator;
import gold.debug.windowstolinux.app.db.persistence.repository.AiProfileRepository;
import gold.debug.windowstolinux.app.db.persistence.repository.ApplicationSecretRepository;
import gold.debug.windowstolinux.app.db.persistence.repository.ConfigurationSnapshotRepository;
import gold.debug.windowstolinux.app.db.persistence.repository.DesktopPreferenceRepository;
import gold.debug.windowstolinux.app.db.persistence.repository.EncryptedSecretRepository;
import gold.debug.windowstolinux.app.db.persistence.repository.ManagedApplicationRepository;
import gold.debug.windowstolinux.app.db.persistence.repository.ManagedApplicationGraphRepository;
import gold.debug.windowstolinux.app.db.persistence.repository.ServerProfileRepository;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;

/**
 * Composes focused desktop repositories over the versioned migrated SQLite schema.
 *
 * <p>在版本化迁移的 SQLite schema 上组合聚焦桌面仓库。
 */
public final class DesktopPersistence implements AutoCloseable {
    /** UI locale preference key. / UI 区域设置偏好键。 */
    public static final String UI_LOCALE_SETTING = "ui.locale";
    /** UI theme preference key. / UI 主题偏好键。 */
    public static final String UI_THEME_SETTING = "ui.theme";

    private final gold.debug.windowstolinux.app.db.persistence.repository.ExternalApplicationRepository externalApplications;
    private final ServerProfileRepository servers;
    private final DesktopPreferenceRepository preferences;
    private final AiProfileRepository aiProfiles;
    private final ConfigurationSnapshotRepository configurations;
    private final ApplicationSecretRepository applicationSecrets;
    private final EncryptedSecretRepository encryptedSecrets;
    private final ManagedApplicationRepository managedApplications;
    private final ManagedApplicationGraphRepository managedApplicationGraphs;

    private DesktopPersistence(DesktopConnectionFactory connections) {
        servers = new ServerProfileRepository(connections);
        externalApplications = new gold.debug.windowstolinux.app.db.persistence.repository.ExternalApplicationRepository(connections);
        preferences = new DesktopPreferenceRepository(connections);
        aiProfiles = new AiProfileRepository(connections);
        configurations = new ConfigurationSnapshotRepository(connections);
        applicationSecrets = new ApplicationSecretRepository(connections);
        encryptedSecrets = new EncryptedSecretRepository(connections);
        managedApplications = new ManagedApplicationRepository(connections);
        managedApplicationGraphs = new ManagedApplicationGraphRepository(connections);
    }

    /** Opens and migrates desktop persistence. / 打开并迁移桌面持久化。 */
    public static DesktopPersistence open(Path dataDirectory) throws DesktopPersistenceException {
        Path normalized;
        try {
            normalized = dataDirectory.toAbsolutePath().normalize();
            Files.createDirectories(normalized);
        } catch (Exception exception) {
            throw DesktopPersistenceException.create(DesktopPersistenceFailureType.DATA_DIRECTORY_UNAVAILABLE,
                    "The desktop persistence directory could not be created safely", exception);
        }
        DesktopConnectionFactory connections = new DesktopConnectionFactory(
                "jdbc:sqlite:" + normalized.resolve("windowstolinux.db"));
        try {
            verifyIntegrity(connections);
            DesktopSchemaMigrator.migrate(connections);
            verifyIntegrity(connections);
            return new DesktopPersistence(connections);
        } catch (SQLException exception) {
            throw map(exception);
        }
    }

    /** Returns the server/profile repository. / 返回服务器/资料仓库。 */
    public ServerProfileRepository servers() { return servers; }
    /** Returns external lifecycle registrations and local presentation. / 返回外部生命周期登记与本地显示设置。 */
    public gold.debug.windowstolinux.app.db.persistence.repository.ExternalApplicationRepository externalApplications() { return externalApplications; }
    /** Returns the preference repository. / 返回偏好仓库。 */
    public DesktopPreferenceRepository preferences() { return preferences; }
    /** Returns the AI profile repository. / 返回 AI 资料仓库。 */
    public AiProfileRepository aiProfiles() { return aiProfiles; }
    /** Returns the normal configuration repository. / 返回普通配置仓库。 */
    public ConfigurationSnapshotRepository configurations() { return configurations; }
    /** Returns the application-secret metadata repository. / 返回应用秘密元数据仓库。 */
    public ApplicationSecretRepository applicationSecrets() { return applicationSecrets; }
    /** Returns the encrypted payload repository. / 返回加密载荷仓库。 */
    public EncryptedSecretRepository encryptedSecrets() { return encryptedSecrets; }
    /** Returns the managed-application repository. / 返回受管应用仓库。 */
    public ManagedApplicationRepository managedApplications() { return managedApplications; }
    /** Returns the durable whole-application graph repository. / 返回持久整应用图仓库。 */
    public ManagedApplicationGraphRepository managedApplicationGraphs() { return managedApplicationGraphs; }

    /** Closes this resource. / 关闭此资源。 */
    @Override
    public void close() {
        // Repositories use short-lived connections and own no shared handle. / 仓库使用短连接，不持有共享句柄。
    }

    private static void verifyIntegrity(DesktopConnectionFactory connections) throws SQLException {
        try (Connection connection = connections.open();
             Statement statement = connection.createStatement();
             ResultSet results = statement.executeQuery("PRAGMA quick_check")) {
            boolean checked = false;
            while (results.next()) {
                checked = true;
                if (!"ok".equalsIgnoreCase(results.getString(1))) {
                    throw new SQLException("database quick_check reported corruption");
                }
            }
            if (!checked) {
                throw new SQLException("database quick_check returned no result");
            }
        }
    }

    static DesktopPersistenceException map(SQLException exception) {
        String message = String.valueOf(exception.getMessage()).toLowerCase(Locale.ROOT);
        DesktopPersistenceFailureType type;
        String diagnostic;
        if (message.contains("quick_check") || message.contains("malformed") || message.contains("corrupt")
                || message.contains("not a database")) {
            type = DesktopPersistenceFailureType.DATABASE_CORRUPTED;
            diagnostic = "SQLite integrity verification failed; automatic repair is disabled";
        } else if (message.contains("newer than")) {
            type = DesktopPersistenceFailureType.SCHEMA_NEWER;
            diagnostic = "The database schema is newer than this desktop client";
        } else if (message.contains("locked") || message.contains("busy")) {
            type = DesktopPersistenceFailureType.DATABASE_LOCKED;
            diagnostic = "SQLite remained locked after the configured five-second busy timeout";
        } else if (message.contains("disk") || message.contains("full") || message.contains("ioerr")) {
            type = DesktopPersistenceFailureType.DISK_UNAVAILABLE;
            diagnostic = "SQLite storage is unavailable or has insufficient capacity";
        } else if (exception.getSuppressed().length > 0) {
            type = DesktopPersistenceFailureType.ROLLBACK_FAILED;
            diagnostic = "A database transaction failed and its rollback could not be verified";
        } else {
            type = DesktopPersistenceFailureType.DATABASE_OPEN_FAILED;
            diagnostic = "Desktop SQLite initialization failed without automatic repair";
        }
        return DesktopPersistenceException.create(type, diagnostic, exception);
    }
}
