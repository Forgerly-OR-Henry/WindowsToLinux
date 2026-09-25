package gold.debug.windowstolinux.api.task;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

/**
 * Owned protocol process that remains occupied until actual exit. / 直到实际退出前持续保留占用的协议进程。
 */
public interface ManagedProcess extends AutoCloseable {

    /**
     * Returns the stable identity of this registered resource. / 返回此已登记资源的稳定身份。
     * @return the owned result described by this capability / 此能力约定的所属结果
     */
    UUID id();

    /**
     * Sends one correlated request; timeout never replays the business effect. / 发送一个关联请求；超时不会重放业务副作用。
     * @param message human-readable non-secret diagnostic message / 可读且不含秘密的诊断消息
     * @param timeout strictly positive operation deadline / 严格为正的操作期限
     * @return the owned result described by this capability / 此能力约定的所属结果
     * @throws ApiException if validation, execution or actual cleanup fails / 校验、执行或实际清理失败时
     */
    CompletionStage<OperationResult<ProcessMessage>> request(ProcessMessage message, Duration timeout)
            throws ApiException;

    /**
     * Requests cooperative cancellation without pretending cleanup is complete. / 请求协作取消，不将其视为清理已经完成。
     * @throws ApiException if validation, execution or actual cleanup fails / 校验、执行或实际清理失败时
     */
    void requestCancel() throws ApiException;

    /**
     * Completes only when the owned process has actually exited. / 仅在所属进程实际退出后完成。
     * @return the owned result described by this capability / 此能力约定的所属结果
     */
    CompletionStage<Integer> exitCode();

    /**
     * Releases the owned resource; failure keeps occupancy and permits retry. / 释放所属资源；失败时保留占用并允许重试。
     * @throws ApiException if validation, execution or actual cleanup fails / 校验、执行或实际清理失败时
     */
    void close() throws ApiException;
}
