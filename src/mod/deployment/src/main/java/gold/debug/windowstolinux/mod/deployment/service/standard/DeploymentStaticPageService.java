package gold.debug.windowstolinux.mod.deployment.service.standard;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.server.ServerInfo;
import gold.debug.windowstolinux.appapi.server.ServerRef;
import gold.debug.windowstolinux.appapi.task.*;
import gold.debug.windowstolinux.mod.deployment.db.DeploymentSecretRepository;
import gold.debug.windowstolinux.mod.deployment.secret.DeploymentSecretProvisionFlow;
import gold.debug.windowstolinux.mod.deployment.secret.SecretValueSource;
import gold.debug.windowstolinux.mod.deployment.service.core.model.DeploymentSecretReference;
import gold.debug.windowstolinux.mod.deployment.service.core.model.DeploymentSecretRevision;
import gold.debug.windowstolinux.mod.deployment.service.core.model.DeploymentSecretSelection;

/** Binds one page's read-only server lookup and original publication task without owning its Swing view. / 绑定页面只读服务器查询及原发布任务，但不持有 Swing 视图。 */
public final class DeploymentStaticPageService implements TaskRecovery {
    /** Read-only short task type. / 只读短任务类型。 */
    public static final String SERVER_LIST_TASK = "deployment-server-list";

    /** Read-only secret choice task type. / 只读秘密选项任务类型。 */
    public static final String SECRET_LIST_TASK = "deployment-secret-list";

    /** Original platform capabilities. / 原平台能力。 */
    private final ModContext context;

    /** Actual original-task static workflow. / 实际原任务静态流程。 */
    private final DeploymentStaticWorkflow workflow;

    /** Existing original-task secret creation. / 既有原任务秘密创建。 */
    private final DeploymentSecretProvisionFlow secrets;

    /** Business revision metadata without plaintext. / 不含明文的业务修订元数据。 */
    private final DeploymentSecretRepository revisions;

    /** Current accepted publication task, independent of an open page. / 当前已接纳发布任务，独立于开放页面。 */
    private TaskHandle<Void> active;

    /** Binds current Mod services. / 绑定当前 Mod 服务。
     * @param context original Mod context / 原 Mod 上下文
     * @param workflow original static workflow / 原静态流程 */
    public DeploymentStaticPageService(ModContext context, DeploymentStaticWorkflow workflow) {
        this.context = Objects.requireNonNull(context);
        this.workflow = Objects.requireNonNull(workflow);
        this.secrets = new DeploymentSecretProvisionFlow(context);
        this.revisions = new DeploymentSecretRepository(context.db());
    }

    /** Loads the owned page selection off the EDT, filling only missing defaults. / 在EDT外加载所属页面选择，仅补缺失默认值。
     * @return original preference query / 原偏好查询
     * @throws ApiException if original query admission fails / 原查询准入失败时 */
    public TaskHandle<gold.debug.windowstolinux.mod.deployment.db.DeploymentPreferences.Selection> preferences()
            throws ApiException {
        return preferences(Optional.empty());
    }

    /** Saves an explicit page edit before any publication is started. / 在任何发布开始前保存明确页面编辑。
     * @param selection captured mode and approval / 已抓取模式与审批
     * @return original observable metadata task / 原可观察元数据任务
     * @throws ApiException if admission fails / 准入失败时 */
    public TaskHandle<gold.debug.windowstolinux.mod.deployment.db.DeploymentPreferences.Selection> savePreferences(
            gold.debug.windowstolinux.mod.deployment.db.DeploymentPreferences.Selection selection) throws ApiException {
        return preferences(Optional.of(Objects.requireNonNull(selection)));
    }

    /** Performs one original preference read or explicit write. / 执行一次原偏好读取或明确写入。
     * @param requested optional explicit edit / 可选明确编辑
     * @return original metadata task / 原元数据任务
     * @throws ApiException if admission fails / 准入失败时 */
    private TaskHandle<gold.debug.windowstolinux.mod.deployment.db.DeploymentPreferences.Selection> preferences(
            Optional<gold.debug.windowstolinux.mod.deployment.db.DeploymentPreferences.Selection> requested) throws ApiException {
        return context.tasks().submit(new TaskRequest(SERVER_LIST_TASK, requested.isPresent()
                ? "Save explicit deployment page preference" : "Deployment page preferences (fills missing defaults)", List.of(), true), task -> {
            try {
                var repository = new gold.debug.windowstolinux.mod.deployment.db.DeploymentPreferences(context.db());
                if (requested.isPresent()) repository.save(task, requested.orElseThrow());
                var selection = requested.isPresent() ? requested.orElseThrow() : repository.read(task);
                return new OperationResult<>(Outcome.SUCCEEDED, Optional.of(selection), Optional.empty());
            } catch (ApiException failure) {
                var code = failure.problem().code();
                return new OperationResult<>(code == ProblemCode.UNKNOWN ? Outcome.UNKNOWN
                        : code == ProblemCode.CANCELLED ? Outcome.CANCELLED : Outcome.FAILED,
                        Optional.empty(), Optional.of(failure.problem()));
            } catch (java.sql.SQLException | IllegalArgumentException failure) {
                return new OperationResult<>(Outcome.FAILED, Optional.empty(), Optional.of(new Problem(
                        failure instanceof IllegalArgumentException ? ProblemCode.INVALID_INPUT : ProblemCode.IO_FAILURE,
                        "DEPLOYMENT_PREFERENCE_INVALID", "Stored deployment preferences could not be loaded safely", Map.of(), Optional.empty())));
            }
        });
    }

    /** Creates one immutable revision before publication admission and transfers its plaintext buffer. / 在发布准入前创建一项不可变修订并转交其明文缓冲区。
     * @param server selected target / 所选目标
     * @param reference reviewed business identifier and revision / 已审阅业务标识与修订
     * @param value one-shot plaintext input requested only by the admitted task / 仅由已准入任务请求的一次性明文输入
     * @return original secret task / 原秘密任务
     * @throws ApiException if admission fails / 准入失败时 */
    public TaskHandle<DeploymentSecretRevision> createSecret(ServerRef server, DeploymentSecretReference reference,
            SecretValueSource value) throws ApiException {
        return secrets.submitOwned(server, reference, "deployment-application",
                Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS), value);
    }

    /** Lists existing selectable business revisions in a separate read-only task. / 在独立只读任务中列出既有可选业务修订。
     * @return original query task / 原查询任务
     * @throws ApiException if admission fails / 准入失败时 */
    public TaskHandle<List<DeploymentSecretSelection>> secretChoices() throws ApiException {
        return context.tasks().submit(new TaskRequest(SECRET_LIST_TASK, "Deployment secret choices", List.of(), true),
                task -> {
                    try {
                        return new OperationResult<>(Outcome.SUCCEEDED,
                                Optional.of(revisions.listReferences(task, 1000)), Optional.empty());
                    } catch (ApiException failure) {
                        var code = failure.problem().code();
                        return new OperationResult<>(
                                code == ProblemCode.CANCELLED
                                        ? Outcome.CANCELLED
                                        : code == ProblemCode.UNKNOWN ? Outcome.UNKNOWN : Outcome.FAILED,
                                Optional.empty(), Optional.of(failure.problem()));
                    } catch (java.sql.SQLException failure) {
                        return new OperationResult<>(Outcome.FAILED, Optional.empty(),
                                Optional.of(new Problem(ProblemCode.IO_FAILURE, "DEPLOYMENT_SECRET_LIST_FAILED",
                                        "Secret references could not be read", Map.of(), Optional.empty())));
                    }
                });
    }

    /** Starts one read-only task for a complete bounded server list. / 为完整有界服务器列表启动一项只读任务。
     * @return original lookup task / 原查询任务
     * @throws ApiException if admission fails / 准入失败时 */
    public TaskHandle<List<ServerInfo>> servers() throws ApiException {
        return context.tasks().submit(new TaskRequest(SERVER_LIST_TASK, "Deployment servers", List.of(), true),
                task -> {
                    try {
                        var all = new ArrayList<ServerInfo>();
                        Optional<String> cursor = Optional.empty();
                        do {
                            if (task.cancellation().requested())
                                return new OperationResult<>(Outcome.CANCELLED, Optional.empty(),
                                        Optional.of(
                                                new Problem(ProblemCode.CANCELLED, "DEPLOYMENT_SERVER_LIST_CANCELLED",
                                                        "Server list was cancelled", Map.of(), Optional.empty())));
                            var page = context.servers().listConfigured(cursor, 200, task);
                            all.addAll(page.items());
                            cursor = page.nextCursor();
                        } while (cursor.isPresent() && all.size() < 10_000);
                        if (cursor.isPresent())
                            return new OperationResult<>(Outcome.FAILED, Optional.empty(),
                                    Optional.of(new Problem(ProblemCode.INVALID_INPUT, "DEPLOYMENT_SERVER_LIST_LIMIT",
                                            "Server list exceeds page limit", Map.of(), Optional.empty())));
                        if (task.cancellation().requested())
                            return new OperationResult<>(Outcome.CANCELLED, Optional.empty(),
                                    Optional.of(new Problem(ProblemCode.CANCELLED, "DEPLOYMENT_SERVER_LIST_CANCELLED",
                                            "Server list was cancelled", Map.of(), Optional.empty())));
                        return new OperationResult<>(Outcome.SUCCEEDED, Optional.of(List.copyOf(all)),
                                Optional.empty());
                    } catch (ApiException failure) {
                        var code = failure.problem().code();
                        return new OperationResult<>(
                                code == ProblemCode.CANCELLED
                                        ? Outcome.CANCELLED
                                        : code == ProblemCode.UNKNOWN ? Outcome.UNKNOWN : Outcome.FAILED,
                                Optional.empty(), Optional.of(failure.problem()));
                    }
                });
    }

    /** Returns persisted original publication snapshots for reopened views. / 为重开的视图返回持久原发布快照。
     * @return bounded current snapshots / 有界当前快照
     * @throws ApiException if reading fails / 读取失败时 */
    public List<TaskSnapshot> publications() throws ApiException {
        var all = new ArrayList<TaskSnapshot>();
        Optional<String> cursor = Optional.empty();
        do {
            var page = context.tasks().list(cursor, 200);
            page.items().stream().filter(item -> item.type().equals("deployment-publication")).forEach(all::add);
            cursor = page.nextCursor();
        } while (cursor.isPresent() && all.size() < 1_000);
        return List.copyOf(all);
    }

    /** Reads retained original secret tasks so reopened pages expose unresolved creation. / 读取保留的原秘密任务，使重开页面显示未解决创建。
     * @return bounded current secret tasks / 有界当前秘密任务
     * @throws ApiException if the original task list is unavailable / 原任务列表不可用时 */
    public List<TaskSnapshot> secretTasks() throws ApiException {
        var all = new ArrayList<TaskSnapshot>();
        Optional<String> cursor = Optional.empty();
        do {
            var page = context.tasks().list(cursor, 200);
            page.items().stream().filter(item -> item.type().equals(DeploymentSecretProvisionFlow.TASK_TYPE))
                    .forEach(all::add);
            cursor = page.nextCursor();
        } while (cursor.isPresent() && all.size() < 1_000);
        return List.copyOf(all);
    }

    /** Checks pure typed form values without accessing source or server capabilities. / 不访问来源或服务器能力，仅校验类型化表单值。
     * @param prompt original source-backed prompt / 原来源依据问题
     * @param answer edited values / 已编辑值 */
    public void validateReview(DeploymentStaticWorkflow.Prompt prompt, DeploymentStaticWorkflow.Answers answer) {
        workflow.validateReview(prompt, answer);
    }

    /** Admits one actual static publication while preserving an accepted task across view close. / 接纳一项实际静态发布，并使已接受任务跨视图关闭保留。
     * @param server exact selected server / 精确选定服务器
     * @param draft source limits / 来源限额
     * @param reviewer current owner review / 当前所属审阅
     * @return original publication task / 原发布任务
     * @throws ApiException if admission fails / 准入失败时 */
    public synchronized TaskHandle<Void> submit(ServerRef server, DeploymentStaticWorkflow.Draft draft,
            DeploymentStaticWorkflow.Reviewer reviewer) throws ApiException {
        return submit(server, draft, Optional.empty(), reviewer);
    }

    /** Admits the selected local or Git source on the original publication task. / 在原发布任务接纳选定本地或 Git 来源。
     * @param server exact selected server / 精确选定服务器
     * @param draft reviewed source name and limits / 经审阅来源名及限额
     * @param git optional explicit Git source / 可选明确 Git 来源
     * @param reviewer current owner review / 当前所属审阅
     * @return original publication task / 原发布任务
     * @throws ApiException if admission fails / 准入失败时 */
    public synchronized TaskHandle<Void> submit(ServerRef server, DeploymentStaticWorkflow.Draft draft,
            Optional<DeploymentStaticWorkflow.GitSource> git, DeploymentStaticWorkflow.Reviewer reviewer)
            throws ApiException {
        return submit(server, draft, git, reviewer, false);
    }

    /** Admits an explicit static or bounded assisted route without changing the original task type. / 准入明确的静态或有界辅助路线而不改变原任务类型。
     * @param server selected target / 所选目标
     * @param draft frozen source / 冻结来源
     * @param git optional pinned Git / 可选固定 Git
     * @param reviewer current owner reviewer / 当前所属审阅人
     * @param assisted explicitly selected assistant / 明确选择辅助模式
     * @return original publication task / 原发布任务
     * @throws ApiException if admission fails / 准入失败时 */
    public synchronized TaskHandle<Void> submit(ServerRef server, DeploymentStaticWorkflow.Draft draft,
            Optional<DeploymentStaticWorkflow.GitSource> git, DeploymentStaticWorkflow.Reviewer reviewer,
            boolean assisted) throws ApiException {
        return submit(server, draft, git, reviewer, assisted, "MANUAL_REVIEW");
    }

    /** Admits the captured page mode and actual approval preference on one original task. / 在一项原任务接纳已抓取页面模式与实际审批偏好。
     * @param server selected target / 所选目标
     * @param draft frozen source / 冻结来源
     * @param git optional pinned Git / 可选固定Git
     * @param reviewer current owner reviewer / 当前所属审阅人
     * @param assisted selected assistant mode / 所选辅助模式
     * @param approvalMode explicit captured policy / 明确已抓取策略
     * @return original publication task / 原发布任务
     * @throws ApiException if admission fails / 准入失败时 */
    public synchronized TaskHandle<Void> submit(ServerRef server, DeploymentStaticWorkflow.Draft draft,
            Optional<DeploymentStaticWorkflow.GitSource> git, DeploymentStaticWorkflow.Reviewer reviewer,
            boolean assisted, String approvalMode) throws ApiException {
        if (active != null && active.snapshot().state() != TaskState.ENDED)
            throw new ApiException(new Problem(ProblemCode.BUSY, "DEPLOYMENT_STATIC_TASK_ACTIVE",
                    "An original deployment task is already active", Map.of(), Optional.empty()));
        var handle = workflow.submit(server, draft, git, reviewer, assisted, approvalMode);
        active = handle;
        handle.completion().whenComplete((ignored, failure) -> {
            synchronized (DeploymentStaticPageService.this) {
                if (active == handle)
                    active = null;
            }
        });
        return handle;
    }

    /** Concludes only an original interrupted read-only query; it cannot publish. / 仅结束原中断的只读查询，不可能发布。
     * @param previous original task / 原任务
     * @param task original recovery / 原恢复
     * @return known read-only interruption or retained uncertainty / 已知只读中断或保留未知
     * @throws ApiException if original occupancy cannot be observed / 无法观察原占用时 */
    @Override
    public OperationResult<Void> reconcile(TaskSnapshot previous, TaskContext task) throws ApiException {
        if (!(SERVER_LIST_TASK.equals(previous.type()) || SECRET_LIST_TASK.equals(previous.type()))
                || !previous.id().equals(task.id()) || !context.tasks().resourcesReleased(task))
            return new OperationResult<>(Outcome.UNKNOWN, Optional.empty(),
                    Optional.of(new Problem(ProblemCode.UNKNOWN, "DEPLOYMENT_SERVER_LIST_RECOVERY_UNKNOWN",
                            "Original query needs review", Map.of(), Optional.empty())));
        return new OperationResult<>(Outcome.FAILED, Optional.empty(),
                Optional.of(new Problem(ProblemCode.IO_FAILURE, "DEPLOYMENT_SERVER_LIST_INTERRUPTED",
                        "Read-only server list was interrupted", Map.of(), Optional.empty())));
    }
}
