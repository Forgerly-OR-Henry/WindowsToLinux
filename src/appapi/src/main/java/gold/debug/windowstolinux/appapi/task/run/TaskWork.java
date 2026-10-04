package gold.debug.windowstolinux.appapi.task.run;

import gold.debug.windowstolinux.appapi.task.result.OperationResult;

/**
 * Author work observing cancellation and retaining unfinished resources. / 观察取消并保留未完成资源的作者任务逻辑。
 * @param <T> public payload or service contract type / 公开载荷或服务契约类型
 */
public interface TaskWork<T> {

    /**
     * Performs author work while observing cancellation and cleanup obligations. / 观察取消与清理责任并执行作者任务。
     * @param task active host-created task context / 宿主创建的活动任务上下文
     * @return the owned result described by this capability / 此能力约定的所属结果
     * @throws Exception if validation, execution or actual cleanup fails / 校验、执行或实际清理失败时
     */
    OperationResult<T> run(TaskContext task) throws Exception;
}
