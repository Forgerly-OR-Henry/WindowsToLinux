package gold.debug.windowstolinux.api.mod;

import gold.debug.windowstolinux.api.task.ApiException;
import gold.debug.windowstolinux.api.task.TaskContext;

/**
 * Service discovery handle that acquires occupancy per call. / 每次调用时获取占用的服务发现句柄。
 * @param <T> public payload or service contract type / 公开载荷或服务契约类型
 */
public interface ServiceHandle<T> extends AutoCloseable {

    /**
     * Performs task-bound synchronous work with validated budgets and ownership. / 校验预算及归属后执行绑定任务的同步工作。
     * @param <R> callback result type / 回调结果类型
     * @param task active host-created task context / 宿主创建的活动任务上下文
     * @param call synchronous callback executed while the provider is occupied / 占用提供方期间执行的同步回调
     * @return the owned result described by this capability / 此能力约定的所属结果
     * @throws ApiException if validation, execution or actual cleanup fails / 校验、执行或实际清理失败时
     */
    <R> R call(TaskContext task, ServiceCall<T, R> call) throws ApiException;

    /**
     * Releases the owned resource; failure keeps occupancy and permits retry. / 释放所属资源；失败时保留占用并允许重试。
     * @throws ApiException if validation, execution or actual cleanup fails / 校验、执行或实际清理失败时
     */
    void close() throws ApiException;
}
