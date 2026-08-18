package gold.debug.windowstolinux.shared.linux.sshd.distro.setup.apt.debian;

import gold.debug.windowstolinux.shared.linux.sshd.distro.setup.apt.AptSetupRenderer;
import gold.debug.windowstolinux.shared.linux.sshd.distro.setup.catalog.DistributionPackageSets;
import gold.debug.windowstolinux.shared.linux.sshd.distro.profile.EcosystemCapabilityChecks;
import gold.debug.windowstolinux.shared.linux.sshd.distro.profile.DistributionSetupProfile;
import gold.debug.windowstolinux.shared.linux.sshd.distro.shell.SetupShellSupport;

import gold.debug.windowstolinux.shared.model.server.CpuMicroarchitectureLevel;

import java.time.Duration;

/** Debian stable-specific environment preparation adapter. / Debian 稳定版专用环境准备适配器。 */
public final class DebianSetup {
    /** Maximum allowed preparation duration. / 允许的最长准备时长。 */
    public static final Duration TIMEOUT = SetupShellSupport.TIMEOUT;

    private DebianSetup() {
    }

    /** Renders Debian 13 preparation only. / 仅渲染 Debian 13 准备。 */
    public static String renderScript(String username, String version) {
        if (!"13".equals(version)) {
            throw new IllegalArgumentException("Debian preparation supports only stable 13");
        }
        return AptSetupRenderer.render(new DistributionSetupProfile(
                "debian", "", version, "amd64", CpuMicroarchitectureLevel.X86_64_V1,
                DistributionPackageSets.APT_BASE, EcosystemCapabilityChecks.DEBIAN_13), username);
    }
}
