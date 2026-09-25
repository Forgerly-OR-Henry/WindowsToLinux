package gold.debug.windowstolinux.api.task;

import java.util.UUID;

/**
 * Tracked resource occupancy released only after actual cleanup. / 仅在实际清理后释放的受追踪资源占用。
 */
public interface ResourceLease extends AutoCloseable {

    /**
     * Returns the stable identity of this registered resource. / 返回此已登记资源的稳定身份。
     * @return the owned result described by this capability / 此能力约定的所属结果
     */
    UUID id();

    /**
     * Releases the owned resource; failure keeps occupancy and permits retry. / 释放所属资源；失败时保留占用并允许重试。
     * @throws ApiException if validation, execution or actual cleanup fails / 校验、执行或实际清理失败时
     */
    void close() throws ApiException;
}
