package gold.debug.windowstolinux.appapi.task.control;

import gold.debug.windowstolinux.appapi.task.result.ApiException;

/** Activation-owned control registration. / 启用代次所属的控制登记。 */
public interface TaskControlRegistration extends AutoCloseable {
    @Override void close() throws ApiException;
}
