package gold.debug.windowstolinux.mod.deployment.service.standard;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.server.ServerInfo;
import gold.debug.windowstolinux.appapi.server.ServerRef;
import gold.debug.windowstolinux.appapi.task.*;

/** Binds one page's read-only server lookup and original publication task without owning its Swing view. / 绑定页面只读服务器查询及原发布任务，但不持有 Swing 视图。 */
public final class DeploymentStaticPageService implements TaskRecovery {
    /** Read-only short task type. / 只读短任务类型。 */
    public static final String SERVER_LIST_TASK = "deployment-server-list";

    /** Original platform capabilities. / 原平台能力。 */
    private final ModContext context;

    /** Actual original-task static workflow. / 实际原任务静态流程。 */
    private final DeploymentStaticWorkflow workflow;

    /** Current accepted publication task, independent of an open page. / 当前已接纳发布任务，独立于开放页面。 */
    private TaskHandle<Void> active;

    /** Binds current Mod services. / 绑定当前 Mod 服务。
     * @param context original Mod context / 原 Mod 上下文
     * @param workflow original static workflow / 原静态流程 */
    public DeploymentStaticPageService(ModContext context, DeploymentStaticWorkflow workflow) {
        this.context = Objects.requireNonNull(context);
        this.workflow = Objects.requireNonNull(workflow);
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
        if (active != null && active.snapshot().state() != TaskState.ENDED)
            throw new ApiException(new Problem(ProblemCode.BUSY, "DEPLOYMENT_STATIC_TASK_ACTIVE",
                    "An original deployment task is already active", Map.of(), Optional.empty()));
        var handle = workflow.submit(server, draft, reviewer);
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
        if (!SERVER_LIST_TASK.equals(previous.type()) || !previous.id().equals(task.id())
                || !context.tasks().resourcesReleased(task))
            return new OperationResult<>(Outcome.UNKNOWN, Optional.empty(),
                    Optional.of(new Problem(ProblemCode.UNKNOWN, "DEPLOYMENT_SERVER_LIST_RECOVERY_UNKNOWN",
                            "Original query needs review", Map.of(), Optional.empty())));
        return new OperationResult<>(Outcome.FAILED, Optional.empty(),
                Optional.of(new Problem(ProblemCode.IO_FAILURE, "DEPLOYMENT_SERVER_LIST_INTERRUPTED",
                        "Read-only server list was interrupted", Map.of(), Optional.empty())));
    }
}
