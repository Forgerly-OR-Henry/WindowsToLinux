package gold.debug.windowstolinux.shared.linux.sshd.distro.registry;

import gold.debug.windowstolinux.shared.linux.sshd.distro.DistributionSetupRenderer;
import gold.debug.windowstolinux.shared.linux.sshd.distro.apt.DebianFamilySetupCatalog;
import gold.debug.windowstolinux.shared.linux.sshd.distro.dnf.EnterpriseLinuxSetupCatalog;

import java.util.ArrayList;
import java.util.List;

/** Assembles data-driven setup profiles without owning package-manager mechanics. / 装配数据驱动的准备配置，但不持有包管理器机械流程。 */
public final class DistributionSetupCatalog {
    private DistributionSetupCatalog() {
    }

    /** Returns the complete fixed supported-distribution profile set. / 返回完整且固定的受支持发行版配置集合。 */
    public static List<DistributionSetupRenderer> defaults() {
        List<DistributionSetupRenderer> profiles = new ArrayList<>();
        profiles.addAll(DebianFamilySetupCatalog.defaults());
        profiles.addAll(EnterpriseLinuxSetupCatalog.defaults());
        return List.copyOf(profiles);
    }
}
