package gold.debug.windowstolinux.mod.deployment.service.standard.shared;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.server.model.ServerInfo;
import gold.debug.windowstolinux.appapi.task.model.TaskRequest;
import gold.debug.windowstolinux.appapi.task.model.TaskSnapshot;
import gold.debug.windowstolinux.appapi.task.model.TaskState;
import gold.debug.windowstolinux.appapi.task.recover.TaskRecovery;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;
import gold.debug.windowstolinux.appapi.task.result.Problem;
import gold.debug.windowstolinux.appapi.task.result.ProblemCode;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.appapi.task.run.TaskHandle;
import gold.debug.windowstolinux.mod.deployment.config.DeploymentConfig;
import gold.debug.windowstolinux.mod.deployment.config.DeploymentConfigLoader;
import gold.debug.windowstolinux.mod.deployment.db.resource.DeploymentSecretStore;
import gold.debug.windowstolinux.mod.deployment.secret.DeploymentSecretProvisionFlow;
import gold.debug.windowstolinux.mod.deployment.secret.SecretValueSource;
import gold.debug.windowstolinux.mod.deployment.service.core.model.secret.DeploymentSecretRef;
import gold.debug.windowstolinux.mod.deployment.service.core.model.secret.DeploymentSecretRevision;
import gold.debug.windowstolinux.mod.deployment.service.core.model.secret.DeploymentSecretSelection;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.contract.DeploymentStandardInputs;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.source.DeploymentStandardSourceService;

/** Binds one page's read-only server lookup and original publication task without owning its Swing view. / 绑定页面只读服务器查询及原发布任务，但不持有 Swing 视图。 */
public final class DeploymentStandardPageService implements TaskRecovery {
    /** Read-only short task type. / 只读短任务类型。 */
    public static final String SERVER_LIST_TASK = "deployment-server-list";

    /** Read-only secret choice task type. / 只读秘密选项任务类型。 */
    public static final String SECRET_LIST_TASK = "deployment-secret-list";

    /** Original platform capabilities. / 原平台能力。 */
    private final ModContext context;

    /** Same immutable configuration used by the page and its queries. / 页面及其查询使用的同一不可变配置。 */
    private final DeploymentConfig configuration;

    /** Actual original-task static workflow. / 实际原任务静态流程。 */
    private final DeploymentStandardInputs.Entry staticMode;

    /** Independent assisted entry captured by the page service. / 页面服务持有的独立辅助入口。 */
    private final DeploymentStandardInputs.Entry assistedMode;

    /** Original short source and project tasks. / 原短来源及项目任务。 */
    private final DeploymentStandardSourceService sources;

    /** Original task progress and bounded owned log reader. / 原任务进度及有界所属日志读取器。 */
    private final gold.debug.windowstolinux.mod.deployment.service.feedback.DeploymentDiagnostics feedback;

    /** Existing original-task secret creation. / 既有原任务秘密创建。 */
    private final DeploymentSecretProvisionFlow secrets;

    /** Business revision metadata without plaintext. / 不含明文的业务修订元数据。 */
    private final DeploymentSecretStore revisions;

    /** Current accepted publication task, independent of an open page. / 当前已接纳发布任务，独立于开放页面。 */
    private TaskHandle<Void> active;

    public void requestApprovalPolicyChange(java.util.UUID original, String mode, long expectedRevision) throws ApiException {
        gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentApprovalPolicies.request(context, original, mode, expectedRevision);
    }

    public gold.debug.windowstolinux.mod.deployment.service.core.DeploymentApprovalPolicyState approvalPolicyState(java.util.UUID original) {
        return gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentApprovalPolicies.state(context, original);
    }

    /** Binds current Mod services. / 绑定当前 Mod 服务。
     * @param context original Mod context / 原 Mod 上下文
     * @param staticMode independent static entry / 独立静态入口
     * @param assistedMode independent assisted entry / 独立辅助入口 */
    public DeploymentStandardPageService(ModContext context, DeploymentStandardInputs.Entry staticMode, DeploymentStandardInputs.Entry assistedMode) {
        this(context, staticMode, assistedMode, DeploymentConfigLoader.current());
    }

    /** Binds explicit fixed settings without replacing captured task inputs. / 绑定明确固定配置，不替换已捕获任务输入。
     * @param context original capabilities / 原能力
     * @param staticMode static entry / 静态入口
     * @param assistedMode assisted entry / 辅助入口
     * @param configuration immutable activation settings / 不可变启用配置 */
    public DeploymentStandardPageService(ModContext context, DeploymentStandardInputs.Entry staticMode,
                                         DeploymentStandardInputs.Entry assistedMode, DeploymentConfig configuration) {
        this.context = Objects.requireNonNull(context);
        this.configuration = Objects.requireNonNull(configuration);
        this.staticMode = Objects.requireNonNull(staticMode);
        this.assistedMode = Objects.requireNonNull(assistedMode);
        this.sources = new DeploymentStandardSourceService(context);
        this.feedback = new gold.debug.windowstolinux.mod.deployment.service.feedback.DeploymentDiagnostics(context);
        this.secrets = new DeploymentSecretProvisionFlow(context);
        this.revisions = new DeploymentSecretStore(context.db());
    }

    /** Returns fixed defaults for the owning view. / 返回所属视图的固定默认值。
     * @return immutable activation configuration / 不可变启用配置 */
    public DeploymentConfig configuration() {
        return configuration;
    }

    /** Returns the same activation's original source task service for page use and recovery registration. / 返回同一启用的原来源任务服务，用于页面及恢复登记。
     * @return original private source service / 原私有来源服务 */
    public DeploymentStandardSourceService sources() {
        return sources;
    }

    /** Returns only this activation's bounded original-task feedback. / 仅返回本次启用的有界原任务反馈。
     * @return original feedback service / 原反馈服务 */
    public gold.debug.windowstolinux.mod.deployment.service.feedback.DeploymentDiagnostics feedback() {
        return feedback;
    }

    /** Reads delivery only for the exact successful original publication identity. / 仅为精确成功原发布身份读取交付。
     * @param originalTaskUUID actual completed platform task UUID / 实际已完成平台任务 UUID
     * @return original owned usage query without another deployment / 不另部署的原所属用法查询
     * @throws ApiException if current original query admission is refused / 当前原查询准入被拒绝时 */
    public TaskHandle<Optional<gold.debug.windowstolinux.mod.deployment.api.DeploymentApplicationUsage>> delivery(
        java.util.UUID originalTaskUUID) throws ApiException {
        return new gold.debug.windowstolinux.mod.deployment.service.core.application.DeploymentApplicationsService(context)
            .usageForCompletedTask(originalTaskUUID);
    }

    /** Opens a fixed host destination through the public owner UI boundary. / 经公开所属 UI 边界打开固定宿主目标。
     * @param target explicit platform destination / 明确平台目标
     * @throws ApiException if current owner UI access is refused / 当前所属 UI 访问被拒绝时 */
    public void openPlatform(gold.debug.windowstolinux.appapi.ui.page.PlatformPageKind target) throws ApiException {
        context.ui().openPlatform(target);
    }

    /** Loads the owned page selection off the EDT, filling only missing defaults. / 在EDT外加载所属页面选择，仅补缺失默认值。
     * @return original preference query / 原偏好查询
     * @throws ApiException if original query admission fails / 原查询准入失败时 */
    public TaskHandle<gold.debug.windowstolinux.mod.deployment.db.setting.DeploymentPreferenceStore.Selection> preferences()
        throws ApiException {
        return preferences(Optional.empty());
    }

    /** Saves an explicit page edit before any publication is started. / 在任何发布开始前保存明确页面编辑。
     * @param selection captured mode and approval / 已抓取模式与审批
     * @return original observable metadata task / 原可观察元数据任务
     * @throws ApiException if admission fails / 准入失败时 */
    public TaskHandle<gold.debug.windowstolinux.mod.deployment.db.setting.DeploymentPreferenceStore.Selection> savePreferences(
        gold.debug.windowstolinux.mod.deployment.db.setting.DeploymentPreferenceStore.Selection selection) throws ApiException {
        return preferences(Optional.of(Objects.requireNonNull(selection)));
    }

    /** Performs one original preference read or explicit write. / 执行一次原偏好读取或明确写入。
     * @param requested optional explicit edit / 可选明确编辑
     * @return original metadata task / 原元数据任务
     * @throws ApiException if admission fails / 准入失败时 */
    private TaskHandle<gold.debug.windowstolinux.mod.deployment.db.setting.DeploymentPreferenceStore.Selection> preferences(
        Optional<gold.debug.windowstolinux.mod.deployment.db.setting.DeploymentPreferenceStore.Selection> requested) throws ApiException {
        return context.tasks().submit(new TaskRequest(SERVER_LIST_TASK, requested.isPresent()
            ? "Save explicit deployment page preference" : "Deployment page preferences (fills missing defaults)", List.of(), true), task -> {
            try {
                var repository = new gold.debug.windowstolinux.mod.deployment.db.setting.DeploymentPreferenceStore(context.db());
                if (requested.isPresent()) repository.save(task, requested.orElseThrow());
                var selection = requested.isPresent() ? requested.orElseThrow() : repository.read(task);
                return new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(selection), Optional.empty());
            } catch (ApiException failure) {
                var code = failure.problem().code();
                return new OperationResult<>(code == ProblemCode.UNKNOWN ? TaskResult.UNKNOWN
                    : code == ProblemCode.CANCELLED ? TaskResult.CANCELLED : TaskResult.FAILED,
                    Optional.empty(), Optional.of(failure.problem()));
            } catch (java.sql.SQLException | IllegalArgumentException failure) {
                return new OperationResult<>(TaskResult.FAILED, Optional.empty(), Optional.of(new Problem(
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
    public TaskHandle<DeploymentSecretRevision> createSecret(ServerId server, DeploymentSecretRef reference,
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
                    return new OperationResult<>(TaskResult.SUCCEEDED,
                        Optional.of(revisions.listReferences(task, 1000)), Optional.empty());
                } catch (ApiException failure) {
                    var code = failure.problem().code();
                    return new OperationResult<>(
                        code == ProblemCode.CANCELLED
                            ? TaskResult.CANCELLED
                            : code == ProblemCode.UNKNOWN ? TaskResult.UNKNOWN : TaskResult.FAILED,
                        Optional.empty(), Optional.of(failure.problem()));
                } catch (java.sql.SQLException failure) {
                    return new OperationResult<>(TaskResult.FAILED, Optional.empty(),
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
                        if (task.cancelToken().requested())
                            return new OperationResult<>(TaskResult.CANCELLED, Optional.empty(),
                                Optional.of(
                                    new Problem(ProblemCode.CANCELLED, "DEPLOYMENT_SERVER_LIST_CANCELLED",
                                        "Server list was cancelled", Map.of(), Optional.empty())));
                        var page = context.servers().listConfigured(cursor, 200, task);
                        all.addAll(page.items());
                        cursor = page.nextCursor();
                    } while (cursor.isPresent() && all.size() < 10_000);
                    if (cursor.isPresent())
                        return new OperationResult<>(TaskResult.FAILED, Optional.empty(),
                            Optional.of(new Problem(ProblemCode.INVALID_INPUT, "DEPLOYMENT_SERVER_LIST_LIMIT",
                                "Server list exceeds page limit", Map.of(), Optional.empty())));
                    if (task.cancelToken().requested())
                        return new OperationResult<>(TaskResult.CANCELLED, Optional.empty(),
                            Optional.of(new Problem(ProblemCode.CANCELLED, "DEPLOYMENT_SERVER_LIST_CANCELLED",
                                "Server list was cancelled", Map.of(), Optional.empty())));
                    return new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(List.copyOf(all)),
                        Optional.empty());
                } catch (ApiException failure) {
                    var code = failure.problem().code();
                    return new OperationResult<>(
                        code == ProblemCode.CANCELLED
                            ? TaskResult.CANCELLED
                            : code == ProblemCode.UNKNOWN ? TaskResult.UNKNOWN : TaskResult.FAILED,
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
            var page = context.tasks().list(cursor, configuration.view().pageSize());
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
            var page = context.tasks().list(cursor, configuration.view().pageSize());
            page.items().stream().filter(item -> item.type().equals(DeploymentSecretProvisionFlow.TASK_TYPE))
                .forEach(all::add);
            cursor = page.nextCursor();
        } while (cursor.isPresent() && all.size() < 1_000);
        return List.copyOf(all);
    }

    /** Checks pure typed form values without accessing source or server capabilities. / 不访问来源或服务器能力，仅校验类型化表单值。
     * @param prompt original source-backed prompt / 原来源依据问题
     * @param answer edited values / 已编辑值 */
    public void validateReview(DeploymentStandardInputs.Prompt prompt, DeploymentStandardInputs.Answers answer) {
        staticMode.validateReview(prompt, answer);
    }

    /** Admits one actual static publication while preserving an accepted task across view close. / 接纳一项实际静态发布，并使已接受任务跨视图关闭保留。
     * @param server exact selected server / 精确选定服务器
     * @param draft source limits / 来源限额
     * @param reviewer current owner review / 当前所属审阅
     * @return original publication task / 原发布任务
     * @throws ApiException if admission fails / 准入失败时 */
    public synchronized TaskHandle<Void> submit(ServerId server, DeploymentStandardInputs.Draft draft,
                                                DeploymentStandardInputs.Reviewer reviewer) throws ApiException {
        return submit(server, draft, Optional.empty(), reviewer);
    }

    /** Admits the selected local or Git source on the original publication task. / 在原发布任务接纳选定本地或 Git 来源。
     * @param server exact selected server / 精确选定服务器
     * @param draft reviewed source name and limits / 经审阅来源名及限额
     * @param git optional explicit Git source / 可选明确 Git 来源
     * @param reviewer current owner review / 当前所属审阅
     * @return original publication task / 原发布任务
     * @throws ApiException if admission fails / 准入失败时 */
    public synchronized TaskHandle<Void> submit(ServerId server, DeploymentStandardInputs.Draft draft,
                                                Optional<DeploymentStandardInputs.GitSource> git, DeploymentStandardInputs.Reviewer reviewer)
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
    public synchronized TaskHandle<Void> submit(ServerId server, DeploymentStandardInputs.Draft draft,
                                                Optional<DeploymentStandardInputs.GitSource> git, DeploymentStandardInputs.Reviewer reviewer,
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
    public synchronized TaskHandle<Void> submit(ServerId server, DeploymentStandardInputs.Draft draft,
                                                Optional<DeploymentStandardInputs.GitSource> git, DeploymentStandardInputs.Reviewer reviewer,
                                                boolean assisted, String approvalMode) throws ApiException {
        return submit(server, draft, git, reviewer, assisted, approvalMode, true);
    }

    /** Captures both deployment policies before admitting the actual original root. / 准入实际原根前抓取部署两项策略。
     * @param server selected original target / 所选原目标
     * @param draft frozen source choices / 冻结来源选择
     * @param git optional pinned source / 可选固定来源
     * @param reviewer actual original reviewer / 实际原审阅人
     * @param assisted selected assisted execution / 所选辅助执行
     * @param approvalMode frozen approval strategy / 冻结审批策略
     * @param rememberAuthorization frozen rememberAuthorization repetition / 冻结授权重复规则
     * @return actual original handle / 实际原句柄
     * @throws ApiException if original admission fails / 原准入失败时 */
    public synchronized TaskHandle<Void> submit(ServerId server, DeploymentStandardInputs.Draft draft,
                                                Optional<DeploymentStandardInputs.GitSource> git, DeploymentStandardInputs.Reviewer reviewer,
                                                boolean assisted, String approvalMode, boolean rememberAuthorization) throws ApiException {
        if (active != null && active.snapshot().state() != TaskState.ENDED)
            throw new ApiException(new Problem(ProblemCode.BUSY, "DEPLOYMENT_STATIC_TASK_ACTIVE",
                "An original deployment task is already active", Map.of(), Optional.empty()));
        var handle = (assisted ? assistedMode : staticMode).submit(server, draft, git, reviewer, approvalMode, rememberAuthorization);
        active = handle;
        handle.completion().whenComplete((ignored, failure) -> {
            synchronized (DeploymentStandardPageService.this) {
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
            return new OperationResult<>(TaskResult.UNKNOWN, Optional.empty(),
                Optional.of(new Problem(ProblemCode.UNKNOWN, "DEPLOYMENT_SERVER_LIST_RECOVERY_UNKNOWN",
                    "Original query needs review", Map.of(), Optional.empty())));
        return new OperationResult<>(TaskResult.FAILED, Optional.empty(),
            Optional.of(new Problem(ProblemCode.IO_FAILURE, "DEPLOYMENT_SERVER_LIST_INTERRUPTED",
                "Read-only server list was interrupted", Map.of(), Optional.empty())));
    }
}
