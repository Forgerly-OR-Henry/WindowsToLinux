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
import gold.debug.windowstolinux.app.service.task.PlatformAuthorizationService;
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

    /** Independent platform permission preference and current workflow scopes. / 独立平台权限偏好及当前流程作用域。 */
    private final PlatformAuthorizationService authorization;

    /** Shared actual diagnostic storage and emergency reconciliation. / 共用实际诊断存储及应急核对。 */
    private final gold.debug.windowstolinux.app.service.log.PlatformLogService logs;

    /** Shared permanent connection and transfer preferences. / 共用常驻连接及传输偏好。 */
    private final gold.debug.windowstolinux.app.service.linux.TransferSettingsService transfers;

    /** Actual Maven artifact version, also used by host-owned task recovery. / 实际 Maven 产物版本，同时用于宿主任务恢复。 */
    private final String version;

    /** Actual desktop presenter, installed before author capabilities are constructed. / 实际桌面呈现器，在构造作者能力之前安装。 */
    private PlatformInteractionService.Prompts prompts;

    /** Actual window-owned source chooser. / 实际窗口所属来源选择器。 */
    private gold.debug.windowstolinux.app.service.source.PlatformSourceAccess.DirectoryPicker sourcePicker;

    /** Permanent server settings and authentication-check coordinator. / 常驻服务器设置及认证检查协调器。 */
    private gold.debug.windowstolinux.app.service.server.PlatformServerService servers;

    /** Permanent model settings and capability probes. / 常驻模型设置及能力探测。 */
    private gold.debug.windowstolinux.app.service.ai.PlatformModelService models;

    /** Caller-bound remote capabilities owned through their actual cleanup. / 持有至实际清理完成的调用方远端能力。 */
    private final java.util.Set<RemoteCapabilities> remoteCapabilities = new java.util.LinkedHashSet<>();

    /** Bound public server capabilities retained through owner teardown. / 保留至所有者拆除的已绑定公开服务器能力。 */
    private final java.util.Set<gold.debug.windowstolinux.app.service.server.PlatformServerAccess> serverAccesses = new java.util.LinkedHashSet<>();

    /** Bound secret capabilities retained until their actual operations end. / 保留至实际操作结束的已绑定秘密能力。 */
    private final java.util.Set<gold.debug.windowstolinux.app.service.security.PlatformSecretService> secretServices = new java.util.LinkedHashSet<>();

    /** Bound model capabilities retaining their actual HTTP clients. / 保留实际 HTTP 客户端的已绑定模型能力。 */
    private final java.util.Set<gold.debug.windowstolinux.app.service.ai.PlatformAiService> aiServices = new java.util.LinkedHashSet<>();

    /** Bound source capabilities retaining actual snapshot files. / 保留实际快照文件的已绑定来源能力。 */
    private final java.util.Set<gold.debug.windowstolinux.app.service.source.PlatformSourceAccess> sourceServices = new java.util.LinkedHashSet<>();

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
        transfers = new gold.debug.windowstolinux.app.service.linux.TransferSettingsService(persistence, security);
        version = readVersion();
        logs = new gold.debug.windowstolinux.app.service.log.PlatformLogService(persistence.database(), security);
        try {
            logs.replayAll();
        } catch (Exception failure) {
            throw new SQLException("Platform diagnostic recovery could not enumerate registered categories", failure);
        }
        tasks = new PlatformTaskRuntime(persistence.database(),
                new PlatformTaskAdmission(persistence.database(), version), security);
        authorization = new PlatformAuthorizationService(persistence, security, tasks);
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

    /** Returns platform permission settings for trusted host controls. / 返回供可信宿主控件使用的平台权限设置。
     * @return current workflow permission owner / 当前流程权限所有者 */
    public synchronized PlatformAuthorizationService authorization() {
        if (stopping || closed)
            throw new IllegalStateException("Platform authorization is closing");
        return authorization;
    }

    /** Returns host diagnostic management without exposing it to author contexts. / 返回宿主诊断管理，不将其暴露给作者上下文。
     * @return shared log owner / 共用日志所有者 */
    public synchronized gold.debug.windowstolinux.app.service.log.PlatformLogService logs() {
        if (stopping || closed)
            throw new IllegalStateException("Platform logs are closing");
        return logs;
    }

    /** Creates an exact owner-bound public logger for one activation. / 为一次启用创建精确所有者绑定公开日志器。
     * @param owner actual Mod identity / 实际 Mod 身份
     * @param version loaded release / 加载发布版本
     * @param schema optional business generation / 可选业务代次
     * @return original activation's logger / 原启用日志器 */
    public synchronized gold.debug.windowstolinux.app.service.log.PlatformLogService.Binding logs(String owner,
            String version, java.util.Optional<gold.debug.windowstolinux.appapi.db.SchemaIdentity> schema) {
        return logs().bind(owner, version, schema, tasks);
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

    /** Returns the permanent transfer preference owner. / 返回常驻传输偏好所有者。
     * @return shared settings service / 共用设置服务 */
    public synchronized gold.debug.windowstolinux.app.service.linux.TransferSettingsService transfers() {
        if (stopping || closed)
            throw new IllegalStateException("Platform transfer settings are closing");
        return transfers;
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

    /** Installs the actual source chooser before constructing author capabilities. / 构造作者能力前安装实际来源选择器。
     * @param picker window-owned directory picker / 窗口所属目录选择器 */
    public synchronized void setSourcePicker(
            gold.debug.windowstolinux.app.service.source.PlatformSourceAccess.DirectoryPicker picker) {
        if (sourcePicker != null || stopping || closed)
            throw new IllegalStateException("Platform source picker is already installed or closing");
        sourcePicker = Objects.requireNonNull(picker);
    }

    /** Creates task-bound local source access for one Mod activation. / 为一次 Mod 启用创建任务绑定本地来源访问。
     * @param owner exact Mod identity / 精确 Mod 身份
     * @param version actual loaded release / 实际已加载版本
     * @return host-owned source capability / 宿主持有的来源能力 */
    public synchronized gold.debug.windowstolinux.app.service.source.PlatformSourceAccess source(String owner,
            String version) {
        if (closed || stopping || sourcePicker == null)
            throw new IllegalStateException("Platform source capabilities are unavailable");
        var source = new gold.debug.windowstolinux.app.service.source.PlatformSourceAccess(persistence.database(),
                tasks, security, owner, version, sourcePicker);
        sourceServices.add(source);
        return source;
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
     * Creates an activation-bound public server reference capability. / 创建绑定一次启用的公开服务器引用能力。
     * @param owner exact Mod identity / 精确 Mod 身份
     * @param version actual loaded release / 实际已加载版本
     * @param schema selected business generation, if present / 选定业务代次，可不存在
     * @return host-owned server adapter / 宿主持有的服务器适配器
     */
    public synchronized gold.debug.windowstolinux.app.service.server.PlatformServerAccess serverAccess(String owner,
            String version, java.util.Optional<gold.debug.windowstolinux.appapi.db.SchemaIdentity> schema) {
        if (closed || stopping)
            throw new IllegalStateException("Platform server capabilities are closing");
        var access = new gold.debug.windowstolinux.app.service.server.PlatformServerAccess(persistence.database(),
                tasks, persistence.serverReferences(), owner, version, schema);
        serverAccesses.add(access);
        return access;
    }

    /** Creates the permanent model configuration service with real task-bound probes. / 创建具有真实任务探测的常驻模型配置服务。
     * @return host model settings / 宿主模型设置
     * @throws gold.debug.windowstolinux.appapi.task.ApiException if recovery registration fails / 恢复登记失败时 */
    public synchronized gold.debug.windowstolinux.app.service.ai.PlatformModelService models()
            throws gold.debug.windowstolinux.appapi.task.ApiException {
        requireWorker();
        if (closed || stopping)
            throw new IllegalStateException("Platform model settings are closing");
        if (models == null)
            models = new gold.debug.windowstolinux.app.service.ai.PlatformModelService(persistence, security, tasks,
                    version, ai(PlatformTaskAdmission.PLATFORM_OWNER, version));
        return models;
    }

    /**
     * Creates protected-byte and reference access for one Mod activation. / 为一次 Mod 启用创建受保护字节及引用访问。
     * @param owner exact Mod identity / 精确 Mod 身份
     * @param version actual loaded release / 实际已加载版本
     * @param schema selected optional business generation / 选定的可选业务代次
     * @return host-owned secret adapter / 宿主持有的秘密适配器
     */
    public synchronized gold.debug.windowstolinux.app.service.security.PlatformSecretService secrets(String owner,
            String version, java.util.Optional<gold.debug.windowstolinux.appapi.db.SchemaIdentity> schema) {
        if (closed || stopping)
            throw new IllegalStateException("Platform secret capabilities are closing");
        var secrets = new gold.debug.windowstolinux.app.service.security.PlatformSecretService(persistence.database(),
                tasks, security, persistence.credentialReferences(), owner, version, schema);
        secretServices.add(secrets);
        return secrets;
    }

    /**
     * Creates task-owned generic model access for one Mod activation. / 为一次 Mod 启用创建任务所属通用模型访问。
     * @param owner exact Mod identity / 精确 Mod 身份
     * @param version actual loaded release / 实际已加载版本
     * @return host-owned model capability / 宿主持有的模型能力
     */
    public synchronized gold.debug.windowstolinux.app.service.ai.PlatformAiService ai(String owner, String version) {
        if (closed || stopping)
            throw new IllegalStateException("Platform model capabilities are closing");
        var ai = new gold.debug.windowstolinux.app.service.ai.PlatformAiService(persistence.database(), tasks, security,
                persistence.aiProfiles(), owner, version);
        aiServices.add(ai);
        return ai;
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
        var interaction = new PlatformInteractionService(tasks, security, authorization, ownerId, version, prompts);
        var linux = new PlatformLinuxService(tasks, security, persistence.servers(), interaction, ownerId, version,
                transfers);
        var capabilities = new RemoteCapabilities(interaction, linux, authorization.launcher(ownerId, version));
        remoteCapabilities.add(capabilities);
        return capabilities;
    }

    /** Remote capabilities whose concrete implementations never cross into author code. / 具体实现不越界交给作者代码的远端能力。 */
    public final class RemoteCapabilities implements AutoCloseable {
        /** Same activation's human review authority. / 同次启用的人工审核授权方。 */
        private final PlatformInteractionService interaction;

        /** Same activation's real transport owner. / 同次启用的真实传输所有者。 */
        private final PlatformLinuxService linux;

        /** Same activation's host-owned workflow start authority. / 同次启用的宿主所属流程启动授权。 */
        private final PlatformAuthorizationService.Launcher launcher;

        /**
         * Takes ownership of the actual platform adapters. / 接管实际平台适配器。
         * @param interaction review adapter / 审核适配器
         * @param linux transport adapter / 传输适配器
         * @param launcher host start authority / 宿主启动授权
         */
        private RemoteCapabilities(PlatformInteractionService interaction, PlatformLinuxService linux,
                PlatformAuthorizationService.Launcher launcher) {
            this.interaction = interaction;
            this.linux = linux;
            this.launcher = launcher;
        }

        /** Returns host start authority without exposing it through ordinary TaskService. / 返回宿主启动授权，不通过普通 TaskService 暴露。
         * @return original activation's launcher / 原启用的启动器 */
        public PlatformAuthorizationService.Launcher launcher() {
            return launcher;
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
            launcher.close();
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
        if (models != null)
            models.close();
        for (var access : java.util.List.copyOf(serverAccesses))
            access.close();
        serverAccesses.clear();
        for (var secrets : java.util.List.copyOf(secretServices))
            secrets.close();
        secretServices.clear();
        for (var ai : java.util.List.copyOf(aiServices))
            ai.close();
        aiServices.clear();
        for (var source : java.util.List.copyOf(sourceServices))
            source.close();
        sourceServices.clear();
        for (var capabilities : java.util.List.copyOf(remoteCapabilities))
            capabilities.close();
        transfers.close();
        authorization.close();
        logs.close();
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
