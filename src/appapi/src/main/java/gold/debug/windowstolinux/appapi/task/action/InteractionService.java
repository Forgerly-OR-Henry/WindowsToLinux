package gold.debug.windowstolinux.appapi.task.action;

import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;

/**
 * Host-owned review and input bound to the accepted task. / 绑定已接纳任务的宿主审核及输入交互。
 */
public interface InteractionService {

    /** Delegates one complete workflow under the business-frozen authorization mode, without granting individual effects. / 按业务冻结的授权模式委托一个完整流程，不直接授权单个副作用。
     * @param frozenWorkflowIntent complete original workflow and server scope / 完整原流程及服务器范围
     * @param authorizationMode business-selected mode frozen for this task / 本任务冻结的业务所选模式
     * @param task authentic original executing root task / 真实原执行根任务
     * @return host-issued original delegation or explicit refusal / 宿主签发的原委托或明确拒绝
     * @throws ApiException if this implementation lacks host delegation or identity cannot be verified / 实现没有宿主委托能力或无法核验身份时 */
    default OperationResult<WorkflowGrant> delegateWorkflow(ActionIntent frozenWorkflowIntent, AuthorizationMode authorizationMode, TaskContext task) throws ApiException {
        throw new ApiException(new gold.debug.windowstolinux.appapi.task.result.Problem(
            gold.debug.windowstolinux.appapi.task.result.ProblemCode.UNAVAILABLE, "WORKFLOW_DELEGATION_UNAVAILABLE",
            "Host workflow delegation is unavailable", java.util.Map.of(), java.util.Optional.empty()));
    }

    /** Opens question-only repetition for an original workflow without delegating ordinary effects. / 为原流程开启仅问题的重复规则，不委托普通副作用。
     * @param frozenWorkflowIntent complete original workflow and server scope / 完整原流程及服务器范围
     * @param authorizationMode frozen ONCE or GENERAL mode / 冻结的 ONCE 或 GENERAL 模式
     * @param task authentic original executing root / 真实原执行根
     * @return host-owned question scope without an initial handoff question / 没有初始交接问题的宿主所属问题作用域
     * @throws ApiException if unavailable, foreign or FULL is requested / 不可用、属于他方或请求 FULL 时 */
    default OperationResult<WorkflowGrant> reviewedWorkflow(ActionIntent frozenWorkflowIntent, AuthorizationMode authorizationMode, TaskContext task) throws ApiException {
        throw new ApiException(new gold.debug.windowstolinux.appapi.task.result.Problem(
            gold.debug.windowstolinux.appapi.task.result.ProblemCode.UNAVAILABLE, "WORKFLOW_REVIEW_SCOPE_UNAVAILABLE",
            "Host workflow review scope is unavailable", java.util.Map.of(), java.util.Optional.empty()));
    }

    /** Opens a fresh explicitly requested scope only during original-root reconciliation, never reviving prior authority. / 仅原根核对期间开启明确请求的新作用域，不复活旧权限。
     * @param frozenWorkflowIntent complete original workflow and server scope / 完整原流程及服务器范围
     * @param authorizationMode explicit frozen business authorization mode / 明确冻结的业务授权模式
     * @param reviewOnly whether only ONCE/GENERAL question repetition is requested / 是否仅请求 ONCE/GENERAL 问题重复规则
     * @param task authentic original root recovery context / 真实原根恢复上下文
     * @return new opaque host scope after exact previous occupancy cleanup; no permission to replay unknown effects / 精确清理旧占用后的新不透明宿主作用域；不授予重放未知效果的权限
     * @throws ApiException if recovery, original facts or cleanup cannot be confirmed / 无法确认恢复、原依据或清理时 */
    default OperationResult<WorkflowGrant> recoverWorkflow(ActionIntent frozenWorkflowIntent, AuthorizationMode authorizationMode, boolean reviewOnly, TaskContext task) throws ApiException {
        throw new ApiException(new gold.debug.windowstolinux.appapi.task.result.Problem(
            gold.debug.windowstolinux.appapi.task.result.ProblemCode.UNAVAILABLE, "WORKFLOW_RECOVERY_SCOPE_UNAVAILABLE",
            "Host recovery workflow scope is unavailable", java.util.Map.of(), java.util.Optional.empty()));
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
