package gold.debug.windowstolinux.api.ai;

import gold.debug.windowstolinux.api.task.ApiException;
import gold.debug.windowstolinux.api.task.OperationResult;
import gold.debug.windowstolinux.api.task.TaskContext;

/**
 * Caller-bound model access with task authorization and budgets. / 绑定调用方并校验任务授权及预算的模型能力。
 */
public interface AiService {

    /**
     * Performs task-bound synchronous work with validated budgets and ownership. / 校验预算及归属后执行绑定任务的同步工作。
     * @param request immutable capability request / 不可变的能力请求
     * @param task active host-created task context / 宿主创建的活动任务上下文
     * @return the owned result described by this capability / 此能力约定的所属结果
     * @throws ApiException if validation, execution or actual cleanup fails / 校验、执行或实际清理失败时
     */
    OperationResult<AiResponse> call(AiRequest request, TaskContext task) throws ApiException;
}
