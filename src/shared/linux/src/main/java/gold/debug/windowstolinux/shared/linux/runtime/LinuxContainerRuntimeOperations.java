package gold.debug.windowstolinux.shared.linux.runtime;

import gold.debug.windowstolinux.shared.linux.connection.LinuxOperationException;

/**
 * Narrow managed-container lifecycle contract; it has no arbitrary container-command entry point.
 *
 * <p>狭窄的受管容器生命周期契约；没有任意容器命令入口。
 */
public interface LinuxContainerRuntimeOperations {
    /**
     * Applies engine-specific autostart metadata without changing current container runtime state.
     *
     * <p>应用引擎专属自启元数据，而不改变当前容器运行状态。
     *
     * @param applicationId managed application identity / 受管应用身份
     * @param autostart engine-specific autostart intent / 引擎专属自启意图
     * @throws LinuxOperationException if the controlled operation fails / 受控操作失败时
     */
    void configureContainerAutostart(String applicationId, ContainerAutostart autostart) throws LinuxOperationException;
}
