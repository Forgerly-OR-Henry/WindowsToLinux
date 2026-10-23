package gold.debug.windowstolinux.mod.deployment.service.environment;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import gold.debug.windowstolinux.appapi.linux.action.RemoteActions;
import gold.debug.windowstolinux.appapi.linux.session.RemoteTarget;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.server.model.ServerInfo;
import gold.debug.windowstolinux.appapi.task.action.ActionIntent;
import gold.debug.windowstolinux.appapi.task.model.TaskRequest;
import gold.debug.windowstolinux.appapi.task.model.TaskSnapshot;
import gold.debug.windowstolinux.appapi.task.query.PageSlice;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;
import gold.debug.windowstolinux.appapi.task.result.Problem;
import gold.debug.windowstolinux.appapi.task.result.ProblemCode;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.appapi.task.run.TaskHandle;

/** Original server-exclusive environment business task and explicit recovery. / 原服务器独占环境业务任务及明确恢复。 */
public final class DeploymentEnvironmentWorkflow {
    /** Exact recoverable task type. / 精确可恢复任务类型。 */
    public static final String TASK_TYPE = "deployment.environment.v1";

    /** Original bound capabilities. / 原绑定能力。 */
    private final ModContext context;

    /** Original mode's exact command review. / 原模式精确命令审核。 */
    private final DeploymentEnvironmentCommandReview review;

    /** Fixed timing captured before this task opens an environment operation. / 当前任务打开环境操作前捕获的固定时序。 */
    private final gold.debug.windowstolinux.mod.deployment.config.DeploymentConfig.Environment timing;

    /** Only this activation's submitted initial-consent observations; these values grant no task authority. / 仅本启用提交任务的初始同意观测，这些值不授予任务权限。 */
    private final java.util.concurrent.ConcurrentMap<java.util.UUID, Boolean> consentAccepted = new java.util.concurrent.ConcurrentHashMap<>();

    /** Uses the static flow's actual host review without introducing a model policy. / 使用静态流程实际宿主审核，不引入模型政策。
     * @param context original Mod capabilities / 原 Mod 能力 */
    public DeploymentEnvironmentWorkflow(ModContext context) {
        this(context, (target, revision, request, task) -> context.interaction()
            .approve(RemoteActions.command(target.server(), revision, request), task));
    }

    /** Binds a mode's actual command review callback. / 绑定模式的实际命令审核回调。
     * @param context original Mod capabilities / 原 Mod 能力
     * @param review required actual mode review / 所需实际模式审核 */
    public DeploymentEnvironmentWorkflow(ModContext context, DeploymentEnvironmentCommandReview review) {
        this(context, review, gold.debug.windowstolinux.mod.deployment.config.DeploymentConfigLoader.current().environment());
    }

    /** Binds explicit timing while retaining the original review authority. / 绑定明确时序并保留原审核权威。
     * @param context original capabilities / 原能力
     * @param review actual command review / 实际命令审核
     * @param timing immutable initial timing / 不可变初始时序 */
    public DeploymentEnvironmentWorkflow(ModContext context, DeploymentEnvironmentCommandReview review,
                                         gold.debug.windowstolinux.mod.deployment.config.DeploymentConfig.Environment timing) {
        this.context = Objects.requireNonNull(context);
        this.review = Objects.requireNonNull(review);
        this.timing = Objects.requireNonNull(timing);
    }

    /** Submits one original task after freezing the displayed target snapshot. / 冻结展示的目标快照后提交一项原任务。
     * @param original exact displayed non-secret saved server / 精确展示的非秘密保存服务器
     * @return host-owned business task independent of the page / 独立于页面的宿主所属业务任务
     * @throws ApiException if original exclusive admission fails / 原独占准入失败时 */
    public TaskHandle<Void> submit(ServerInfo original) throws ApiException {
        Objects.requireNonNull(original);
        var handle = context.tasks().<Void>submit(new TaskRequest(TASK_TYPE, gold.debug.windowstolinux.mod.deployment.service.feedback.DeploymentDiagnostics.text(context, "environment.taskTitle"),
            List.of(original.ref().id()), true), task -> start(original, task));
        consentAccepted.putIfAbsent(handle.id(), false);
        handle.completion().whenComplete((result, failure) -> consentAccepted.remove(handle.id()));
        return handle;
    }

    /** Observes only this activation's unanswered initial scope review, without storage or operation authority. / 仅观察本启用尚未回答的初始范围审阅，不授予存储或操作权限。
     * @param originalTaskId original submitted task identity / 原提交任务身份
     * @return true only while initial scope consent is unaccepted; terminal or foreign tasks return false / 仅初始范围未接受时为真，终态或外部任务返回假 */
    public boolean awaitingConsent(java.util.UUID originalTaskId) {
        return Boolean.FALSE.equals(consentAccepted.get(Objects.requireNonNull(originalTaskId)));
    }

    /** Lists the original owner's outstanding tasks through the central authority. / 经中央权威列出原所有者未完成任务。
     * @param cursor original page cursor / 原分页游标
     * @param limit bounded page capacity / 有界分页容量
     * @return actual owner tasks for central observation / 中央观察用实际所有者任务
     * @throws ApiException if original lookup fails / 原查找失败时 */
    public PageSlice<TaskSnapshot> list(Optional<String> cursor, int limit) throws ApiException {
        return context.tasks().list(cursor, limit);
    }

    /** Reconciles exact retained original evidence and never manufactures a new operation. / 核对精确保留原依据，绝不制造新操作。
     * @param previous original retained task snapshot / 原保留任务快照
     * @param task authentic original recovery context / 真实原恢复上下文
     * @return original verified or retained uncertain result / 原已核或保留未知结果
     * @throws Exception if original evidence or cleanup fails / 原依据或清理失败时 */
    public OperationResult<Void> reconcile(TaskSnapshot previous, TaskContext task) throws Exception {
        if (!previous.id().equals(task.id()) || !TASK_TYPE.equals(previous.type()))
            throw new IllegalArgumentException("Environment recovery task differs");
        var journal = DeploymentEnvironmentJournal.find(context.tasks(), task);
        if (journal.isEmpty()) return failed("ENVIRONMENT_ORIGINAL_EVIDENCE_MISSING");
        return new DeploymentEnvironmentRunner(context, review, task, journal.orElseThrow()).run(true);
    }

    /** Confirms the fixed scope before any SSH operation. / 任何 SSH 操作前确认固定范围。
     * @param original displayed target snapshot / 展示的目标快照
     * @param task admitted original task / 已准入原任务
     * @return actual outcome / 实际结果
     * @throws Exception if original evidence or cleanup fails / 原依据或清理失败时 */
    private OperationResult<Void> start(ServerInfo original, TaskContext task) throws Exception {
        var layout = new gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout(
            gold.debug.windowstolinux.mod.deployment.config.DeploymentConfigLoader.current().paths());
        gold.debug.windowstolinux.mod.deployment.config.DeploymentConfigCapture.remoteLayout(context.tasks(), task, layout);
        var target = new RemoteTarget(original.ref(), task.id(), original.host(), original.port(), original.username(),
            original.trustedFingerprint());
        String exact = "server=" + original.ref().id() + "\nhost=" + original.host() + "\nport=" + original.port()
            + "\nusername=" + original.username() + "\nfingerprint=" + original.trustedFingerprint().orElse("first-use")
            + "\nFixed original distribution packages, Java 21/Maven/Node/Python/C/C++, containers and managed helper."
            + "\nDNF 9 installs/updates openssh, openssh-server and openssh-clients. CentOS CRB is transaction-only."
            + "\nSELinux configuration and reboot require separate explicit system confirmation. No full-system upgrade.";
        var approval = context.interaction().approve(new ActionIntent("deployment.environment.confirm", List.of(original.ref().id()),
            original.ref().id(), DeploymentEnvironmentScripts.bodyDigest(layout), exact,
            gold.debug.windowstolinux.mod.deployment.service.feedback.DeploymentDiagnostics.text(context, "environment.confirmTarget")), task);
        if (approval.outcome() != TaskResult.SUCCEEDED)
            return new OperationResult<>(approval.outcome(), Optional.empty(), approval.problem());
        consentAccepted.put(task.id(), true);
        gold.debug.windowstolinux.mod.deployment.config.DeploymentConfigCapture.environment(context.tasks(), task, timing);
        var journal = DeploymentEnvironmentJournal.begin(context.tasks(), task, target);
        return new DeploymentEnvironmentRunner(context, review, task, journal).run(false);
    }

    /** Reports missing original evidence without transmitting credentials. / 报告原依据缺失，不传输凭据。
     * @param event safe event / 安全事件
     * @return failed result / 失败结果 */
    private static OperationResult<Void> failed(String event) {
        return new OperationResult<>(TaskResult.FAILED, Optional.empty(), Optional.of(new Problem(ProblemCode.IO_FAILURE,
            event, "Original environment evidence is unavailable", Map.of(), Optional.empty())));
    }
}
