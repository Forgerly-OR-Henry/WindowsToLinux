package gold.debug.windowstolinux.mod.deployment.service.agent;

import java.nio.file.Path;
import java.util.*;

import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.server.model.ServerInfo;
import gold.debug.windowstolinux.appapi.task.model.TaskRequest;
import gold.debug.windowstolinux.appapi.task.model.TaskSnapshot;
import gold.debug.windowstolinux.appapi.task.recover.TaskRecovery;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;
import gold.debug.windowstolinux.appapi.task.result.Problem;
import gold.debug.windowstolinux.appapi.task.result.ProblemCode;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.appapi.task.run.TaskHandle;
import gold.debug.windowstolinux.mod.deployment.file.git.DeploymentGitRequest;
import gold.debug.windowstolinux.mod.deployment.service.core.DeploymentSourceSelectionService;

/** Provides agent-only page queries and original task admission. / 提供自主专用页面查询与原任务接纳。 */
public final class DeploymentAgentPageService implements TaskRecovery {
    /** Own short query and explicit inspection-metadata task type. / 专有短查询及显式检查元数据任务类型。 */
    public static final String SERVER_LIST_TASK = "deployment-agent-servers";

    /** Actual original owner capabilities. / 实际原所有者能力。 */
    private final ModContext context;

    /** Dedicated original task admission and recovery coordinator. / 专有原任务准入及恢复协调器。 */
    private final DeploymentAgentWorkflow workflow;

    /** Binds one activation and its own agent workflow. / 绑定一次启用及其专有自主工作流。
     * @param context active Mod / 活动 Mod
     * @param workflow original agent workflow / 原自主工作流 */
    public DeploymentAgentPageService(ModContext context, DeploymentAgentWorkflow workflow) {
        this.context = Objects.requireNonNull(context);
        this.workflow = Objects.requireNonNull(workflow);
    }

    /** Returns the original owned diagnostic reader without storage on the EDT. / 返回原所属诊断读取器，不在EDT访问存储。
     * @return original task feedback reader / 原任务反馈读取器 */
    public gold.debug.windowstolinux.mod.deployment.service.feedback.DeploymentDiagnostics feedback() {
        return new gold.debug.windowstolinux.mod.deployment.service.feedback.DeploymentDiagnostics(context);
    }

    /** Reads delivery only for the exact original successful host task. / 仅为精确原成功宿主任务读取交付。
     * @param originalTaskUUID actual completed original UUID / 实际已完成原UUID
     * @return actual bounded owned delivery query / 实际有界所属交付查询
     * @throws ApiException if original query admission fails / 原查询准入失败时 */
    public TaskHandle<Optional<gold.debug.windowstolinux.mod.deployment.api.DeploymentApplicationUsage>> delivery(UUID originalTaskUUID)
        throws ApiException {
        return new gold.debug.windowstolinux.mod.deployment.service.core.execution.DeploymentApplicationsService(context)
            .usageForCompletedTask(originalTaskUUID);
    }

    /** Opens one fixed actual host destination. / 打开一个固定实际宿主目的页。
     * @param target explicit finite destination / 明确有限目的页
     * @throws ApiException if original UI access is revoked / 原UI访问被撤销时 */
    public void openPlatform(gold.debug.windowstolinux.appapi.ui.page.PlatformPageKind target) throws ApiException {
        context.ui().openPlatform(target);
    }

    /** Returns the common original picker without standard-engine or project-write access. / 返回共用原选择器，不取得标准引擎或项目写入访问。
     * @return same-owner short metadata service / 同所属短元数据服务 */
    public DeploymentSourceSelectionService sources() {
        return new DeploymentSourceSelectionService(context);
    }

    /** Loads the owned page selection through the actual worker query. / 经实际工作线程查询加载所属页面选择。
     * @return original preference query / 原偏好查询
     * @throws ApiException if original query admission fails / 原查询准入失败时 */
    public TaskHandle<gold.debug.windowstolinux.mod.deployment.db.DeploymentPreferences.Selection> preferences()
        throws ApiException {
        return query("Agent page preferences (fills missing defaults)", List.of(),
            task -> new gold.debug.windowstolinux.mod.deployment.db.DeploymentPreferences(context.db()).read(task));
    }

    /** Saves a captured approval edit on its own original metadata task. / 在自身原元数据任务保存已抓取审批编辑。
     * @param selection captured page mode and policy / 已抓取页面模式与策略
     * @return original observable metadata task / 原可观察元数据任务
     * @throws ApiException if admission fails / 准入失败时 */
    public TaskHandle<gold.debug.windowstolinux.mod.deployment.db.DeploymentPreferences.Selection> savePreferences(
        gold.debug.windowstolinux.mod.deployment.db.DeploymentPreferences.Selection selection) throws ApiException {
        Objects.requireNonNull(selection);
        return query("Save explicit agent page preference", List.of(), task -> {
            new gold.debug.windowstolinux.mod.deployment.db.DeploymentPreferences(context.db()).save(task, selection);
            return selection;
        });
    }

    /** Loads configured targets through a bounded read-only original query. / 经有界原只读查询加载已配置目标。
     * @return observable query / 可观察查询
     * @throws ApiException when admission fails / 准入失败时 */
    public TaskHandle<List<ServerInfo>> servers() throws ApiException {
        return context.tasks().submit(new TaskRequest(SERVER_LIST_TASK, "Agent deployment servers", List.of(), true),
            task -> {
                try {
                    var all = new ArrayList<ServerInfo>();
                    Optional<String> cursor = Optional.empty();
                    do {
                        if (task.cancelToken().requested())
                            return new OperationResult<>(TaskResult.CANCELLED, Optional.empty(), Optional
                                .of(problem(ProblemCode.CANCELLED, "DEPLOYMENT_AGENT_SERVER_LIST_CANCELLED")));
                        var page = context.servers().listConfigured(cursor, 200, task);
                        all.addAll(page.items());
                        cursor = page.nextCursor();
                    } while (cursor.isPresent() && all.size() < 10000);
                    if (cursor.isPresent())
                        return new OperationResult<>(TaskResult.FAILED, Optional.empty(), Optional
                            .of(problem(ProblemCode.INVALID_INPUT, "DEPLOYMENT_AGENT_SERVER_LIST_LIMIT")));
                    return new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(List.copyOf(all)),
                        Optional.empty());
                } catch (ApiException failure) {
                    var code = failure.problem().code();
                    var result = code == ProblemCode.CANCELLED
                        ? TaskResult.CANCELLED
                        : code == ProblemCode.UNKNOWN ? TaskResult.UNKNOWN : TaskResult.FAILED;
                    return new OperationResult<>(result, Optional.empty(), Optional.of(failure.problem()));
                }
            });
    }

    /** Lists retained agent tasks so reopening exposes unresolved work. / 列出留存自主任务，使重开可见未决工作。
     * @return bounded task snapshots / 有界任务快照
     * @throws ApiException on unavailable task listing / 任务列表不可用时 */
    public List<TaskSnapshot> tasks() throws ApiException {
        var all = new ArrayList<TaskSnapshot>();
        Optional<String> cursor = Optional.empty();
        do {
            var page = context.tasks().list(cursor, 200);
            page.items().stream().filter(item -> item.type().equals(DeploymentAgentWorkflow.TASK_TYPE))
                .forEach(all::add);
            cursor = page.nextCursor();
        } while (cursor.isPresent() && all.size() < 1000);
        return List.copyOf(all);
    }

    /** Requests control on this activation's original live worker. / 请求控制此启用代次的原活动工作线程。
     * @param taskId selected original identity / 所选原身份
     * @param command explicit user request / 显式用户请求 */
    public void control(UUID taskId, DeploymentAgentControl.CommandAction command) {
        workflow.control(taskId, command);
    }

    /** Reads actual Mod-local control state. / 读取实际Mod内部控制状态。
     * @param taskId selected original identity / 所选原身份
     * @return actual live state or absence / 实际活动状态或不存在 */
    public Optional<DeploymentAgentControl.State> controlState(UUID taskId) {
        return workflow.controlState(taskId);
    }

    /** Reads existing ended and retained business history without recreating tasks. / 读取既有结束及留存业务历史，不重建任务。
     * @return actual bounded query / 实际有界查询
     * @throws ApiException if admission fails / 准入失败时 */
    public TaskHandle<List<DeploymentAgentHistory.Entry>> history() throws ApiException {
        return query("Agent original history", List.of(), task -> new DeploymentAgentHistory(context).recent(task));
    }

    /** Reads exact events for a selected original UUID under a new actual query. / 在新实际查询下读取所选原UUID的精确事件。
     * @param selected original business identity / 原业务身份
     * @return actual detail query / 实际详情查询
     * @throws ApiException if admission fails / 准入失败时 */
    public TaskHandle<DeploymentAgentHistory.Detail> events(UUID selected) throws ApiException {
        return query("Agent original events", List.of(),
            task -> new DeploymentAgentHistory(context).detail(task, selected));
    }

    /** Records an explicit inspection request; no new task bypasses an UNKNOWN server lock. / 记录显式检查请求，新任务不绕过UNKNOWN服务器锁。
     * @param selected owner-listed retained original UUID / 所有者列表留存原UUID
     * @return durable request identity / 持久请求身份
     * @throws ApiException if admission fails / 准入失败时 */
    public TaskHandle<Long> requestInspection(UUID selected) throws ApiException {
        return query("Request original agent inspection (writes metadata)", List.of(),
            task -> new DeploymentAgentHistory(context).request(task, selected));
    }

    /** Inspects only an ended original candidate using current profile and fingerprint authorization. / 使用当前资料及指纹授权，仅检查已结束原候选。
     * @param selected original business entry / 原业务项
     * @return actual readonly facts or conflict / 实际只读事实或冲突
     * @throws ApiException if original work remains retained or admission fails / 原工作仍留存或准入失败时 */
    public TaskHandle<Map<String, String>> inspect(DeploymentAgentHistory.Entry selected) throws ApiException {
        if (tasks().stream().anyMatch(task -> task.id().equals(selected.id())))
            throw new ApiException(problem(ProblemCode.ACCESS_DENIED, "DEPLOYMENT_AGENT_INSPECT_ORIGINAL_REQUIRED"));
        return query("Inspect ended original agent candidate", List.of(selected.server().id()), task -> {
            var history = new DeploymentAgentHistory(context);
            var actual = history.detail(task, selected.id());
            if (!actual.entry().equals(selected)
                || !Set.of("FAILED", "CANCELLED", "SUCCEEDED", "INTERRUPTED").contains(actual.entry().state()))
                throw new IllegalArgumentException("Original terminal inspection identity changed");
            return history.inspect(task, actual, false);
        });
    }

    /** Admits a bounded owner query or explicit metadata write with known error classification. / 接纳有界所有者查询或显式元数据写入，并分类已知错误。
     * @param title visible actual operation / 可见实际操作
     * @param servers exact occupied targets / 精确占用目标
     * @param work actual owner work / 实际所有者工作
     * @param <T> bounded returned value / 有界返回值
     * @return actual original query handle / 实际原查询句柄
     * @throws ApiException if admission fails / 准入失败时 */
    private <T> TaskHandle<T> query(String title, List<String> servers, Query<T> work) throws ApiException {
        return context.tasks().submit(new TaskRequest(SERVER_LIST_TASK, title, servers, true), task -> {
            try {
                return new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(work.run(task)), Optional.empty());
            } catch (ApiException failure) {
                if (!context.tasks().resourcesReleased(task))
                    return new OperationResult<>(TaskResult.UNKNOWN, Optional.empty(),
                        Optional.of(new Problem(ProblemCode.UNKNOWN, "DEPLOYMENT_AGENT_HISTORY_RESOURCES_UNKNOWN",
                            "Original query resource closure is unconfirmed",
                            Map.of("causeEvent", failure.problem().eventCode()),
                            failure.problem().diagnosticId())));
                return new OperationResult<>(failure.problem().code() == ProblemCode.UNKNOWN
                    ? TaskResult.UNKNOWN
                    : failure.problem().code() == ProblemCode.CANCELLED ? TaskResult.CANCELLED : TaskResult.FAILED,
                    Optional.empty(), Optional.of(failure.problem()));
            } catch (Exception failure) {
                boolean released = context.tasks().resourcesReleased(task);
                return new OperationResult<>(released ? TaskResult.FAILED : TaskResult.UNKNOWN, Optional.empty(),
                    Optional.of(problem(released ? ProblemCode.IO_FAILURE : ProblemCode.UNKNOWN,
                        "DEPLOYMENT_AGENT_HISTORY_UNCONFIRMED")));
            }
        });
    }

    /** Executes one current owner operation without fabricating an old context. / 执行当前所有者操作，不伪造旧上下文。
     * @param <T> actual returned value / 实际返回值 */
    @FunctionalInterface
    private interface Query<T> {
        /** Runs within the actual admitted callback. / 在实际已接纳回调内运行。
         * @param task actual context / 实际上下文
         * @return bounded result / 有界结果
         * @throws Exception if facts are unconfirmed / 事实未确证时 */
        T run(TaskContext task) throws Exception;
    }

    /** Starts one original local agent task. / 启动一项原本地自主任务。
     * @param server reviewed target / 经审阅目标
     * @param request bounded agent policy / 有界自主策略
     * @param location completed short picker metadata / 已完成短选择器元数据
     * @return original observable task / 原可观察任务
     * @throws ApiException on failed admission / 准入失败时 */
    public TaskHandle<DeploymentAgentDeliveryPort.Published> submitLocal(ServerId server,
                                                                         DeploymentAgentWorkflow.Request request,
                                                                         DeploymentSourceSelectionService.LocalLocation location) throws ApiException {
        return workflow.submitLocal(server, request, location);
    }

    /** Starts one original pinned Git agent task. / 启动一项原固定 Git 自主任务。
     * @param server reviewed target / 经审阅目标
     * @param request bounded policy / 有界策略
     * @param git credential-free source / 不含凭据来源
     * @param executable explicit native Git binary / 明确原生 Git 程序
     * @param projectName reviewed saved project name / 经审阅已存项目名称
     * @return original observable task / 原可观察任务
     * @throws ApiException on failed admission / 准入失败时 */
    public TaskHandle<DeploymentAgentDeliveryPort.Published> submitGit(ServerId server,
                                                                       DeploymentAgentWorkflow.Request request, DeploymentGitRequest git, Path executable, String projectName)
        throws ApiException {
        return workflow.submitGit(server, request, git, executable, projectName);
    }

    /** Ends an interrupted short query or metadata request only after actual resources are closed; original inspection remains durable. / 仅实际资源已关闭后结束中断短查询或元数据请求；原检查请求保持持久。
     * @param previous retained query / 留存查询
     * @param task original recovery context / 原恢复上下文
     * @return known interruption or uncertainty / 已知中断或未知
     * @throws ApiException when occupancy cannot be observed / 占用不可观察时 */
    @Override
    public OperationResult<Void> reconcile(TaskSnapshot previous, TaskContext task) throws ApiException {
        if (!SERVER_LIST_TASK.equals(previous.type()) || !previous.id().equals(task.id())
            || !context.tasks().resourcesReleased(task))
            return new OperationResult<>(TaskResult.UNKNOWN, Optional.empty(),
                Optional.of(problem(ProblemCode.UNKNOWN, "DEPLOYMENT_AGENT_SERVER_LIST_UNKNOWN")));
        return new OperationResult<>(TaskResult.FAILED, Optional.empty(),
            Optional.of(problem(ProblemCode.IO_FAILURE, "DEPLOYMENT_AGENT_SERVER_LIST_INTERRUPTED")));
    }

    /** Creates one bounded public query problem. / 创建一项有界公开查询问题。
     * @param code public category / 公开类别
     * @param event fixed event / 固定事件
     * @return problem / 问题 */
    private static Problem problem(ProblemCode code, String event) {
        return new Problem(code, event, "Agent target query requires review", Map.of(), Optional.empty());
    }
}
