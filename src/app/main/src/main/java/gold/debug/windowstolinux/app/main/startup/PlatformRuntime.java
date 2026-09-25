package gold.debug.windowstolinux.app.main.startup;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Objects;

import javax.swing.SwingUtilities;

import gold.debug.windowstolinux.app.db.PlatformDatabase;
import gold.debug.windowstolinux.app.db.PlatformPersistence;
import gold.debug.windowstolinux.app.db.bootstrap.DatabaseProtection;
import gold.debug.windowstolinux.app.db.task.TaskLedger;
import gold.debug.windowstolinux.app.service.security.DesktopSecurityService;

/** Owns the platform database and security session for one desktop boot. / 为一次桌面启动持有平台数据库及安全会话。 */
public final class PlatformRuntime implements AutoCloseable {
    /** Protected platform storage retained until actual shutdown. / 实际关闭前保留的受保护平台存储。 */
    private final PlatformPersistence persistence;

    /** Master-password session and accepted credential operations. / 主密码会话及已接纳的凭据操作。 */
    private final DesktopSecurityService security;

    /** Whether all owned resources have actually closed. / 所有所属资源是否已实际关闭。 */
    private boolean closed;

    /**
     * Binds initialized platform resources. / 绑定已初始化的平台资源。
     * @param persistence protected platform repositories / 受保护的平台仓库
     * @param security current boot security owner / 当前启动的安全所有者
     */
    private PlatformRuntime(PlatformPersistence persistence, DesktopSecurityService security) {
        this.persistence = Objects.requireNonNull(persistence);
        this.security = Objects.requireNonNull(security);
    }

    /**
     * Opens H2 without opening or modifying an older SQLite database. / 打开 H2，不打开或修改旧 SQLite 数据库。
     * @param dataDirectory fixed run-mode data directory / 固定运行模式数据目录
     * @param protection current-user bootstrap protection / 当前用户引导保护
     * @return resources owned by this boot / 当前启动持有的资源
     * @throws Exception if initialization or cleanup fails / 初始化或清理失败时
     */
    public static PlatformRuntime open(Path dataDirectory, DatabaseProtection protection) throws Exception {
        requireWorker();
        var persistence = new PlatformPersistence(PlatformDatabase.open(dataDirectory, protection));
        try {
            return new PlatformRuntime(persistence, new DesktopSecurityService(persistence.security()));
        } catch (RuntimeException | Error failure) {
            try {
                persistence.close();
            } catch (Exception cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    /** Returns the platform repositories for startup composition. / 返回供启动装配使用的平台仓库。
     * @return owned platform repositories / 所属平台仓库 */
    public PlatformPersistence persistence() {
        return persistence;
    }

    /** Returns the current security boundary without key material. / 返回不含密钥材料的当前安全边界。
     * @return security session owner / 安全会话所有者 */
    public DesktopSecurityService security() {
        return security;
    }

    /**
     * Records a successfully constructed desktop in the current boot. / 在当前启动中记录已成功构造的桌面。
     * @throws SQLException if this boot no longer owns the startup marker / 当前启动不再持有启动标记时
     */
    public synchronized void started() throws SQLException {
        requireWorker();
        if (closed)
            throw new SQLException("Platform runtime is closed");
        try (var connection = persistence.database().openConnection();
                var statement = connection.prepareStatement(
                        "UPDATE app_state SET shutdown_state='RUNNING',updated_at=? WHERE singleton_id=1 AND boot_id=? AND shutdown_state IN ('STARTING','RUNNING')")) {
            statement.setLong(1, System.currentTimeMillis());
            statement.setObject(2, persistence.database().bootId());
            if (statement.executeUpdate() != 1)
                throw new SQLException("Platform boot marker changed");
        }
    }

    /**
     * Closes only after persistent tasks and actual connections are released. / 仅在持久化任务及实际连接释放后关闭。
     * @throws Exception if tasks, credentials or physical connections remain / 仍有任务、凭据操作或物理连接时
     */
    @Override
    public synchronized void close() throws Exception {
        requireWorker();
        if (closed)
            return;
        if (!new TaskLedger(persistence.database()).unfinished().isEmpty())
            throw new SQLException("Unfinished platform tasks require reconciliation before shutdown");
        if (persistence.database().openConnections() != 0)
            throw new SQLException("Platform connections must close before shutdown");
        security.close();
        persistence.close();
        closed = true;
    }

    /** Rejects blocking startup and shutdown on the Swing event thread. / 拒绝在 Swing 事件线程执行阻塞启动和关闭。 */
    private static void requireWorker() {
        if (SwingUtilities.isEventDispatchThread())
            throw new IllegalStateException("Platform lifecycle requires a worker thread");
    }
}
