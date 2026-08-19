package gold.debug.windowstolinux.shared.linux.runtime;

import gold.debug.windowstolinux.shared.linux.error.LinuxOperationException;
import gold.debug.windowstolinux.shared.model.health.HealthCheck;
import gold.debug.windowstolinux.shared.model.lifecycle.LifecycleAction;
import gold.debug.windowstolinux.shared.model.lifecycle.LifecycleObservation;
import gold.debug.windowstolinux.shared.model.managed.ManagedApplication;

/**
 * Managed runtime observation, health and lifecycle contract.
 *
 * <p>受管运行时观测、健康检查和生命周期契约。
 */
public interface LinuxRuntimeExecutor {
    /**
     * Performs the {@code checkHealth} operation.
     *
     * <p>执行 {@code checkHealth} 操作。
     *
     * @param application the {@code application} value / {@code application} 值
     * @param healthCheck the {@code healthCheck} value / {@code healthCheck} 值
     * @return the operation result / 操作结果
     * @throws LinuxOperationException if the operation cannot be completed / 无法完成操作时
     */
    HealthCheckResult checkHealth(ManagedApplication application, HealthCheck healthCheck) throws LinuxOperationException;

    /**
     * Performs the {@code observe} operation.
     *
     * <p>执行 {@code observe} 操作。
     *
     * @param application the {@code application} value / {@code application} 值
     * @return the operation result / 操作结果
     * @throws LinuxOperationException if the operation cannot be completed / 无法完成操作时
     */
    LifecycleObservation observe(ManagedApplication application) throws LinuxOperationException;

    /**
     * Performs the {@code executeLifecycle} operation.
     *
     * <p>执行 {@code executeLifecycle} 操作。
     *
     * @param application the {@code application} value / {@code application} 值
     * @param action the {@code action} value / {@code action} 值
     * @param healthCheck the {@code healthCheck} value / {@code healthCheck} 值
     * @return the operation result / 操作结果
     * @throws LinuxOperationException if the operation cannot be completed / 无法完成操作时
     */
    LifecycleObservation executeLifecycle(ManagedApplication application, LifecycleAction action, HealthCheck healthCheck)
            throws LinuxOperationException;
}
