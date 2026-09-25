package gold.debug.windowstolinux.api.task;

/**
 * Registration allowing manual reconciliation of retained tasks. / 允许人工核对保留任务的恢复注册。
 */
public interface RecoveryRegistration extends AutoCloseable {

    /**
     * Releases the owned resource; failure keeps occupancy and permits retry. / 释放所属资源；失败时保留占用并允许重试。
     * @throws ApiException if validation, execution or actual cleanup fails / 校验、执行或实际清理失败时
     */
    void close() throws ApiException;
}
