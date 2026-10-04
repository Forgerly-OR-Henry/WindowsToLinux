package gold.debug.windowstolinux.app.main.start;

import java.util.*;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import java.util.function.Supplier;

import gold.debug.windowstolinux.app.db.PlatformStorage;
import gold.debug.windowstolinux.app.db.connect.PlatformDatabase;
import gold.debug.windowstolinux.app.db.task.*;
import gold.debug.windowstolinux.app.db.task.ProcessRecovery;
import gold.debug.windowstolinux.app.db.task.TaskLedger;
import gold.debug.windowstolinux.app.db.task.TaskResource;
import gold.debug.windowstolinux.app.linux.ssh.recover.*;
import gold.debug.windowstolinux.app.linux.ssh.recover.RecoveryHost;
import gold.debug.windowstolinux.app.linux.ssh.recover.SshRecovery;
import gold.debug.windowstolinux.app.linux.ssh.recover.browser.BrowserProcess;
import gold.debug.windowstolinux.app.linux.ssh.recover.browser.TaskBrowserSession;
import gold.debug.windowstolinux.app.linux.ssh.recover.model.RecoveryProbeResult.StatusType;
import gold.debug.windowstolinux.app.linux.ssh.recover.model.RecoveryProbeResult;
import gold.debug.windowstolinux.app.secret.SecretStoreException;
import gold.debug.windowstolinux.app.service.ai.PlatformAiService;
import gold.debug.windowstolinux.app.service.error.ApplicationServiceFailureType;
import gold.debug.windowstolinux.app.service.linux.PlatformLinuxService;
import gold.debug.windowstolinux.app.service.security.DesktopSecurityService;
import gold.debug.windowstolinux.app.service.server.PlatformServerService;
import gold.debug.windowstolinux.app.service.task.*;
import gold.debug.windowstolinux.app.service.task.HostTaskRecovery;
import gold.debug.windowstolinux.app.service.task.PlatformTaskAdmission;
import gold.debug.windowstolinux.app.service.task.PlatformTaskRuntime;
import gold.debug.windowstolinux.appapi.ai.*;
import gold.debug.windowstolinux.appapi.ai.model.AiRequest;
import gold.debug.windowstolinux.appapi.ai.model.AiResponse;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.*;
import gold.debug.windowstolinux.appapi.task.model.TaskSnapshot;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;
import gold.debug.windowstolinux.appapi.task.result.Problem;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;

/** Connects platform authority and database receipts to Linux-owned SSH recovery. / 将平台授权及数据库凭据接入 Linux 所属 SSH 救援。 */
final class PlatformRecoveryHost implements RecoveryHost {
    /** Original database and repositories. / 原数据库及仓库。 */
    private final PlatformStorage persistence;

    /** Original interactive lock authority. / 原交互锁定授权方。 */
    private final DesktopSecurityService security;

    /** Original central task runtime. / 原中央任务运行时。 */
    private final PlatformTaskRuntime runtime;

    /** Guarded platform SSH capability. / 受保护平台 SSH 能力。 */
    private final PlatformLinuxService linux;

    /** Original task-bound model capability. / 原任务绑定模型能力。 */
    private final PlatformAiService ai;

    /** Original transport and interaction cleanup. / 原传输及交互清理。 */
    private final PlatformServerService.TaskCleanup cleanup;

    /** Binds existing capabilities without starting another executor. / 绑定既有能力，不启动另一执行器。
     * @param persistence original storage / 原存储
     * @param security original security authority / 原安全授权方
     * @param runtime original tasks / 原任务
     * @param linux guarded transport / 受保护传输
     * @param ai configured models / 配置模型
     * @param cleanup original resource cleanup / 原资源清理 */
    PlatformRecoveryHost(PlatformStorage persistence, DesktopSecurityService security, PlatformTaskRuntime runtime,
                         PlatformLinuxService linux, PlatformAiService ai, PlatformServerService.TaskCleanup cleanup) {
        this.persistence = Objects.requireNonNull(persistence);
        this.security = Objects.requireNonNull(security);
        this.runtime = Objects.requireNonNull(runtime);
        this.linux = Objects.requireNonNull(linux);
        this.ai = Objects.requireNonNull(ai);
        this.cleanup = Objects.requireNonNull(cleanup);
    }

    /** Checks interactive authorization. / 检查交互授权。 */
    @Override
    public void requireUnlocked() {
        security.requireUnlocked();
    }

    /** Runs a short control under the original lock generation. / 在原锁定代次下运行短控制操作。
     * @param action original control / 原控制
     * @param <T> result type / 结果类型
     * @return original result / 原结果 */
    @Override
    public <T> T whileUnlocked(Supplier<T> action) {
        return security.whileUnlocked(action);
    }

    /** Registers browser privacy. / 登记浏览器隐私。
     * @param listener original lock observer / 原锁定观察者
     * @return retained registration / 保留登记 */
    @Override
    public AutoCloseable observeLock(Consumer<Boolean> listener) {
        return security.observeLock(listener);
    }

    /** Binds the original platform task owner. / 绑定原平台任务所有者。
     * @param version exact owner version / 精确所有者版本
     * @param recovery original-task mode / 原任务模式
     * @return authenticated task service / 已认证任务服务 */
    @Override
    public TaskService tasks(String version, boolean recovery) {
        return runtime.bind(PlatformTaskAdmission.PLATFORM_OWNER, version, recovery);
    }

    /** Lists the original central tasks. / 列出原中央任务。
     * @return current task observations / 当前任务观察 */
    @Override
    public List<TaskSnapshot> snapshots() {
        return runtime.snapshots();
    }

    /** Reads the current saved selection. / 读取当前已保存选择。
     * @param id original server identity / 原服务器身份
     * @return complete non-secret selection / 完整非秘密选择
     * @throws Exception if storage is unavailable / 存储不可用时 */
    @Override
    public SshRecovery.Server server(String id) throws Exception {
        var profile = persistence.servers().findServerProfile(id).orElseThrow();
        return new SshRecovery.Server(profile.id(), profile.host(), profile.sshPort(), profile.username(),
            profile.credentialKey(), profile.displayName());
    }

    /** Checks original retained server ownership. / 检查原保留服务器占用。
     * @param task original task / 原任务
     * @param server exact server / 精确服务器
     * @return whether ownership remains / 占用是否保留
     * @throws java.sql.SQLException if storage is unavailable / 存储不可用时 */
    @Override
    public boolean holdsServer(UUID task, String server) throws java.sql.SQLException {
        return new TaskLedger(persistence.database()).queries().holds(task,
            new TaskResource("SERVER", server, PlatformTaskAdmission.PLATFORM_OWNER, true));
    }

    /** Reconciles the existing task without submitting another one. / 核对既有任务，不提交另一任务。
     * @param id original task / 原任务
     * @return original completion / 原完成结果
     * @throws ApiException if authority is unavailable / 授权不可用时 */
    @Override
    public CompletionStage<TaskSnapshot> reconcile(UUID id) throws ApiException {
        return runtime.reconcile(id);
    }

    /** Releases only recorded previous-process resources. / 仅释放记录的前进程资源。
     * @param previous original task / 原任务
     * @throws Exception if release is unverified / 释放未核实时 */
    @Override
    public void releasePrevious(TaskSnapshot previous) throws Exception {
        HostTaskRecovery.releasePreviousProcess(persistence.database(), previous,
            Map.of("CONNECTION", "ssh-", "CUSTOM", "interaction-", "PROCESS", "browser-v1:"));
    }

    /** Supplies original browser resource receipts. / 提供原浏览器资源凭据。
     * @return task-bound browser authority / 任务绑定浏览器授权 */
    @Override
    public TaskBrowserSession.Host browser() {
        return browser(persistence.database(), runtime);
    }

    /** Executes the guarded fixed SSH verification. / 执行受保护固定 SSH 复核。
     * @param server admitted server / 已准入服务器
     * @param task original task / 原任务
     * @return fixed observation / 固定观察
     * @throws ApiException if authority or cleanup fails / 授权或清理失败时 */
    @Override
    public OperationResult<Void> verify(ServerId server, TaskContext task) throws ApiException {
        return linux.verifyRecovery(server, task);
    }

    /** Calls the configured model with its existing limits. / 按既有上限调用配置模型。
     * @param request bounded rescue request / 有界救援请求
     * @param task original task / 原任务
     * @return original model outcome / 原模型结果
     * @throws ApiException if authority or cleanup fails / 授权或清理失败时 */
    @Override
    public OperationResult<AiResponse> call(AiRequest request, TaskContext task) throws ApiException {
        return ai.callWithinModelLimit(request, task);
    }

    /** Closes original transport, interaction and model resources. / 关闭原传输、交互及模型资源。
     * @param task original task / 原任务
     * @throws ApiException if resources remain / 资源仍保留时 */
    @Override
    public void closeResources(TaskContext task) throws ApiException {
        cleanup.close(task);
        ai.closeTaskResources(task);
    }

    /** Stores only bounded rescue metadata under the original task. / 以原任务保存有界救援元数据。
     * @param task original task / 原任务
     * @param server original server / 原服务器
     * @param state rescue lifecycle / 救援生命周期
     * @param digest command hash / 命令摘要
     * @param event safe event / 安全事件
     * @throws Exception if the transaction fails / 事务失败时 */
    @Override
    public void journal(TaskContext task, String server, String state, String digest, String event) throws Exception {
        persistence.recovery().savePlatform(task.id(), server, state, digest, event);
    }

    /** Adapts physical browser receipts without exposing DB types to Linux. / 适配物理浏览器凭据，不向 Linux 暴露 DB 类型。
     * @param database original database / 原数据库
     * @param tasks original task runtime / 原任务运行时
     * @return original browser resource authority / 原浏览器资源授权方 */
    static TaskBrowserSession.Host browser(PlatformDatabase database, PlatformTaskRuntime tasks) {
        return new TaskBrowserSession.Host() {
            /** Requires original executing authority. / 要求原执行授权。
             * @param task original task / 原任务
             * @param owner original owner / 原所有者
             * @param version original version / 原版本
             * @throws ApiException if authority ended / 授权已结束时 */
            @Override
            public void requireExecuting(TaskContext task, String owner, String version) throws ApiException {
                tasks.requireExecuting(task, owner, version);
            }

            /** Preserves uncertain effects. / 保留不确定副作用。
             * @param task original task / 原任务
             * @throws ApiException if accounting fails / 记账失败时 */
            @Override
            public void uncertain(TaskContext task) throws ApiException {
                tasks.reportUncertainEffect(task);
            }

            /** Retains a durable pre-creation receipt. / 保留持久创建前凭据。
             * @param task original task / 原任务
             * @param owner original owner / 原所有者
             * @return retained claim / 保留申请
             * @throws ApiException if admission fails / 准入失败时 */
            @Override
            public TaskBrowserSession.Claim retain(TaskContext task, String owner) throws ApiException {
                var before = new ProcessRecovery(database.bootId(), UUID.randomUUID(), OptionalLong.empty(),
                    Optional.empty());
                var resource = new TaskResource("PROCESS", "browser-v1:" + before.process(), owner, false);
                var lease = tasks.retain(task, resource, before.encode("browser.v1"), false);
                return new TaskBrowserSession.Claim() {
                    /** Commits the observed process before terminal use. / 在终端使用前提交已观察进程。
                     * @param process original physical browser / 原物理浏览器
                     * @throws Exception if the receipt cannot commit / 凭据无法提交时 */
                    @Override
                    public void started(BrowserProcess process) throws Exception {
                        new TaskLedger(database).checkpointBrowser(task.id(), resource, before,
                            new ProcessRecovery(database.bootId(), before.process(), OptionalLong.of(process.pid()),
                                Optional.of(process.started())));
                    }

                    /** Confirms physical closure and releases the same claim. / 确认物理关闭并释放同一申请。
                     * @throws Exception if release cannot commit / 释放无法提交时 */
                    @Override
                    public void close() throws Exception {
                        new TaskLedger(database).confirmBrowserClosed(task.id(), resource, before);
                        lease.close();
                    }
                };
            }
        };
    }

    /**
     * Maps the first structured failure in the cause chain and separates interruption, authentication, host identity and credential failures. Only an explicit connection failure without interruption is retryable.
     * <p>转换原因链中的第一个结构化失败，并区分中断、认证、主机身份及凭据失败。仅未伴随中断的明确连接失败可重试。
     *
     * @param exception original exception being classified or translated / 正在分类或转换的原始异常
     * @return a classified result carrying the original descriptor when available / 携带原始描述（存在时）的分类结果
     */
    static RecoveryProbeResult failed(Exception exception) {
        Problem descriptor = null;
        boolean credential = false;
        boolean interrupted = Thread.currentThread().isInterrupted();
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable cause = exception; cause != null && visited.size() < 16
            && visited.add(cause); cause = cause.getCause()) {
            if (descriptor == null && cause instanceof SecretStoreException secret) {
                descriptor = secret.problem();
                credential = true;
            }
            if (descriptor == null && cause instanceof gold.debug.windowstolinux.appapi.task.result.ApiException api)
                descriptor = api.problem();
            if (descriptor == null
                && cause instanceof gold.debug.windowstolinux.app.service.error.ApplicationServiceException service)
                descriptor = service.problem();
            interrupted |= cause instanceof InterruptedException || cause instanceof java.io.InterruptedIOException
                || cause instanceof java.util.concurrent.CancellationException;
        }
        if (descriptor == null)
            descriptor = (interrupted
                ? ApplicationServiceFailureType.RECOVERY_INTERRUPTED
                : ApplicationServiceFailureType.RECOVERY_PROBE_FAILED).problem(UUID.randomUUID(), Map.of(),
                interrupted ? "SSH recovery probe was interrupted" : "SSH recovery verification failed");
        if (interrupted || credential)
            return new RecoveryProbeResult(interrupted ? StatusType.INTERRUPTED : StatusType.CREDENTIAL_UNAVAILABLE,
                Optional.of(descriptor));
        return RecoveryProbeResult.failed(descriptor);
    }

}
