package gold.debug.windowstolinux.shared.linux.sshd.distro.extension.registry;

import gold.debug.windowstolinux.shared.linux.sshd.distro.DistributionSetupRenderer;
import gold.debug.windowstolinux.shared.linux.sshd.distro.apt.DebianSetupRenderer;
import gold.debug.windowstolinux.shared.linux.sshd.distro.apt.UbuntuSetupRenderer;
import gold.debug.windowstolinux.shared.linux.sshd.distro.dnf.AlmaLinuxSetupRenderer;
import gold.debug.windowstolinux.shared.linux.sshd.distro.dnf.CentosStreamSetupRenderer;
import gold.debug.windowstolinux.shared.linux.sshd.distro.dnf.OracleLinuxSetupRenderer;
import gold.debug.windowstolinux.shared.linux.sshd.distro.dnf.RockyLinuxSetupRenderer;

import java.util.List;

/**
 * Assembles implemented distribution renderers only. / 仅装配已实现的具名发行版渲染器。
 */
public final class DistributionSetupCatalog {
    /**
     * Prevents instantiation of this static contract helper.
     * <p>防止实例化当前静态契约辅助类。
     */
    private DistributionSetupCatalog() {
    }

    /**
     * Returns the complete fixed supported-distribution profile set. / 返回完整且固定的受支持发行版配置集合。
     *
     * @return the complete fixed supported-distribution profile set / 完整且固定的受支持发行版配置集合
     */
    public static List<DistributionSetupRenderer> defaults() {
        return List.of(new UbuntuSetupRenderer(), new DebianSetupRenderer(),
                new CentosStreamSetupRenderer(), new RockyLinuxSetupRenderer(),
                new AlmaLinuxSetupRenderer(), new OracleLinuxSetupRenderer());
    }
}
