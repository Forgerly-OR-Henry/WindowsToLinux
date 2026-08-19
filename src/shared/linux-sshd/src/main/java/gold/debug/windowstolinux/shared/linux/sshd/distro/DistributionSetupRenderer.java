package gold.debug.windowstolinux.shared.linux.sshd.distro;

import gold.debug.windowstolinux.shared.linux.error.LinuxOperationException;
import gold.debug.windowstolinux.shared.model.capability.LinuxCapabilityFacts;
import gold.debug.windowstolinux.shared.model.server.LinuxDistroType;

/**
 * Renders one fixed supported-distribution preparation script from collected facts.
 *
 * <p>根据已采集事实渲染一个固定受支持发行版准备脚本。
 */
public interface DistributionSetupRenderer {
    /** Creates one renderer for a fixed distribution family. / 为固定发行版系列创建一个渲染器。 */
    static DistributionSetupRenderer of(LinuxDistroType distro, ScriptRenderer renderer) {
        return new DistributionSetupRenderer() {
            @Override public LinuxDistroType distro() {
                return distro;
            }

            @Override public String render(LinuxCapabilityFacts capabilities, String username)
                    throws LinuxOperationException {
                return renderer.render(capabilities, username);
            }
        };
    }

    /** Returns the only distribution family handled by this preparation. / 返回此准备实现唯一处理的发行版系列。 */
    LinuxDistroType distro();

    /** Renders a restricted preparation script for the collected host. / 为已采集主机渲染受限准备脚本。 */
    String render(LinuxCapabilityFacts capabilities, String username) throws LinuxOperationException;

    /** Renders one checked preparation script. / 渲染一个可抛受控异常的准备脚本。 */
    @FunctionalInterface
    interface ScriptRenderer {
        /** Renders the selected preparation script. / 渲染所选择的准备脚本。 */
        String render(LinuxCapabilityFacts capabilities, String username) throws LinuxOperationException;
    }
}
