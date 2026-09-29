package gold.debug.windowstolinux.mod.deployment.service.agent;

import java.nio.file.Path;
import java.util.*;

import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.server.*;
import gold.debug.windowstolinux.appapi.task.*;
import gold.debug.windowstolinux.mod.deployment.source.git.DeploymentGitRequest;

/** Provides agent-only page queries and original task admission. / 提供自主专用页面查询与原任务接纳。 */
public final class DeploymentAgentPageService implements TaskRecovery {
    /** Own read-only query type, registered alongside the agent workflow. / 与自主工作流一同登记的专有只读查询类型。 */
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
                            if (task.cancellation().requested())
                                return new OperationResult<>(Outcome.CANCELLED, Optional.empty(), Optional
                                        .of(problem(ProblemCode.CANCELLED, "DEPLOYMENT_AGENT_SERVER_LIST_CANCELLED")));
                            var page = context.servers().listConfigured(cursor, 200, task);
                            all.addAll(page.items());
                            cursor = page.nextCursor();
                        } while (cursor.isPresent() && all.size() < 10000);
                        if (cursor.isPresent())
                            return new OperationResult<>(Outcome.FAILED, Optional.empty(), Optional
                                    .of(problem(ProblemCode.INVALID_INPUT, "DEPLOYMENT_AGENT_SERVER_LIST_LIMIT")));
                        return new OperationResult<>(Outcome.SUCCEEDED, Optional.of(List.copyOf(all)),
                                Optional.empty());
                    } catch (ApiException failure) {
                        var code = failure.problem().code();
                        var result = code == ProblemCode.CANCELLED
                                ? Outcome.CANCELLED
                                : code == ProblemCode.UNKNOWN ? Outcome.UNKNOWN : Outcome.FAILED;
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

    /** Starts one original local agent task. / 启动一项原本地自主任务。
     * @param server reviewed target / 经审阅目标
     * @param request bounded agent policy / 有界自主策略
     * @return original observable task / 原可观察任务
     * @throws ApiException on failed admission / 准入失败时 */
    public TaskHandle<DeploymentAgentDeliveryPort.Published> submitLocal(ServerRef server,
            DeploymentAgentWorkflow.Request request) throws ApiException {
        return workflow.submitLocal(server, request);
    }

    /** Starts one original pinned Git agent task. / 启动一项原固定 Git 自主任务。
     * @param server reviewed target / 经审阅目标
     * @param request bounded policy / 有界策略
     * @param git credential-free source / 不含凭据来源
     * @param executable explicit native Git binary / 明确原生 Git 程序
     * @param projectName reviewed saved project name / 经审阅已存项目名称
     * @return original observable task / 原可观察任务
     * @throws ApiException on failed admission / 准入失败时 */
    public TaskHandle<DeploymentAgentDeliveryPort.Published> submitGit(ServerRef server,
            DeploymentAgentWorkflow.Request request, DeploymentGitRequest git, Path executable, String projectName)
            throws ApiException {
        return workflow.submitGit(server, request, git, executable, projectName);
    }

    /** Closes only an interrupted original read-only query. / 仅结束被中断的原只读查询。
     * @param previous retained query / 留存查询
     * @param task original recovery context / 原恢复上下文
     * @return known interruption or uncertainty / 已知中断或未知
     * @throws ApiException when occupancy cannot be observed / 占用不可观察时 */
    @Override
    public OperationResult<Void> reconcile(TaskSnapshot previous, TaskContext task) throws ApiException {
        if (!SERVER_LIST_TASK.equals(previous.type()) || !previous.id().equals(task.id())
                || !context.tasks().resourcesReleased(task))
            return new OperationResult<>(Outcome.UNKNOWN, Optional.empty(),
                    Optional.of(problem(ProblemCode.UNKNOWN, "DEPLOYMENT_AGENT_SERVER_LIST_UNKNOWN")));
        return new OperationResult<>(Outcome.FAILED, Optional.empty(),
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
