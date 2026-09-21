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
 *  <p>在版本化迁移的 SQLite schema 上组合聚焦桌面仓库。
 */
public final class DesktopPersistence implements AutoCloseable {
    /**
     * UI locale preference key. / UI 区域设置偏好键。
     */
    public static final String UI_LOCALE_SETTING = "ui.locale";
    /**
     * UI theme preference key. / UI 主题偏好键。
     */
    public static final String UI_THEME_SETTING = "ui.theme";

    /**
     * Bound gold debug windowstolinux app db persistence repository external application repository collaborator for external applications.
     * <p>处理外部应用集合的golddebugwindowstolinux应用db持久化仓库外部应用仓库协作对象。
     */
    private final gold.debug.windowstolinux.app.db.persistence.repository.ExternalApplicationRepository externalApplications;
    /**
     * Bound server profile repository collaborator for server-profile and authenticated-session service.
     * <p>处理服务器资料及已认证会话服务的服务器配置资料仓库协作对象。
     */
    private final ServerProfileRepository servers;
    /**
     * Bound gold debug windowstolinux app db persistence repository browser recovery repository collaborator for recovery.
     * <p>处理恢复的golddebugwindowstolinux应用db持久化仓库浏览器恢复仓库协作对象。
     */
    private final gold.debug.windowstolinux.app.db.persistence.repository.BrowserRecoveryRepository recovery;
    /**
     * Bound desktop preference repository collaborator for preferences.
     * <p>处理偏好的Desktop偏好仓库协作对象。
     */
    private final DesktopPreferenceRepository preferences;
    /**
     * Bound ai profile repository collaborator for ai profiles.
     * <p>处理AI配置资料集合的AI配置资料仓库协作对象。
     */
    private final AiProfileRepository aiProfiles;
    /** Nonsecret deployment task journal. / 非秘密部署任务日志。 */
    private final gold.debug.windowstolinux.app.db.persistence.repository.AgentTaskRepository agentTasks;
    /**
     * Bound configuration snapshot repository collaborator for configurations.
     * <p>处理配置集合的配置快照仓库协作对象。
     */
    private final ConfigurationSnapshotRepository configurations;
    /**
     * Bound application secret repository collaborator for application secrets.
     * <p>处理应用秘密集合的应用秘密仓库协作对象。
     */
    private final ApplicationSecretRepository applicationSecrets;
    /**
     * Bound encrypted secret repository collaborator for encrypted secrets.
     * <p>处理加密秘密集合的加密秘密仓库协作对象。
     */
    private final EncryptedSecretRepository encryptedSecrets;
    /**
     * Bound managed application repository collaborator for managed applications.
     * <p>处理受管应用集合的受管应用仓库协作对象。
     */
    private final ManagedApplicationRepository managedApplications;
    /**
     * Bound managed application graph repository collaborator for managed application graphs.
     * <p>处理受管应用图集合的受管应用图仓库协作对象。
     */
    private final ManagedApplicationGraphRepository managedApplicationGraphs;

    /**
     * Binds the supplied dependencies and state for desktop persistence.
     * <p>为Desktop持久化绑定传入的依赖及状态。
     *
     * @param connections factory for scoped database connections / 限定作用域数据库连接的工厂
     */
    private DesktopPersistence(DesktopConnectionFactory connections) {
        servers = new ServerProfileRepository(connections);
        agentTasks = new gold.debug.windowstolinux.app.db.persistence.repository.AgentTaskRepository(connections);
        recovery = new gold.debug.windowstolinux.app.db.persistence.repository.BrowserRecoveryRepository(connections);
        externalApplications = new gold.debug.windowstolinux.app.db.persistence.repository.ExternalApplicationRepository(connections);
        preferences = new DesktopPreferenceRepository(connections);
        aiProfiles = new AiProfileRepository(connections);
        configurations = new ConfigurationSnapshotRepository(connections);
        applicationSecrets = new ApplicationSecretRepository(connections);
        encryptedSecrets = new EncryptedSecretRepository(connections);
        managedApplications = new ManagedApplicationRepository(connections);
        managedApplicationGraphs = new ManagedApplicationGraphRepository(connections);
    }

    /**
     * Opens and migrates desktop persistence. / 打开并迁移桌面持久化。
     *
     * @param dataDirectory data directory / 数据目录
     * @return constructed or resolved desktop persistence / 构造或解析得到的Desktop持久化
     * @throws DesktopPersistenceException if the desktop persistence boundary rejects the operation / Desktop持久化边界拒绝当前操作时
     */
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
            new gold.debug.windowstolinux.app.db.persistence.repository.BrowserRecoveryRepository(connections).interruptUnfinished();
            new gold.debug.windowstolinux.app.db.persistence.repository.AgentTaskRepository(connections).interruptUnfinished();
            verifyIntegrity(connections);
            return new DesktopPersistence(connections);
        } catch (SQLException exception) {
            throw map(exception);
        }
    }

    /**
     * Returns the server/profile repository. / 返回服务器/资料仓库。
     *
     * @return the server/profile repository / 服务器/资料仓库
     */
    public ServerProfileRepository servers() { return servers; }
    /**
     * Nonsecret rescue journal. / 非秘密救援记录。
     *
     * @return recovery / 恢复
     */
    public gold.debug.windowstolinux.app.db.persistence.repository.BrowserRecoveryRepository recovery() { return recovery; }
    /** Provides the nonsecret task journal. / 提供非秘密任务日志。
     * @return deployment task repository / 部署任务仓库
     */
    public gold.debug.windowstolinux.app.db.persistence.repository.AgentTaskRepository agentTasks(){return agentTasks;}

    /**
     * Returns external lifecycle registrations and local presentation. / 返回外部生命周期登记与本地显示设置。
     *
     * @return external lifecycle registrations and local presentation / 外部生命周期登记与本地显示设置
     */
    public gold.debug.windowstolinux.app.db.persistence.repository.ExternalApplicationRepository externalApplications() { return externalApplications; }
    /**
     * Returns the preference repository. / 返回偏好仓库。
     *
     * @return the preference repository / 偏好仓库
     */
    public DesktopPreferenceRepository preferences() { return preferences; }
    /**
     * Returns the AI profile repository. / 返回 AI 资料仓库。
     *
     * @return the AI profile repository /  AI 资料仓库
     */
    public AiProfileRepository aiProfiles() { return aiProfiles; }
    /**
     * Returns the normal configuration repository. / 返回普通配置仓库。
     *
     * @return the normal configuration repository / 普通配置仓库
     */
    public ConfigurationSnapshotRepository configurations() { return configurations; }
    /**
     * Returns the application-secret metadata repository. / 返回应用秘密元数据仓库。
     *
     * @return the application-secret metadata repository / 应用秘密元数据仓库
     */
    public ApplicationSecretRepository applicationSecrets() { return applicationSecrets; }
    /**
     * Returns the encrypted payload repository. / 返回加密载荷仓库。
     *
     * @return the encrypted payload repository / 加密载荷仓库
     */
    public EncryptedSecretRepository encryptedSecrets() { return encryptedSecrets; }
    /**
     * Returns the managed-application repository. / 返回受管应用仓库。
     *
     * @return the managed-application repository / 受管应用仓库
     */
    public ManagedApplicationRepository managedApplications() { return managedApplications; }
    /**
     * Returns the durable whole-application graph repository. / 返回持久整应用图仓库。
     *
     * @return the durable whole-application graph repository / 持久整应用图仓库
     */
    public ManagedApplicationGraphRepository managedApplicationGraphs() { return managedApplicationGraphs; }

    /**
     * Closes this resource. / 关闭此资源。
     */
    @Override
    public void close() {
        // Repositories use short-lived connections and own no shared handle. / 仓库使用短连接，不持有共享句柄。
    }

    /**
     * Verifies integrity.
     * <p>验证完整性。
     *
     * @param connections factory for scoped database connections / 限定作用域数据库连接的工厂
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     */
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

    /**
     * Classifies persistence failures into safe database-owned descriptors while retaining the original cause.
     * <p>将持久化失败分类为安全的数据库自有描述，并保留原始原因。
     *
     * @param exception original exception being classified or translated / 正在分类或转换的原始异常
     * @return constructed or resolved desktop persistence exception / 构造或解析得到的Desktop持久化异常
     */
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
