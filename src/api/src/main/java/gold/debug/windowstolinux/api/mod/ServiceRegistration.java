package gold.debug.windowstolinux.api.mod;

import gold.debug.windowstolinux.api.task.ApiException;

/**
 * Owned registration removed only after successful release. / 仅在实际释放后撤销的服务注册。
 */
public interface ServiceRegistration extends AutoCloseable {

    /**
     * Releases the owned resource; failure keeps occupancy and permits retry. / 释放所属资源；失败时保留占用并允许重试。
     * @throws ApiException if validation, execution or actual cleanup fails / 校验、执行或实际清理失败时
     */
    void close() throws ApiException;
}
