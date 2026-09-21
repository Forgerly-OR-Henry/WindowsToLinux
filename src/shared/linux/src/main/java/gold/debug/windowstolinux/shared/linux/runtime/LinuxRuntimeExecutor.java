package gold.debug.windowstolinux.shared.linux.runtime;

import gold.debug.windowstolinux.shared.linux.error.LinuxOperationException;
import gold.debug.windowstolinux.shared.model.health.HealthCheck;
import gold.debug.windowstolinux.shared.model.lifecycle.LifecycleAction;
import gold.debug.windowstolinux.shared.model.lifecycle.LifecycleObservation;
import gold.debug.windowstolinux.shared.model.managed.ManagedApplication;

/**
 * Managed runtime observation, health and lifecycle contract.
 *
 *  <p>受管运行时观测、健康检查和生命周期契约。
 */
public interface LinuxRuntimeExecutor {
    /**
     * Checks health.
     * <p>检查健康。
     *
     * @param application managed target with its server and ownership identity / 携带服务器及归属身份的受管目标
     * @param healthCheck reviewed probe and its success criteria / 已审阅探测及其成功条件
     * @return the operation result / 操作结果
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     */
    HealthCheckResult checkHealth(ManagedApplication application, HealthCheck healthCheck) throws LinuxOperationException;

    /**
     * Observes lifecycle observation.
     * <p>观测生命周期观测。
     *
     * @param application managed target with its server and ownership identity / 携带服务器及归属身份的受管目标
     * @return the operation result / 操作结果
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     */
    LifecycleObservation observe(ManagedApplication application) throws LinuxOperationException;

    /**
     * Executes lifecycle.
     * <p>执行生命周期。
     *
     * @param application managed target with its server and ownership identity / 携带服务器及归属身份的受管目标
     * @param action explicit action selected for the current target / 为当前目标显式选择的动作
     * @param healthCheck reviewed probe and its success criteria / 已审阅探测及其成功条件
     * @return the operation result / 操作结果
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     */
    LifecycleObservation executeLifecycle(ManagedApplication application, LifecycleAction action, HealthCheck healthCheck)
            throws LinuxOperationException;
}
