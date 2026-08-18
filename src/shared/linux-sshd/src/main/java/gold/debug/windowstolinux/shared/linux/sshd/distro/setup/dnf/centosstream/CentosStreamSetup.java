package gold.debug.windowstolinux.shared.linux.sshd.distro.setup.dnf.centosstream;

import gold.debug.windowstolinux.shared.linux.sshd.distro.setup.dnf.DnfSetupRenderer;
import gold.debug.windowstolinux.shared.linux.sshd.distro.setup.catalog.DistributionPackageSets;
import gold.debug.windowstolinux.shared.linux.sshd.distro.profile.EcosystemCapabilityChecks;
import gold.debug.windowstolinux.shared.linux.sshd.distro.profile.DistributionSetupProfile;
import gold.debug.windowstolinux.shared.linux.sshd.distro.shell.SetupShellSupport;

import gold.debug.windowstolinux.shared.model.server.CpuMicroarchitectureLevel;

import java.time.Duration;

/** CentOS Stream-specific environment preparation adapter. / CentOS Stream 专用环境准备适配器。 */
public final class CentosStreamSetup {
    /** Maximum allowed preparation duration. / 允许的最长准备时长。 */
    public static final Duration TIMEOUT = SetupShellSupport.TIMEOUT;

    private CentosStreamSetup() {
    }

    /** Renders CentOS Stream 9 or 10 preparation. / 渲染 CentOS Stream 9 或 10 准备。 */
    public static String renderScript(String username, String version) {
        if (!("9".equals(version) || "10".equals(version))) {
            throw new IllegalArgumentException("CentOS Stream preparation supports only 9 or 10");
        }
        boolean ten = "10".equals(version);
        // CentOS Stream 9/10 cloud images may omit VARIANT_ID even though ID and VERSION_ID identify the supported stream.
        // CentOS Stream 9/10 云镜像可能省略 VARIANT_ID，但 ID 与 VERSION_ID 仍可识别受支持的 Stream。
        return DnfSetupRenderer.render(new DistributionSetupProfile(
                "centos", "", version, "x86_64",
                ten ? CpuMicroarchitectureLevel.X86_64_V3 : CpuMicroarchitectureLevel.X86_64_V1,
                DistributionPackageSets.enterprise(version),
                ten ? EcosystemCapabilityChecks.ENTERPRISE_10 : EcosystemCapabilityChecks.ENTERPRISE_9), username);
    }
}
