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

    /** Shared service publication and synchronous provider ownership. / 共用服务发布及同步提供方归属。 */
    private final gold.debug.windowstolinux.app.service.mod.PlatformModServices services;

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

    /** Permanent independent rescue coordinator on central platform tasks. / 中央平台任务上的常驻独立救援协调器。 */
    private gold.debug.windowstolinux.app.service.recovery.PlatformRecoveryService recovery;

    /** Permanent model settings and capability probes. / 常驻模型设置及能力探测。 */
    private gold.debug.windowstolinux.app.service.ai.PlatformModelService models;

    /** Original restart-intent management and request reviews. / 原重启意图管理及请求审阅。 */
    private gold.debug.windowstolinux.app.service.mod.PlatformModIntents modIntents;

    /** Original-package installer, owned only in actual JAR or APP layouts. / 仅实际 JAR 或 APP 布局持有的原包安装器。 */
    private gold.debug.windowstolinux.app.service.mod.PlatformModInstallation modInstallation;

    /** Original automatic CLASS graph and its reverse cleanup ownership. / 原自动 CLASS 图及其逆序清理归属。 */
    private gold.debug.windowstolinux.app.main.mod.PlatformModCoordinator modCoordinator;

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

    /** Activation process owners retained until actual exit and durable release. / 保留至实际退出及持久释放的启用进程所有者。 */
    private final java.util.Set<gold.debug.windowstolinux.app.service.task.PlatformProcessService> processServices = new java.util.LinkedHashSet<>();

    /** Complete or partially prepared Mod contexts whose cleanup is still owned. / 仍持有清理责任的完整或部分已准备 Mod 上下文。 */
    private final java.util.Map<String, gold.debug.windowstolinux.app.main.mod.PlatformModContext> modContexts = new java.util.LinkedHashMap<>();

    /** Original loaders and lifecycle journals retained beyond physical context closure. / 超出物理上下文关闭阶段仍保留的原加载器及生命周期记录器。 */
    private final java.util.Map<String, gold.debug.windowstolinux.app.mod.lifecycle.ModRuntimeSession> modSessions = new java.util.LinkedHashMap<>();

    /** Disabled revisions captured before this runtime accepts any new actions. / 本运行时接纳任何新操作前捕获的禁用修订。 */
    private final java.util.Map<String, gold.debug.windowstolinux.app.db.mod.ModRestartLedger.Disabled> initiallyDisabled;

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
        initiallyDisabled = gold.debug.windowstolinux.app.db.mod.ModRestartLedger
                .initiallyDisabled(persistence.database());
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
        services = new gold.debug.windowstolinux.app.service.mod.PlatformModServices(persistence.database(), tasks);
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

    /** Prepares all actual Mod capabilities after the desktop presenters are installed. / 桌面呈现器安装后准备全部实际 Mod 能力。
     * @param loader actual prepared loader / 实际已准备加载器
     * @param schema exact selected business generation / 精确选定业务代次
     * @param mode normal or recovery activation / 普通或恢复启用
     * @param pages actual desktop page registry / 实际桌面页面登记
     * @return host-owned context lease for the surrounding Mod runtime session / 外围 Mod 运行会话所用宿主持有上下文占用
     * @throws Exception if identity, preparation or cleanup fails / 身份、准备或清理失败时 */
    public gold.debug.windowstolinux.app.main.mod.PlatformModContext modContext(
            gold.debug.windowstolinux.app.mod.loader.ModClassLoader loader,
            java.util.Optional<gold.debug.windowstolinux.appapi.db.SchemaIdentity> schema,
            gold.debug.windowstolinux.appapi.mod.ModLaunchMode mode,
            gold.debug.windowstolinux.app.ui.shell.ModPageRegistry pages) throws Exception {
        requireWorker();
        final gold.debug.windowstolinux.app.main.mod.PlatformModContext prepared;
        synchronized (this) {
            if (stopping || closed || prompts == null || sourcePicker == null)
                throw new IllegalStateException("Install desktop presenters before preparing Mod capabilities");
            String owner = loader.manifest().id();
            if (modContexts.containsKey(owner) || modSessions.containsKey(owner))
                throw new IllegalStateException("This Mod still owns an activation context");
            prepared = new gold.debug.windowstolinux.app.main.mod.PlatformModContext(this, loader, schema, mode, pages,
                    () -> forgetModContext(owner));
            modContexts.put(owner, prepared);
        }
        try {
            prepared.prepare();
            return prepared;
        } catch (Exception | LinkageError | AssertionError failure) {
            try {
                prepared.close();
            } catch (Exception cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    /** Exposes retained context leases to trusted lifecycle cleanup, including failed preparation. / 向可信生命周期清理公开保留上下文占用，包括失败准备。
     * @return exact original context ownership / 精确原上下文归属 */
    public synchronized java.util.Map<String, gold.debug.windowstolinux.app.main.mod.PlatformModContext> modContexts() {
        return java.util.Map.copyOf(modContexts);
    }

    /** Creates one real session whose durable lifecycle must finish before platform shutdown. / 创建一个真实会话，其持久生命周期必须在平台关闭前完成。
     * @param loader actual prepared code owner / 实际已准备代码所有者
     * @param schema selected data generation / 选定数据代次
     * @param mode normal or recovery mode / 普通或恢复模式
     * @param pages actual page registry / 实际页面登记
     * @param platformApi actual host API release / 实际宿主 API 版本
     * @return retained original lifecycle session / 保留的原生命周期会话
     * @throws Exception if context preparation or duplicate ownership prevents construction / 上下文准备或重复归属阻止构造时 */
    public gold.debug.windowstolinux.app.mod.lifecycle.ModRuntimeSession modSession(
            gold.debug.windowstolinux.app.mod.loader.ModClassLoader loader,
            java.util.Optional<gold.debug.windowstolinux.appapi.db.SchemaIdentity> schema,
            gold.debug.windowstolinux.appapi.mod.ModLaunchMode mode,
            gold.debug.windowstolinux.app.ui.shell.ModPageRegistry pages,
            gold.debug.windowstolinux.app.mod.version.SemanticVersion platformApi) throws Exception {
        Objects.requireNonNull(platformApi);
        synchronized (this) {
            if (modSessions.containsKey(loader.manifest().id()))
                throw new IllegalStateException("Original Mod lifecycle acknowledgement is still pending");
        }
        var context = modContext(loader, schema, mode, pages);
        var session = new gold.debug.windowstolinux.app.mod.lifecycle.ModRuntimeSession(loader, context, platformApi,
                context.journal(() -> forgetModSession(loader.manifest().id())));
        synchronized (this) {
            modSessions.put(loader.manifest().id(), session);
        }
        return session;
    }

    /** Exposes original sessions for host cleanup retries without replaying author startup. / 向宿主清理重试提供原会话，不重放作者启动。
     * @return retained session identities / 保留会话身份 */
    public synchronized java.util.Map<String, gold.debug.windowstolinux.app.mod.lifecycle.ModRuntimeSession> modSessions() {
        return java.util.Map.copyOf(modSessions);
    }

    /** Returns host-only Mod intent management using the actual compiled public API identity. / 使用实际编译公开 API 身份返回仅供宿主的 Mod 意图管理。
     * @return original management service / 原管理服务
     * @throws Exception if build identity or archive registration is unavailable / 构建身份或归档登记不可用时 */
    public synchronized gold.debug.windowstolinux.app.service.mod.PlatformModIntents modIntents() throws Exception {
        requireWorker();
        if (stopping || closed)
            throw new IllegalStateException("Mod intent management is closing");
        if (modIntents == null) {
            modIntents = new gold.debug.windowstolinux.app.service.mod.PlatformModIntents(persistence.database(),
                    security, readApiVersion());
        }
        return modIntents;
    }

    /** Returns original-package installation for the actual application layout. / 为实际应用布局返回原包安装能力。
     * @return original installation owner / 原安装所有者
     * @throws Exception if closing, in CLASS mode or storage is unavailable / 正在关闭、处于 CLASS 模式或存储不可用时 */
    public synchronized gold.debug.windowstolinux.app.service.mod.PlatformModInstallation modInstallation()
            throws Exception {
        requireWorker();
        if (stopping || closed)
            throw new IllegalStateException("Mod installation is closing");
        var layout = gold.debug.windowstolinux.app.main.runtime.RunModeResolver.resolve();
        if (layout.mode() == gold.debug.windowstolinux.app.main.runtime.RunModeResolver.RunMode.RUN_CLASS)
            throw new IllegalStateException("CLASS mode uses source builds, not installed package directories");
        if (modInstallation == null)
            modInstallation = new gold.debug.windowstolinux.app.service.mod.PlatformModInstallation(
                    persistence.database(), security, layout.applicationHome(), logs, readApiVersion());
        return modInstallation;
    }

    /** Starts the one automatic graph without holding the runtime monitor across author code. / 启动唯一自动图，不跨作者代码持有运行时监视器。
     * @param workspace explicit validated CLASS workspace / 明示已验证 CLASS 工作区
     * @param pages actual visible window page registry / 实际可见窗口页面登记
     * @return original graph's startup facts / 原图启动事实
     * @throws Exception if ownership or build identity prevents composition / 归属或构建身份阻止装配时 */
    public gold.debug.windowstolinux.app.main.mod.PlatformModCoordinator.Report startDevelopmentMods(Path workspace,
            gold.debug.windowstolinux.app.ui.shell.ModPageRegistry pages) throws Exception {
        return startMods(workspace, true, pages);
    }

    /** Starts installed code from the explicit executable home using the same lifecycle. / 使用相同生命周期从明示程序根启动安装代码。
     * @param home verified executable home / 已验证程序根
     * @param pages actual desktop registry / 实际桌面登记
     * @return actual graph facts / 实际图事实
     * @throws Exception if graph ownership or installed code is unavailable / 图归属或安装代码不可用时 */
    public gold.debug.windowstolinux.app.main.mod.PlatformModCoordinator.Report startInstalledMods(Path home,
            gold.debug.windowstolinux.app.ui.shell.ModPageRegistry pages) throws Exception {
        return startMods(home, false, pages);
    }

    /** Owns exactly one graph while keeping author callbacks outside the runtime monitor. / 持有唯一图，使作者回调离开运行时监视器。
     * @param root original source root / 原来源根
     * @param development whether CLASS discovery applies / 是否应用 CLASS 发现
     * @param pages actual desktop registry / 实际桌面登记
     * @return actual startup facts / 实际启动事实
     * @throws Exception if ownership or source verification fails / 归属或来源核验失败时 */
    private gold.debug.windowstolinux.app.main.mod.PlatformModCoordinator.Report startMods(Path root,
            boolean development, gold.debug.windowstolinux.app.ui.shell.ModPageRegistry pages) throws Exception {
        requireWorker();
        final gold.debug.windowstolinux.app.main.mod.PlatformModCoordinator coordinator;
        synchronized (this) {
            if (stopping || closed || modCoordinator != null || !modContexts.isEmpty() || !modSessions.isEmpty())
                throw new IllegalStateException("Mod startup already has an owner or the platform is closing");
            coordinator = new gold.debug.windowstolinux.app.main.mod.PlatformModCoordinator(this, root, development,
                    initiallyDisabled, pages, readApiVersion());
            modCoordinator = coordinator;
        }
        return coordinator.start();
    }

    /** Runs an explicit original schema inspection through the retained original graph. / 经保留原图运行明确原数据检查。
     * @param preparation original retained preparation / 原保留准备
     * @return observed data outcome without business startup / 不含业务启动的已观察数据结果
     * @throws Exception if authorization, graph or original cleanup is unavailable / 授权、图或原清理不可用时 */
    public gold.debug.windowstolinux.appapi.task.Outcome reconcileModSchema(java.util.UUID preparation)
            throws Exception {
        requireWorker();
        final gold.debug.windowstolinux.app.main.mod.PlatformModCoordinator coordinator;
        synchronized (this) {
            if (stopping || closed || modCoordinator == null)
                throw new IllegalStateException("Mod graph is unavailable");
            coordinator = modCoordinator;
        }
        return coordinator.reconcileSchema(preparation);
    }

    /** Reviews an explicitly selected source for rebuilding a failed unused target. / 审阅明确所选来源以重建失败未使用目标。
     * @param owner selected Mod / 已选 Mod
     * @param source explicitly confirmed source / 明确确认来源
     * @return original concrete retry review / 原具体重试审阅
     * @throws Exception if the graph or retry requirements are unavailable / 图或重试条件不可用时 */
    public gold.debug.windowstolinux.app.service.mod.PlatformModIntents.RetryReview reviewModSchemaRetry(String owner,
            java.util.Optional<gold.debug.windowstolinux.appapi.db.SchemaIdentity> source) throws Exception {
        requireWorker();
        final gold.debug.windowstolinux.app.main.mod.PlatformModCoordinator coordinator;
        synchronized (this) {
            if (stopping || closed || modCoordinator == null)
                throw new IllegalStateException("Mod graph is unavailable");
            coordinator = modCoordinator;
        }
        return coordinator.reviewSchemaRetry(owner, source);
    }

    /** Executes one explicit retry in this boot without changing the user's selection. / 在本次启动执行一次明确重试，不改变用户选择。
     * @param review user-confirmed original review / 用户确认原审阅
     * @return actual data result without starting business / 不启动业务的实际数据结果
     * @throws Exception if original execution or cleanup remains unconfirmed / 原执行或清理仍未确认时 */
    public gold.debug.windowstolinux.appapi.task.Outcome retryModSchema(
            gold.debug.windowstolinux.app.service.mod.PlatformModIntents.RetryReview review) throws Exception {
        requireWorker();
        final gold.debug.windowstolinux.app.main.mod.PlatformModCoordinator coordinator;
        synchronized (this) {
            if (stopping || closed || modCoordinator == null)
                throw new IllegalStateException("Mod graph is unavailable");
            coordinator = modCoordinator;
        }
        return coordinator.retrySchema(review);
    }

    /** Reads the actual compiled appapi identity for both management and startup. / 为管理与启动读取实际编译 appapi 身份。
     * @return exact public API release / 精确公开 API 版本
     * @throws java.io.IOException if identity is unavailable / 身份不可用时 */
    private static gold.debug.windowstolinux.app.mod.version.SemanticVersion readApiVersion()
            throws java.io.IOException {
        var properties = new java.util.Properties();
        try (var input = gold.debug.windowstolinux.appapi.mod.ModContext.class
                .getResourceAsStream("/gold/debug/windowstolinux/appapi/build.properties")) {
            if (input == null)
                throw new java.io.IOException("Public API build identity is missing");
            properties.load(input);
        }
        return gold.debug.windowstolinux.app.mod.version.SemanticVersion
                .parse(properties.getProperty("platform.api.version"));
    }

    /** Releases host session bookkeeping after physical and durable cleanup. / 在物理及持久清理后释放宿主会话登记。
     * @param owner original owner ID / 原所有者 ID */
    private synchronized void forgetModSession(String owner) {
        modSessions.remove(owner);
    }

    /** Removes a context only after its own physical cleanup succeeded. / 仅在上下文本身物理清理成功后移除。
     * @param owner exact completed activation owner / 精确已完成启用所有者 */
    private synchronized void forgetModContext(String owner) {
        modContexts.remove(owner);
    }

    /** Returns service graph assembly for trusted lifecycle composition. / 返回供可信生命周期装配使用的服务图。
     * @return actual service publication owner / 实际服务发布所有者 */
    public synchronized gold.debug.windowstolinux.app.service.mod.PlatformModServices services() {
        if (stopping || closed)
            throw new IllegalStateException("Platform services are closing");
        return services;
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

    /** Composes permanent rescue with actual host model, browser and SSH task ownership. / 使用实际宿主模型、浏览器及 SSH 任务归属装配常驻救援。
     * @return original platform rescue coordinator / 原平台救援协调器
     * @throws gold.debug.windowstolinux.appapi.task.ApiException if original recovery registration fails / 原恢复登记失败时 */
    public synchronized gold.debug.windowstolinux.app.service.recovery.PlatformRecoveryService recovery()
            throws gold.debug.windowstolinux.appapi.task.ApiException {
        if (closed || stopping || prompts == null)
            throw new IllegalStateException("Platform rescue is unavailable");
        if (recovery == null) {
            var remote = remoteCapabilities(PlatformTaskAdmission.PLATFORM_OWNER, version);
            var model = ai(PlatformTaskAdmission.PLATFORM_OWNER, version);
            try {
                recovery = new gold.debug.windowstolinux.app.service.recovery.PlatformRecoveryService(persistence,
                        security, tasks, version, remote.linux, model, remote::closeTaskResources);
            } catch (gold.debug.windowstolinux.appapi.task.ApiException | RuntimeException failure) {
                try {
                    remote.close();
                } catch (Exception cleanup) {
                    failure.addSuppressed(cleanup);
                }
                try {
                    model.close();
                } catch (Exception cleanup) {
                    failure.addSuppressed(cleanup);
                }
                throw failure;
            }
        }
        return recovery;
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

    /** Creates an original-owner managed-process capability without starting a child. / 创建原所有者受管进程能力，不启动子进程。
     * @param owner actual Mod identity / 实际 Mod 身份
     * @param version loaded release / 加载发布版本
     * @return bound process capability / 绑定进程能力 */
    public synchronized gold.debug.windowstolinux.app.service.task.PlatformProcessService processes(String owner,
            String version) {
        if (closed || stopping)
            throw new IllegalStateException("Platform process capabilities are closing");
        var processes = new gold.debug.windowstolinux.app.service.task.PlatformProcessService(persistence.database(),
                tasks, logs(owner, version, java.util.Optional.empty()), owner, version);
        processServices.add(processes);
        return processes;
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
    public void close() throws Exception {
        requireWorker();
        final gold.debug.windowstolinux.app.main.mod.PlatformModCoordinator coordinator;
        synchronized (this) {
            if (closed)
                return;
            coordinator = modCoordinator;
        }
        boolean wasSealed = tasks.seal(true);
        try {
            if (coordinator != null)
                coordinator.close();
            closePlatform();
        } catch (Exception | Error failure) {
            synchronized (this) {
                if (!stopping && !closed)
                    tasks.seal(wasSealed);
            }
            throw failure;
        }
    }

    /** Closes platform services only after the automatic graph released its actual ownership. / 仅在自动图释放实际归属后关闭平台服务。
     * @throws Exception if original resources or final acknowledgements remain / 原资源或最终确认仍残留时 */
    private synchronized void closePlatform() throws Exception {
        requireWorker();
        if (closed)
            return;
        boolean wasSealed = tasks.seal(true);
        if (!stopping) {
            try {
                if (!modContexts.isEmpty() || !modSessions.isEmpty())
                    throw new SQLException(
                            "Mod sessions and their original contexts must close before platform shutdown");
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
        if (recovery != null)
            recovery.close();
        if (models != null)
            models.close();
        if (modInstallation != null)
            modInstallation.close();
        if (modIntents != null)
            modIntents.close();
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
        for (var processes : java.util.List.copyOf(processServices))
            processes.close();
        processServices.clear();
        for (var capabilities : java.util.List.copyOf(remoteCapabilities))
            capabilities.close();
        transfers.close();
        authorization.close();
        services.close();
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
