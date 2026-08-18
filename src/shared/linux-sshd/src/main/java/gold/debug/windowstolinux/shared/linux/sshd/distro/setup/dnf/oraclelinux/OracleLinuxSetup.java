package gold.debug.windowstolinux.shared.linux.sshd.distro.setup.dnf.oraclelinux;

import gold.debug.windowstolinux.shared.linux.sshd.distro.setup.dnf.DnfSetupRenderer;
import gold.debug.windowstolinux.shared.linux.sshd.distro.setup.catalog.DistributionPackageSets;
import gold.debug.windowstolinux.shared.linux.sshd.distro.profile.EcosystemCapabilityChecks;
import gold.debug.windowstolinux.shared.linux.sshd.distro.profile.DistributionSetupProfile;
import gold.debug.windowstolinux.shared.linux.sshd.distro.shell.SetupShellSupport;

import gold.debug.windowstolinux.shared.model.server.CpuMicroarchitectureLevel;

import java.time.Duration;

/** Oracle Linux-specific rolling-major preparation adapter. / Oracle Linux 专用滚动主版本准备适配器。 */
public final class OracleLinuxSetup {
    /** Maximum allowed preparation duration. / 允许的最长准备时长。 */
    public static final Duration TIMEOUT = SetupShellSupport.TIMEOUT;

    private OracleLinuxSetup() {
    }

    /** Renders a currently updated Oracle Linux 9 or 10 profile. / 渲染已更新的 Oracle Linux 9 或 10 配置。 */
    public static String renderScript(String username, String version) {
        if (!("9.7".equals(version) || "10.2".equals(version))) {
            throw new IllegalArgumentException("Oracle Linux preparation supports only current 9.7 or 10.2 updates");
        }
        boolean ten = version.startsWith("10.");
        return DnfSetupRenderer.render(new DistributionSetupProfile(
                "ol", "", version, "x86_64",
                ten ? CpuMicroarchitectureLevel.X86_64_V3 : CpuMicroarchitectureLevel.X86_64_V1,
                DistributionPackageSets.enterprise(ten ? "10" : "9"),
                ten ? EcosystemCapabilityChecks.ENTERPRISE_10 : EcosystemCapabilityChecks.ENTERPRISE_9), username);
    }
}
