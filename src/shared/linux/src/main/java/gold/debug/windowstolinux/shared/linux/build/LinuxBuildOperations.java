package gold.debug.windowstolinux.shared.linux.build;

import gold.debug.windowstolinux.shared.linux.connection.LinuxOperationException;
import gold.debug.windowstolinux.shared.linux.transfer.RemoteWorkspace;
import gold.debug.windowstolinux.shared.model.deployment.BuildLimits;

/**
 * Controlled target-host build contract.
 *
 * <p>受控目标机构建契约。
 */
public interface LinuxBuildOperations {
    /**
     * Performs the {@code build} operation.
     *
     * <p>执行 {@code build} 操作。
     *
     * @param workspace the {@code workspace} value / {@code workspace} 值
     * @param limits the {@code limits} value / {@code limits} 值
     * @return the operation result / 操作结果
     * @throws LinuxOperationException if the operation cannot be completed / 无法完成操作时
     */
    RemoteBuildResult build(RemoteWorkspace workspace, BuildLimits limits) throws LinuxOperationException;
}
