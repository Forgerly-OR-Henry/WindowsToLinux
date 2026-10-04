package gold.debug.windowstolinux.app.linux.ssh.recover;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

import gold.debug.windowstolinux.app.linux.ssh.recover.ai.PlatformRecoveryModels;
import gold.debug.windowstolinux.app.linux.ssh.recover.browser.TaskBrowserSession;
import gold.debug.windowstolinux.app.linux.ssh.recover.model.RecoveryProbeResult;
import gold.debug.windowstolinux.app.linux.ssh.recover.model.RecoveryTarget;
import gold.debug.windowstolinux.app.linux.ssh.recover.model.TerminalTarget;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.TaskService;
import gold.debug.windowstolinux.appapi.task.model.TaskRequest;
import gold.debug.windowstolinux.appapi.task.model.TaskSnapshot;
import gold.debug.windowstolinux.appapi.task.model.TaskState;
import gold.debug.windowstolinux.appapi.task.recover.RecoveryRegistration;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;
import gold.debug.windowstolinux.appapi.task.result.Problem;
import gold.debug.windowstolinux.appapi.task.result.ProblemCode;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.appapi.task.run.TaskHandle;

/** Owns independent browser rescue on central platform tasks with retained exact resource cleanup. / 在中央平台任务中持有独立浏览器救援及精确原资源清理。 */
public final class SshRecovery implements AutoCloseable {
    /** Startup snapshot governing rescue timings and model budgets. / 控制救援时序及模型预算的启动快照。 */
    private final gold.debug.windowstolinux.app.config.AppConfig config;

    /** Stable original-task protocol, also restricting fixed host SSH verification. / 稳定原任务协议，同时限定宿主固定 SSH 复核。 */
    public static final String TASK_TYPE = "platform.ssh.rescue.v1";

    /** Exact platform task owner. / 精确平台任务所有者。 */
    private static final String OWNER = "@platform";

    /** Injected platform authority and durable receipts. / 注入的平台授权及持久凭据。 */
    private final RecoveryHost host;

    /** Current host release for new rescue tasks. / 新救援任务使用的当前宿主版本。 */
    private final String version;

    /** Original central task binding. / 原中央任务绑定。 */
    private final TaskService tasks;

    /** Actual sessions retained through original task reconciliation. / 保留至原任务核对的实际会话。 */
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();

    /** Original-version recovery registrations. / 原版本恢复登记。 */
    private final List<RecoveryRegistration> recoveries = new ArrayList<>();

    /** Admission seal for service shutdown. / 服务关闭的准入封闭。 */
    private boolean closed;

    /** Binds the full SSH rescue to existing platform capabilities. / 将完整 SSH 救援绑定到既有平台能力。
     * @param host original platform authority and receipts / 原平台授权及凭据
     * @param version actual host release / 实际宿主版本
     * @throws ApiException if recovery registration fails / 恢复登记失败时 */
    public SshRecovery(RecoveryHost host, String version) throws ApiException {
        this(host, version, gold.debug.windowstolinux.app.config.AppConfig.defaultsConfig());
    }

    /** Captures the startup configuration. / 捕获启动配置。
     * @param host supplied startup dependency / 启动依赖
     * @param version supplied startup dependency / 启动依赖
     * @param config supplied startup dependency / 启动依赖
     * @throws ApiException if composition fails / 装配失败时
     */
    public SshRecovery(RecoveryHost host, String version, gold.debug.windowstolinux.app.config.AppConfig config) throws ApiException {
        this.config = Objects.requireNonNull(config);
        this.host = Objects.requireNonNull(host);
        this.version = Objects.requireNonNull(version);
        tasks = host.tasks(version, false);
        var versions = new LinkedHashSet<String>();
        versions.add(version);
        pending().forEach(task -> versions.add(task.ownerVersion()));
        try {
            for (var original : versions)
                recoveries.add(host.tasks(original, true)
                    .registerRecovery(TASK_TYPE, (previous, task) -> {
                        var session = sessions.get(task.id());
                        if (session != null) {
                            if (session.browser != null)
                                session.browser.close();
                            host.closeResources(task);
                        } else {
                            host.releasePrevious(previous);
                        }
                        return new OperationResult<>(TaskResult.FAILED, Optional.empty(),
                            Optional.of(problem(ProblemCode.UNAVAILABLE, "RESCUE_RECONCILED",
                                "The interrupted rescue was reconciled without replaying any command")));
                    }));
        } catch (ApiException failure) {
            for (var registration : recoveries)
                try {
                    registration.close();
                } catch (ApiException rollback) {
                    failure.addSuppressed(rollback);
                }
            throw failure;
        }
    }

    /** Starts an independent rescue holding the complete saved-server resource from admission. / 从准入起持有完整已保存服务器资源以启动独立救援。
     * @param profile selected saved server / 所选已保存服务器
     * @return interactive view of the original accepted task / 原已接纳任务的交互视图
     * @throws ApiException if new rescue admission is unavailable / 新救援准入不可用时 */
    public synchronized SshRecoverySession start(Server profile) throws ApiException {
        host.requireUnlocked();
        if (closed)
            throw new ApiException(problem(ProblemCode.UNAVAILABLE, "RESCUE_CLOSED", "Rescue admission is closed"));
        for (var original : pending()) {
            var active = sessions.get(original.id());
            if (active != null && active.selected.id().equals(profile.id()))
                return active;
            try {
                if (host.holdsServer(original.id(), profile.id()))
                    throw new ApiException(problem(ProblemCode.BUSY, "RESCUE_ORIGINAL_RETAINED",
                        "The original server rescue requires reconciliation"));
            } catch (java.sql.SQLException failure) {
                throw new ApiException(problem(ProblemCode.IO_FAILURE, "RESCUE_OWNERSHIP",
                    "Original rescue ownership cannot be read"));
            }
        }
        var session = new Session(profile);
        session.handle = tasks.submit(
            new TaskRequest(TASK_TYPE, "SSH rescue: " + profile.displayName(), List.of(profile.id()), true),
            session::run);
        sessions.put(session.handle.id(), session);
        session.handle.completion().whenComplete((result, failure) -> {
            if (session.handle.snapshot().state() == TaskState.ENDED)
                sessions.remove(session.handle.id(), session);
        });
        return session;
    }

    /** Lists original unfinished rescue tasks without network or database work on the UI thread. / 不在 UI 线程访问网络或数据库地列出原未结束救援任务。
     * @return immutable original task snapshots / 不可变原任务快照 */
    public List<TaskSnapshot> pending() {
        return host.snapshots().stream().filter(task -> task.ownerId().equals(OWNER)
            && task.type().equals(TASK_TYPE) && task.state() != TaskState.ENDED).toList();
    }

    /** Reopens the existing current-process view without starting another rescue. / 重新打开当前进程既有视图，不启动第二次救援。
     * @param id original task identity / 原任务身份
     * @return existing interactive session / 既有交互会话
     * @throws ApiException if the original session is unavailable / 原会话不可用时 */
    public SshRecoverySession session(UUID id) throws ApiException {
        host.requireUnlocked();
        var session = sessions.get(id);
        if (session == null)
            throw new ApiException(problem(ProblemCode.NOT_FOUND, "RESCUE_SESSION_MISSING",
                "The original interactive rescue is unavailable"));
        return session;
    }

    /** Requests cooperative cancellation using the original central handle. / 使用原中央句柄请求协作取消。
     * @param id original task identity / 原任务身份
     * @throws ApiException if the original handle is unavailable / 原句柄不可用时 */
    public void cancel(UUID id) throws ApiException {
        session(id).close();
    }

    /** Reconciles original cleanup only after the user has checked any submitted terminal action. / 仅在用户已核对所提交终端动作后核对原清理。
     * @param id original rescue task / 原救援任务
     * @param actionReconciled explicit confirmation that the old action is no longer running / 明确确认旧动作已不再运行
     * @return actual original-task cleanup result / 实际原任务清理结果
     * @throws ApiException if identity or explicit reconciliation is missing / 身份或明确核对缺失时 */
    public CompletionStage<TaskSnapshot> reconcile(UUID id, boolean actionReconciled) throws ApiException {
        host.requireUnlocked();
        if (!actionReconciled || pending().stream().noneMatch(task -> task.id().equals(id)))
            throw new ApiException(problem(ProblemCode.ACCESS_DENIED, "RESCUE_RECONCILIATION_REQUIRED",
                "Original rescue and explicit action reconciliation are required"));
        return host.reconcile(id).thenApply(result -> {
            if (result.state() == TaskState.ENDED)
                sessions.remove(id);
            return result;
        });
    }

    /** Revokes registrations only after original tasks have ended. / 仅在原任务已结束后撤销登记。
     * @throws ApiException if rescue work or cleanup still remains / 仍有救援工作或清理时 */
    @Override
    public synchronized void close() throws ApiException {
        closed = true;
        if (!pending().isEmpty())
            throw new ApiException(problem(ProblemCode.BUSY, "RESCUE_TASKS_ACTIVE",
                "Original rescue tasks still require completion or reconciliation"));
        for (var registration : List.copyOf(recoveries)) {
            registration.close();
            recoveries.remove(registration);
        }
        sessions.clear();
    }

    /** Complete non-secret saved selection frozen before task admission. / 在任务准入前冻结的完整非秘密保存选择。
     * @param id saved identity / 已保存身份
     * @param host selected host / 所选主机
     * @param sshPort selected port / 所选端口
     * @param username selected login / 所选登录名
     * @param credentialKey opaque credential reference / 不透明凭据引用
     * @param displayName saved display name / 已保存显示名称 */
    public record Server(String id, String host, int sshPort, String username, String credentialKey,
                         String displayName) {
        /** Validates the selection without reading any secret. / 校验选择，不读取任何秘密。
         * @param id saved identity / 已保存身份
         * @param host selected host / 所选主机
         * @param sshPort selected port / 所选端口
         * @param username selected login / 所选登录名
         * @param credentialKey opaque credential reference / 不透明凭据引用
         * @param displayName saved display name / 已保存显示名称 */
        public Server {
            new gold.debug.windowstolinux.app.linux.ssh.connect.SshEndpoint(id, host, sshPort, username);
            Objects.requireNonNull(credentialKey);
            if (Objects.requireNonNull(displayName).isBlank() || displayName.length() > 120)
                throw new IllegalArgumentException("Invalid rescue server display name");
        }
    }

    /** Original interactive scope; all business runs on its central task callback. / 原交互作用域，全部业务在其中央任务回调运行。 */
    private final class Session implements SshRecoverySession {
        /** Selected display metadata; current authoritative settings are reloaded after admission. / 所选显示元数据，准入后重读当前权威设置。 */
        private final Server selected;

        /** Original task handle published before start returns. / start 返回前发布的原任务句柄。 */
        private volatile TaskHandle<BrowserRecoverySnapshot> handle;

        /** Actual serial controller retained for the UI. / 为 UI 保留的实际串行控制器。 */
        private volatile BrowserRecoverySession controller;

        /** Original physical owner retained across failed cleanup. / 跨失败清理保留的原物理所有者。 */
        private volatile TaskBrowserSession browser;

        /** Original platform evidence retained during setup or journal failure. / 准备或日志失败期间保留的原平台证据。 */
        private volatile Problem failure;

        /** Last non-secret command hash for durable submission events. / 持久提交事件所用最后非秘密命令摘要。 */
        private String digest = "";

        /** Captures the selected saved-server identity. / 捕获所选已保存服务器身份。
         * @param selected original saved selection / 原已保存选择 */
        private Session(Server selected) {
            this.selected = Objects.requireNonNull(selected);
        }

        /** Executes the full rescue inside the admitted original task. / 在已准入原任务内执行完整救援。
         * @param task authentic original context / 真实原上下文
         * @return final observation independent of retained cleanup / 独立于保留清理的最终观察
         * @throws Exception if original setup or cleanup cannot finish / 原准备或清理不能完成时 */
        private OperationResult<BrowserRecoverySnapshot> run(TaskContext task) throws Exception {
            var profile = host.server(selected.id());
            if (!profile.equals(selected))
                return new OperationResult<>(TaskResult.FAILED, Optional.empty(), Optional.of(problem(
                    ProblemCode.INVALID_INPUT, "RESCUE_SERVER_CHANGED",
                    "The saved server changed after selection; check the current configuration again")));
            browser = new TaskBrowserSession(host.browser(), task,
                OWNER, version, host::observeLock);
            try {
                controller = new BrowserRecoverySession(browser, new PlatformRecoveryModels(host::call, task, config.ai().recovery()),
                    () -> probe(task), snapshot -> journal(task, profile, snapshot),
                    new RecoveryTarget(profile.id(), profile.host(), profile.sshPort()), task, config.ssh());
                controller.run();
                var result = controller.snapshot();
                TaskResult outcome = result.resultUnknown() ? TaskResult.UNKNOWN : switch (result.state()) {
                    case RECOVERED -> TaskResult.SUCCEEDED;
                    case CANCELLED -> TaskResult.CANCELLED;
                    default -> TaskResult.FAILED;
                };
                var reason = outcome == TaskResult.SUCCEEDED
                    ? Optional.<Problem>empty()
                    : Optional.of(result.resultUnknown()
                    ? problem(ProblemCode.UNKNOWN, "RESCUE_COMMAND_UNCONFIRMED",
                    "The submitted terminal command requires manual reconciliation")
                    : outcome == TaskResult.CANCELLED
                    ? problem(ProblemCode.CANCELLED, "RESCUE_CANCELLED",
                    "The original rescue was cancelled")
                    : result.failure().isPresent()
                    ? result.failure().orElseThrow()
                    : failure != null
                    ? failure
                    : problem(ProblemCode.IO_FAILURE,
                    "RESCUE_"
                        + result.messageCode().toUpperCase(Locale.ROOT),
                    "Rescue ended without confirmed SSH recovery"));
                return new OperationResult<>(outcome, Optional.of(result), reason);
            } finally {
                browser.close();
            }
        }

        /** Performs only fixed authenticated read-only verification. / 仅执行固定已认证只读复核。
         * @param task original task / 原任务
         * @return classified original evidence / 分类后的原证据 */
        private RecoveryProbeResult probe(TaskContext task) {
            try {
                var result = host.verify(new ServerId(selected.id()), task);
                if (result.outcome() == TaskResult.SUCCEEDED) {
                    failure = null;
                    return RecoveryProbeResult.recovered();
                }
                failure = result.problem().orElseGet(
                    () -> problem(ProblemCode.CANCELLED, "RESCUE_CANCELLED", "Rescue verification was cancelled"));
            } catch (ApiException problem) {
                failure = problem.problem();
                if (problem.getSuppressed().length != 0)
                    failure = problem(ProblemCode.UNKNOWN, "RESCUE_SSH_CLEANUP",
                        "The original SSH resources could not be released");
            }
            return RecoveryProbeResult.failed(failure);
        }

        /** Commits only metadata under the same central task ID before any terminal input. / 任何终端输入前以同一中央任务 ID 提交元数据。
         * @param task original task / 原任务
         * @param profile admitted saved profile / 准入已保存配置
         * @param snapshot transient controller state / 临时控制器状态 */
        private void journal(TaskContext task, Server profile,
                             gold.debug.windowstolinux.app.linux.ssh.recover.BrowserRecoverySnapshot snapshot) {
            try {
                if (snapshot.action().isPresent())
                    digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                        .digest(snapshot.action().orElseThrow().command().getBytes(StandardCharsets.UTF_8)));
                var event = snapshot.messageCode().replaceAll("([a-z])([A-Z])", "$1-$2").toLowerCase(Locale.ROOT);
                host.journal(task, profile.id(), snapshot.state().name(), digest, event);
            } catch (Exception problem) {
                failure = problem(ProblemCode.IO_FAILURE, "RESCUE_JOURNAL",
                    "The original rescue journal could not be committed");
                throw new IllegalStateException("Rescue metadata commit failed", new ApiException(failure));
            }
        }

        /** Reads the safe snapshot only while unlocked. / 仅在解锁时读取安全快照。
         * @return current safe rescue snapshot / 当前安全救援快照 */
        @Override
        public BrowserRecoverySnapshot snapshot() {
            return host.whileUnlocked(() -> {
                if (controller != null)
                    return controller.snapshot();
                var task = handle.snapshot();
                var ended = task.state() == TaskState.ENDED || task.state() == TaskState.UNKNOWN;
                var cancelled = task.outcome().equals(Optional.of(TaskResult.CANCELLED));
                return new BrowserRecoverySnapshot(handle.id().toString(),
                    !ended
                        ? BrowserRecoverySnapshot.State.STARTING
                        : cancelled ? BrowserRecoverySnapshot.State.CANCELLED : BrowserRecoverySnapshot.State.FAILED,
                    !ended ? "starting" : cancelled ? "cancelled" : "probeFailed", Optional.empty(), "",
                    task.state() == TaskState.UNKNOWN, 0, Map.of(), Optional.empty());
            });
        }

        /** Lists terminals only after the original controller is ready. / 仅在原控制器就绪后列出终端。
         * @return selected-session candidates / 所选会话候选 */
        @Override
        public List<TerminalTarget> terminals() {
            return interactive(() -> controller == null
                ? List.of()
                : controller.terminals());
        }

        /** Confirms the original target and observation consent. / 确认原目标及观察同意。
         * @param target original terminal identity / 原终端身份
         * @param server original server identity / 原服务器身份
         * @param consent observation consent / 观察同意
         * @param reconciled original-action reconciliation / 原动作核对 */
        @Override
        public void bindTerminal(String target, String server, boolean consent, boolean reconciled) {
            host.whileUnlocked(() -> {
                requireController().bindTerminal(target, server, consent, reconciled);
                return null;
            });
        }

        /** Confirms only the current exact action and independent impact decision. / 仅确认当前精确动作及独立影响决定。
         * @param token original action token / 原动作令牌
         * @param highImpact separate impact consent / 独立影响同意 */
        @Override
        public void confirmAction(String token, boolean highImpact) {
            host.whileUnlocked(() -> {
                requireController().confirmAction(token, highImpact);
                return null;
            });
        }

        /** Revokes observation and proposals while the user takes over. / 用户接管时撤销观察及建议。 */
        @Override
        public void pause() {
            host.whileUnlocked(() -> {
                if (controller != null)
                    controller.pause();
                return null;
            });
        }

        /** Requires a new explicit terminal binding on resume. / 恢复时要求新的明确终端绑定。 */
        @Override
        public void resume() {
            host.whileUnlocked(() -> {
                requireController().resume();
                return null;
            });
        }

        /** Requests central cooperative cancellation without interrupting browser or model threads. / 请求中央协作取消，不中断浏览器或模型线程。 */
        @Override
        public void close() {
            host.requireUnlocked();
            try {
                handle.requestCancel();
            } catch (ApiException failure) {
                throw new IllegalStateException("Rescue cancellation failed", failure);
            }
        }

        /** Guards interactive access without keeping the security monitor across browser calls. / 保护交互访问，不在浏览器调用期间持有安全监视器。
         * @param action original interactive action / 原交互动作
         * @param <T> safe return type / 安全返回类型
         * @return original action result / 原动作结果 */
        private <T> T interactive(Supplier<T> action) {
            host.requireUnlocked();
            var result = action.get();
            host.requireUnlocked();
            return result;
        }

        /** Rejects controls before original task admission completes. / 原任务准入完成前拒绝控件操作。
         * @return actual original controller / 实际原控制器 */
        private BrowserRecoverySession requireController() {
            return Objects.requireNonNull(controller, "Original rescue has not started");
        }
    }

    /** Constructs safe platform evidence without terminal content. / 构造不含终端内容的安全平台证据。
     * @param code public outcome classification / 公开结果分类
     * @param event stable host event / 稳定宿主事件
     * @param message safe explanation / 安全说明
     * @return immutable problem / 不可变问题 */
    private static Problem problem(ProblemCode code, String event, String message) {
        return new Problem(code, event, message, Map.of(), Optional.empty());
    }
}
