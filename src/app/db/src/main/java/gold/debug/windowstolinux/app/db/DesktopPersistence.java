package gold.debug.windowstolinux.app.db;

import gold.debug.windowstolinux.app.db.connection.DesktopConnectionFactory;
import gold.debug.windowstolinux.app.db.migration.DesktopSchemaMigrator;
import gold.debug.windowstolinux.app.db.repository.AiProfileRepository;
import gold.debug.windowstolinux.app.db.repository.ApplicationSecretRepository;
import gold.debug.windowstolinux.app.db.repository.ConfigurationSnapshotRepository;
import gold.debug.windowstolinux.app.db.repository.DesktopPreferenceRepository;
import gold.debug.windowstolinux.app.db.repository.EncryptedSecretRepository;
import gold.debug.windowstolinux.app.db.repository.ManagedApplicationRepository;
import gold.debug.windowstolinux.app.db.repository.ManagedApplicationGraphRepository;
import gold.debug.windowstolinux.app.db.repository.ServerProfileRepository;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;

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
        preferences = new DesktopPreferenceRepository(connections);
        aiProfiles = new AiProfileRepository(connections);
        configurations = new ConfigurationSnapshotRepository(connections);
        applicationSecrets = new ApplicationSecretRepository(connections);
        encryptedSecrets = new EncryptedSecretRepository(connections);
        managedApplications = new ManagedApplicationRepository(connections);
        managedApplicationGraphs = new ManagedApplicationGraphRepository(connections);
    }

    /** Opens and migrates desktop persistence. / 打开并迁移桌面持久化。 */
    public static DesktopPersistence open(Path dataDirectory) throws IOException, SQLException {
        Path normalized = dataDirectory.toAbsolutePath().normalize();
        Files.createDirectories(normalized);
        DesktopConnectionFactory connections = new DesktopConnectionFactory(
                "jdbc:sqlite:" + normalized.resolve("windowstolinux.db"));
        DesktopSchemaMigrator.migrate(connections);
        return new DesktopPersistence(connections);
    }

    /** Returns the server/profile repository. / 返回服务器/资料仓库。 */
    public ServerProfileRepository servers() { return servers; }
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

    @Override
    public void close() {
        // Repositories use short-lived connections and own no shared handle. / 仓库使用短连接，不持有共享句柄。
    }
}
