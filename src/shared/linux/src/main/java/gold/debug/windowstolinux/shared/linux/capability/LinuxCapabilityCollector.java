package gold.debug.windowstolinux.shared.linux.capability;

import gold.debug.windowstolinux.shared.linux.error.LinuxOperationException;
import gold.debug.windowstolinux.shared.model.capability.ServerCapabilityFacts;

/**
 * Typed target-host capability collection.
 *
 * <p>类型化目标主机能力采集契约。
 */
public interface LinuxCapabilityCollector {
    /**
     * Performs the {@code collectCapabilities} operation.
     *
     * <p>执行 {@code collectCapabilities} 操作。
     *
     * @return the operation result / 操作结果
     * @throws LinuxOperationException if the operation cannot be completed / 无法完成操作时
     */
    ServerCapabilityFacts collectCapabilities() throws LinuxOperationException;
}
