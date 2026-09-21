package gold.debug.windowstolinux.shared.linux.capability;

import gold.debug.windowstolinux.shared.linux.error.LinuxOperationException;
import gold.debug.windowstolinux.shared.model.capability.ServerCapabilityFacts;

/**
 * Typed target-host capability collection.
 *
 *  <p>类型化目标主机能力采集契约。
 */
public interface LinuxCapabilityCollector {
    /**
     * Collects observed target tools and runtime capabilities.
     * <p>采集目标工具及运行能力观测。
     *
     * @return the operation result / 操作结果
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     */
    ServerCapabilityFacts collectCapabilities() throws LinuxOperationException;
}
