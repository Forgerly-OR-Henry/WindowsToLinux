package gold.debug.windowstolinux.shared.linux.sshd.distro.setup.dnf.almalinux;

import gold.debug.windowstolinux.shared.linux.sshd.distro.setup.dnf.DnfSetupRenderer;
import gold.debug.windowstolinux.shared.linux.sshd.distro.setup.catalog.DistributionPackageSets;
import gold.debug.windowstolinux.shared.linux.sshd.distro.profile.EcosystemCapabilityChecks;
import gold.debug.windowstolinux.shared.linux.sshd.distro.profile.DistributionSetupProfile;
import gold.debug.windowstolinux.shared.linux.sshd.distro.shell.SetupShellSupport;

import gold.debug.windowstolinux.shared.model.server.CpuMicroarchitectureLevel;

import java.time.Duration;

/** AlmaLinux-specific default-architecture preparation adapter. / AlmaLinux 专用默认架构准备适配器。 */
public final class AlmaLinuxSetup {
    /** Maximum allowed preparation duration. / 允许的最长准备时长。 */
    public static final Duration TIMEOUT = SetupShellSupport.TIMEOUT;

    private AlmaLinuxSetup() {
    }

    /** Renders maintained AlmaLinux default x86_64 preparation. / 渲染受维护 AlmaLinux 默认 x86_64 准备。 */
    public static String renderScript(String username, String version, String packageArchitecture) {
        if (!("9.8".equals(version) || "10.2".equals(version)) || !"x86_64".equals(packageArchitecture)) {
            throw new IllegalArgumentException(
                    "AlmaLinux automatic preparation supports maintained 9.8 or 10.2 default x86_64 only");
        }
        boolean ten = version.startsWith("10.");
        return DnfSetupRenderer.render(new DistributionSetupProfile(
                "almalinux", "", version, packageArchitecture,
                ten ? CpuMicroarchitectureLevel.X86_64_V3 : CpuMicroarchitectureLevel.X86_64_V1,
                DistributionPackageSets.enterprise(ten ? "10" : "9"),
                ten ? EcosystemCapabilityChecks.ENTERPRISE_10 : EcosystemCapabilityChecks.ENTERPRISE_9), username);
    }
}
