package gold.debug.windowstolinux.appapi.task.action;

import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;

/**
 * Host-owned review and input bound to the accepted task. / 绑定已接纳任务的宿主审核及输入交互。
 */
public interface InteractionService {

    /** Changes only a live original grant at the owning worker's settled boundary. / 仅在所属工作线程的结清边界变更活动原许可。
     * Already presented questions and dispatched effects retain their original policy. / 已呈现的问题和已发送的副作用保留原策略。
     * @param grant exact original host grant, never a wrapper or copied identity / 精确原宿主许可，不接受包装或复制身份
     * @param expectedRevision last confirmed scope-local policy revision / 最近确认的作用域内策略修订
     * @param mode requested human decision policy / 请求的人工决定策略
     * @param task authentic original executing task / 真实原执行任务
     * @return confirmed policy or explicit refusal; uncertain outcomes must stop later effects / 已确认策略或明确拒绝，未知结果须阻止后续副作用
     * @throws ApiException if scope, revision or authority cannot be verified / 无法核验作用域、修订或权限时 */
    default OperationResult<WorkflowPolicyState> updateReviewedWorkflowPolicy(WorkflowGrant grant, long expectedRevision,
                                                                              CommandReviewMode mode, TaskContext task) throws ApiException {
        throw new ApiException(new gold.debug.windowstolinux.appapi.task.result.Problem(
            gold.debug.windowstolinux.appapi.task.result.ProblemCode.UNAVAILABLE, "WORKFLOW_POLICY_UPDATE_UNAVAILABLE",
            "Host workflow policy update is unavailable", java.util.Map.of(), java.util.Optional.empty()));
    }

    /** Opens a task-local reviewed workflow; FULL_CONTROL requires an actual startup confirmation. / 开启任务内审核流程；全控须真实启动确认。
     * @param intent complete frozen workflow / 完整冻结流程
     * @param mode selected human decision policy / 人工确认策略
     * @param rememberAuthorization remember only actual human approval in this workflow / 仅记住本流程真实人工批准
     * @param recovery fresh original reconciliation scope / 新原任务核对作用域
     * @param task authentic original root / 真实原根
     * @return host-owned scope or explicit refusal / 宿主作用域或明确拒绝
     * @throws ApiException if unavailable or admission fails / 不可用或准入失败时 */
    default OperationResult<WorkflowGrant> openReviewedWorkflow(ActionIntent intent, CommandReviewMode mode,
                                                                boolean rememberAuthorization, boolean recovery, TaskContext task) throws ApiException {
        throw new ApiException(new gold.debug.windowstolinux.appapi.task.result.Problem(
            gold.debug.windowstolinux.appapi.task.result.ProblemCode.UNAVAILABLE, "WORKFLOW_REVIEW_SCOPE_UNAVAILABLE",
            "Host reviewed workflow is unavailable", java.util.Map.of(), java.util.Optional.empty()));
    }

    /** Authorizes an independently approved exact action; no reusable authority comes from automatic approval. / 授权独立审核通过的精确动作；自动通过不产生可复用权限。
     * @param intent unchanged original action / 未改变原动作
     * @param command actual SSH request, absent for non-command effects / 实际SSH请求，非命令为空
     * @param requiresUserConfirmation reviewer identified a real unresolved user tradeoff / 审核发现实际未决取舍
     * @param reason plain presentation of changes, impact and recommendation / 修改、影响和推荐方案的纯文本
     * @param task authentic original task / 真实原任务
     * @return single-use exact grant or refusal / 精确单次许可或拒绝
     * @throws ApiException if binding, scope or decision fails / 绑定、作用域或确认失败时 */
    default OperationResult<ApprovalGrant> approveReviewedAction(ActionIntent intent,
                                                                 java.util.Optional<gold.debug.windowstolinux.appapi.linux.command.CommandRequest> command,
                                                                 boolean requiresUserConfirmation, String reason, TaskContext task) throws ApiException {
        throw new ApiException(new gold.debug.windowstolinux.appapi.task.result.Problem(
            gold.debug.windowstolinux.appapi.task.result.ProblemCode.UNAVAILABLE, "REVIEWED_ACTION_UNAVAILABLE",
            "Host reviewed action authorization is unavailable", java.util.Map.of(), java.util.Optional.empty()));
    }

    /** Revokes and cleans only original workflow occupancy during authentic root reconciliation, without creating new permission. / 仅在真实原根核对期间撤销并清理原流程占用，不创建新权限。
     * @param task authentic original root recovery context / 真实原根恢复上下文
     * @throws ApiException if this implementation cannot confirm original cleanup / 此实现无法确认原清理时 */
    default void closeWorkflowResources(TaskContext task) throws ApiException {
        throw new ApiException(new gold.debug.windowstolinux.appapi.task.result.Problem(
            gold.debug.windowstolinux.appapi.task.result.ProblemCode.UNAVAILABLE, "WORKFLOW_CLEANUP_UNAVAILABLE",
            "Host original workflow cleanup is unavailable", java.util.Map.of(), java.util.Optional.empty()));
    }

    /** Requests a business-required decision using the original delegated workflow's captured authorization mode. / 使用原委托流程捕获的授权模式请求业务要求的确认。
     * @param intent exact effect and business-validated question meaning / 精确副作用及经业务校验的问题含义
     * @param task authentic original task / 真实原任务
     * @return a real single-use grant or refusal; ordinary implementations retain their existing approval behavior / 真实单次许可或拒绝；普通实现保留既有审批行为
     * @throws ApiException if ownership, decision or cleanup fails / 归属、确认或清理失败时 */
    default OperationResult<ApprovalGrant> approveRequired(ActionIntent intent, TaskContext task) throws ApiException {
        return approve(intent, task);
    }

    /**
     * Requests review of the exact effect and returns a host-validated grant. / 请求审核精确副作用并返回宿主可验证的授权。
     * @param intent exact effect submitted for user review / 提交给用户审核的精确副作用
     * @param task active host-created task context / 宿主创建的活动任务上下文
     * @return the owned result described by this capability / 此能力约定的所属结果
     * @throws ApiException if validation, execution or actual cleanup fails / 校验、执行或实际清理失败时
     */
    OperationResult<ApprovalGrant> approve(ActionIntent intent, TaskContext task) throws ApiException;

    /**
     * Requests input without allowing secret values into diagnostics. / 请求输入，秘密值不得进入诊断。
     * @param title human-readable task title / 可读的任务标题
     * @param prompt host-presented user input request / 由宿主展示的用户输入请求
     * @param secret opaque authorized secret reference or temporary secret value / 不透明的已授权秘密引用或临时秘密值
     * @param task active host-created task context / 宿主创建的活动任务上下文
     * @return the owned result described by this capability / 此能力约定的所属结果
     * @throws ApiException if validation, execution or actual cleanup fails / 校验、执行或实际清理失败时
     */
    OperationResult<String> requestText(String title, String prompt, boolean secret, TaskContext task)
        throws ApiException;
}
