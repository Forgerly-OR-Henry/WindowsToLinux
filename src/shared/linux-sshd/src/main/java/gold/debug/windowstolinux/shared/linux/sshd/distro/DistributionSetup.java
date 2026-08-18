package gold.debug.windowstolinux.shared.linux.sshd.distro;

import gold.debug.windowstolinux.shared.linux.error.LinuxOperationException;
import gold.debug.windowstolinux.shared.model.capability.LinuxCapabilities;
import gold.debug.windowstolinux.shared.model.server.LinuxDistro;

/**
 * Renders one fixed supported-distribution preparation script from collected facts.
 *
 * <p>根据已采集事实渲染一个固定受支持发行版准备脚本。
 */
public interface DistributionSetup {
    /** Returns the only distribution family handled by this preparation. / 返回此准备实现唯一处理的发行版系列。 */
    LinuxDistro distro();

    /** Renders a restricted preparation script for the collected host. / 为已采集主机渲染受限准备脚本。 */
    String render(LinuxCapabilities capabilities, String username) throws LinuxOperationException;
}
