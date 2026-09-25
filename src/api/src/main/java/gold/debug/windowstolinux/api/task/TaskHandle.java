package gold.debug.windowstolinux.api.task;

import java.util.UUID;
import java.util.concurrent.CompletionStage;

/**
 * Observable task result completed only after required cleanup. / 仅在必要清理完成后结束的可观察任务结果。
 * @param <T> public payload or service contract type / 公开载荷或服务契约类型
 */
public interface TaskHandle<T> {

    /**
     * Returns the stable identity of this registered resource. / 返回此已登记资源的稳定身份。
     * @return the owned result described by this capability / 此能力约定的所属结果
     */
    UUID id();

    /**
     * Captures the current owned state or immutable selected source. / 取得当前所属状态或选定来源的不可变快照。
     * @return the owned result described by this capability / 此能力约定的所属结果
     */
    TaskSnapshot snapshot();

    /**
     * Observes the final result after required resource cleanup. / 在必要资源清理结束后观察最终结果。
     * @return the owned result described by this capability / 此能力约定的所属结果
     */
    CompletionStage<OperationResult<T>> completion();

    /**
     * Requests cooperative cancellation without pretending cleanup is complete. / 请求协作取消，不将其视为清理已经完成。
     * @throws ApiException if validation, execution or actual cleanup fails / 校验、执行或实际清理失败时
     */
    void requestCancel() throws ApiException;
}
