package gold.debug.windowstolinux.shared.linux.capability;

import gold.debug.windowstolinux.shared.linux.error.LinuxOperationException;
import gold.debug.windowstolinux.shared.model.capability.LinuxCapabilityFacts;

/**
 * Read-only capability collection for the typed deployment distribution and container matrix.
 *
 *  <p>用于部署发行版和容器矩阵的只读能力采集。
 */
public interface LinuxPlatformCapabilityCollector {
    /**
     * Collects current non-secret typed deployment host facts.
     *
     *  <p>采集当前非秘密部署主机事实。
     *
     * @return current host facts / 当前主机事实
     * @throws LinuxOperationException if collection cannot complete / 无法完成采集时
     */
    LinuxCapabilityFacts collectDeploymentCapabilities() throws LinuxOperationException;
}
