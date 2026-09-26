package gold.debug.windowstolinux.app.main.startup;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Objects;

import javax.swing.SwingUtilities;

import gold.debug.windowstolinux.app.db.PlatformDatabase;
import gold.debug.windowstolinux.app.db.PlatformPersistence;
import gold.debug.windowstolinux.app.db.bootstrap.DatabaseProtection;
import gold.debug.windowstolinux.app.db.task.TaskLedger;
import gold.debug.windowstolinux.app.service.linux.PlatformLinuxService;
import gold.debug.windowstolinux.app.service.security.DesktopSecurityService;
import gold.debug.windowstolinux.app.service.task.PlatformInteractionService;
import gold.debug.windowstolinux.app.service.task.PlatformTaskAdmission;
import gold.debug.windowstolinux.app.service.task.PlatformTaskRuntime;

/** Owns platform storage, task execution and security for one desktop boot. / 为一次桌面启动持有平台存储、任务执行及安全会话。 */
public final class PlatformRuntime implements AutoCloseable {
    /** Protected platform storage retained until actual shutdown. / 实际关闭前保留的受保护平台存储。 */
    private final PlatformPersistence persistence;

    /** Master-password session and accepted credential operations. / 主密码会话及已接纳的凭据操作。 */
    private final DesktopSecurityService security;

    /** Central task owner including retained work from previous boots. / 包含前次启动保留工作的中央任务所有者。 */
    private final PlatformTaskRuntime tasks;

    /** Actual Maven artifact version, also used by host-owned task recovery. / 实际 Maven 产物版本，同时用于宿主任务恢复。 */
    private final String version;

    /** Actual desktop presenter, installed before author capabilities are constructed. / 实际桌面呈现器，在构造作者能力之前安装。 */
    private PlatformInteractionService.Prompts prompts;

    /** Permanent server settings and authentication-check coordinator. / 常驻服务器设置及认证检查协调器。 */
    private gold.debug.windowstolinux.app.service.server.PlatformServerService servers;

    /** Caller-bound remote capabilities owned through their actual cleanup. / 持有至实际清理完成的调用方远端能力。 */
    private final java.util.Set<RemoteCapabilities> remoteCapabilities = new java.util.LinkedHashSet<>();

    /** Whether executor teardown has started and admission must remain sealed. / 执行器拆除是否已开始且准入必须保持封闭。 */
    private boolean stopping;

    /** Whether all owned resources have actually closed. / 所有所属资源是否已实际关闭。 */
    private boolean closed;

    /**
     * Binds initialized platform resources. / 绑定已初始化的平台资源。
     * @param persistence protected platform repositories / 受保护的平台仓库
     * @param security current boot security owner / 当前启动的安全所有者
     * @throws SQLException if retained tasks cannot be loaded / 无法加载保留任务时
     * @throws java.io.IOException if build identity is missing or unfiltered / 构建身份缺失或未展开时
     */
    private PlatformRuntime(PlatformPersistence persistence, DesktopSecurityService security)
            throws SQLException, java.io.IOException {
        this.persistence = Objects.requireNonNull(persistence);
        this.security = Objects.requireNonNull(security);
        version = readVersion();
        tasks = new PlatformTaskRuntime(persistence.database(),
                new PlatformTaskAdmission(persistence.database(), version), security);
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
        DesktopSecurityService security = null;
        try {
            security = new DesktopSecurityService(persistence.security());
            return new PlatformRuntime(persistence, security);
        } catch (Exception | Error failure) {
            if (security != null) {
                try {
                    security.close();
                } catch (Exception cleanup) {
                    failure.addSuppressed(cleanup);
                }
            }
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

    /** Returns the central task executor for trusted host composition. / 返回供可信宿主装配使用的中央任务执行器。
     * @return current boot task owner / 当前启动的任务所有者 */
    public PlatformTaskRuntime tasks() {
        return tasks;
    }

    /** Returns the actual application artifact identity. / 返回实际应用产物身份。
     * @return current Maven version / 当前 Maven 版本 */
    public String version() {
        return version;
    }

    /**
     * Reads build-generated identity independently of working directory or JAR manifest layout. / 独立于工作目录及 JAR 清单布局读取构建生成身份。
     * @return actual non-secret artifact version / 实际不含秘密的产物版本
     * @throws java.io.IOException if metadata is absent or not generated / 元数据缺失或未生成时
     */
    private static String readVersion() throws java.io.IOException {
        var properties = new java.util.Properties();
        try (var input = PlatformRuntime.class
                .getResourceAsStream("/gold/debug/windowstolinux/app/main/build.properties")) {
            if (input == null)
                throw new java.io.IOException("Application build identity is missing");
            properties.load(input);
        }
        String value = properties.getProperty("application.version", "");
        if (value.isBlank() || value.length() > 512 || value.contains("${"))
            throw new java.io.IOException("Application build identity is invalid");
        return value;
    }

    /**
     * Installs the actual window-owned human presenter exactly once. / 仅安装一次实际窗口所属人工呈现器。
     * @param prompts platform-owned human presentation / 平台所属人工呈现
     */
    public synchronized void setInteractionPresenter(PlatformInteractionService.Prompts prompts) {
        if (this.prompts != null || stopping || closed)
            throw new IllegalStateException("Platform interaction presenter is already installed or closing");
        this.prompts = Objects.requireNonNull(prompts);
    }

    /**
     * Creates the permanent platform server capability after human presentation is installed. / 安装人工呈现后创建常驻平台服务器能力。
     * @return current platform settings and check coordinator / 当前平台设置及检查协调器
     * @throws gold.debug.windowstolinux.appapi.task.ApiException if recovery ownership cannot be registered / 无法登记恢复归属时
     */
    public synchronized gold.debug.windowstolinux.app.service.server.PlatformServerService servers()
            throws gold.debug.windowstolinux.appapi.task.ApiException {
        if (closed || stopping || prompts == null)
            throw new IllegalStateException("Platform server settings are unavailable");
        if (servers == null) {
            var remote = remoteCapabilities(PlatformTaskAdmission.PLATFORM_OWNER, version);
            try {
                servers = new gold.debug.windowstolinux.app.service.server.PlatformServerService(persistence, security,
                        tasks, version, remote.linux(), remote::closeTaskResources);
            } catch (gold.debug.windowstolinux.appapi.task.ApiException | RuntimeException failure) {
                try {
                    remote.close();
                } catch (Exception cleanup) {
                    failure.addSuppressed(cleanup);
                }
                throw failure;
            }
        }
        return servers;
    }

    /**
     * Creates real task, credential, review and SSH adapters for one Mod activation. / 为一次 Mod 启用创建真实任务、凭据、审核及 SSH 适配。
     * @param ownerId exact activating Mod / 精确正在启用的 Mod
     * @param version exact loaded release / 精确已加载版本
     * @return host-owned capability pair / 宿主持有的能力组合
     */
    public synchronized RemoteCapabilities remoteCapabilities(String ownerId, String version) {
        if (closed || stopping || prompts == null)
            throw new IllegalStateException("Platform remote capabilities are unavailable");
        var interaction = new PlatformInteractionService(tasks, security, ownerId, version, prompts);
        var linux = new PlatformLinuxService(tasks, security, persistence.servers(), interaction, ownerId, version,
                java.time.Duration.ofSeconds(30), java.time.Duration.ofMinutes(5));
        var capabilities = new RemoteCapabilities(interaction, linux);
        remoteCapabilities.add(capabilities);
        return capabilities;
    }

    /** Remote capabilities whose concrete implementations never cross into author code. / 具体实现不越界交给作者代码的远端能力。 */
    public final class RemoteCapabilities implements AutoCloseable {
        /** Same activation's human review authority. / 同次启用的人工审核授权方。 */
        private final PlatformInteractionService interaction;

        /** Same activation's real transport owner. / 同次启用的真实传输所有者。 */
        private final PlatformLinuxService linux;

        /**
         * Takes ownership of the actual platform adapters. / 接管实际平台适配器。
         * @param interaction review adapter / 审核适配器
         * @param linux transport adapter / 传输适配器
         */
        private RemoteCapabilities(PlatformInteractionService interaction, PlatformLinuxService linux) {
            this.interaction = interaction;
            this.linux = linux;
        }

        /** Returns the public author review contract. / 返回公开作者审核契约。
         * @return bound review service / 绑定的审核服务 */
        public gold.debug.windowstolinux.appapi.task.InteractionService interaction() {
            return interaction;
        }

        /** Returns the public author transport contract. / 返回公开作者传输契约。
         * @return bound Linux service / 绑定的 Linux 服务 */
        public gold.debug.windowstolinux.appapi.linux.LinuxService linux() {
            return linux;
        }

        /**
         * Retries retained physical resources for explicit host reconciliation. / 为显式宿主核对重试保留物理资源。
         * @param task original retained task / 原始保留任务
         * @throws gold.debug.windowstolinux.appapi.task.ApiException if release remains incomplete / 释放仍未完成时
         */
        public void closeTaskResources(gold.debug.windowstolinux.appapi.task.TaskContext task)
                throws gold.debug.windowstolinux.appapi.task.ApiException {
            linux.closeTaskResources(task);
            interaction.retryFinishedReleases();
        }

        /**
         * Closes actual clients before revoking their review capability. / 在撤销审核能力之前关闭实际客户端。
         * @throws gold.debug.windowstolinux.appapi.task.ApiException if actual cleanup remains / 实际清理仍未完成时
         */
        @Override
        public void close() throws gold.debug.windowstolinux.appapi.task.ApiException {
            linux.close();
            interaction.close();
            synchronized (PlatformRuntime.this) {
                remoteCapabilities.remove(this);
            }
        }
    }

    /**
     * Records a successfully constructed desktop in the current boot. / 在当前启动中记录已成功构造的桌面。
     * @throws SQLException if this boot no longer owns the startup marker / 当前启动不再持有启动标记时
     */
    public synchronized void started() throws SQLException {
        requireWorker();
        if (closed || stopping)
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
        boolean wasSealed = tasks.seal(true);
        if (!stopping) {
            try {
                if (!new TaskLedger(persistence.database()).unfinished().isEmpty())
                    throw new SQLException("Unfinished platform tasks require reconciliation before shutdown");
                if (persistence.database().openConnections() != 0)
                    throw new SQLException("Platform connections must close before shutdown");
                var state = security.securityState();
                if (state.activeTasks() != 0 || state.changing())
                    throw new IllegalStateException(
                            "Credential operations and security changes must finish before shutdown");
            } catch (Exception | Error failure) {
                tasks.seal(wasSealed);
                throw failure;
            }
            stopping = true;
        }
        if (servers != null)
            servers.close();
        for (var capabilities : java.util.List.copyOf(remoteCapabilities))
            capabilities.close();
        tasks.close();
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
