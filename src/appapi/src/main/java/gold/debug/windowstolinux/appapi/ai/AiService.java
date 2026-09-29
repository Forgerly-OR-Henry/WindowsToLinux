package gold.debug.windowstolinux.appapi.ai;

import gold.debug.windowstolinux.appapi.task.ApiException;
import gold.debug.windowstolinux.appapi.task.OperationResult;
import gold.debug.windowstolinux.appapi.task.TaskContext;

/**
 * Caller-bound model access with task authorization and budgets. / 绑定调用方并校验任务授权及预算的模型能力。
 */
public interface AiService {

    /** Returns an opaque revision of all configured purpose routes before a recoverable AI decision. / 可恢复 AI 决策前返回全部已配置用途路由的不透明修订。
     * @param purpose purpose that must currently have a usable model / 当前必须有可用模型的用途
     * @param task authentic original task / 真实原任务
     * @return exact non-secret routing revision / 精确不含秘密的路由修订
     * @throws ApiException if authorization or configuration is unavailable / 授权或配置不可用时 */
    String configurationRevision(AiPurpose purpose, TaskContext task) throws ApiException;

    /**
     * Performs task-bound synchronous work with validated budgets and ownership. / 校验预算及归属后执行绑定任务的同步工作。
     * @param request immutable capability request / 不可变的能力请求
     * @param task active host-created task context / 宿主创建的活动任务上下文
     * @return the owned result described by this capability / 此能力约定的所属结果
     * @throws ApiException if validation, execution or actual cleanup fails / 校验、执行或实际清理失败时
     */
    OperationResult<AiResponse> call(AiRequest request, TaskContext task) throws ApiException;

    /** Calls only the frozen routing revision, retaining its exact selected candidate list. / 仅按冻结路由修订调用并保持其精确候选列表。
     * @param request bounded model request / 有界模型请求
     * @param expectedRevision previously frozen opaque revision / 此前冻结的不透明修订
     * @param task authentic original task / 真实原任务
     * @return actual result or classified refusal / 实际结果或已分类拒绝
     * @throws ApiException if authorization or configuration cannot be verified / 授权或配置无法核实时 */
    OperationResult<AiResponse> call(AiRequest request, String expectedRevision, TaskContext task) throws ApiException;
}
