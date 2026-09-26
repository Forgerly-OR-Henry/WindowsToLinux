package gold.debug.windowstolinux.appapi.task;

import java.util.Optional;
import java.util.UUID;

/**
 * Accepted task identity and cooperative resource accounting. / 已接纳任务的身份及协作资源登记。
 */
public interface TaskContext {

    /**
     * Returns the stable identity of this registered resource. / 返回此已登记资源的稳定身份。
     * @return the owned result described by this capability / 此能力约定的所属结果
     */
    UUID id();

    /**
     * Returns this task's cooperative cancellation signal. / 返回此任务的协作取消信号。
     * @return the owned result described by this capability / 此能力约定的所属结果
     */
    CancellationToken cancellation();

    /**
     * Updates bounded task progress without changing its business result. / 更新有界任务进度，不改变业务结果。
     * @param text message or response text / 消息或响应文本
     * @param fraction progress fraction between zero and one / 零到一之间的进度比例
     * @throws ApiException if validation, execution or actual cleanup fails / 校验、执行或实际清理失败时
     */
    void progress(String text, Optional<Double> fraction) throws ApiException;

    /**
     * Registers an owned local resource without granting extra server locks. / 登记所属本地资源，不授予额外服务器锁。
     * @param kind protocol message kind / 协议消息类型
     * @param localId identifier local to the registering Mod / 登记插件内部的标识
     * @param description non-secret human-readable description / 不含秘密的可读说明
     * @return the owned result described by this capability / 此能力约定的所属结果
     * @throws ApiException if validation, execution or actual cleanup fails / 校验、执行或实际清理失败时
     */
    ResourceLease retain(String kind, String localId, String description) throws ApiException;
}
