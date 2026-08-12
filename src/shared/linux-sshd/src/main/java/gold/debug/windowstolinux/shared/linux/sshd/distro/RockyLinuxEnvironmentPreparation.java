package gold.debug.windowstolinux.shared.linux.sshd.distro;

import gold.debug.windowstolinux.shared.model.server.CpuMicroarchitectureLevel;

import java.time.Duration;

/** Rocky Linux-specific maintained-release preparation adapter. / Rocky Linux 专用受维护版本准备适配器。 */
public final class RockyLinuxEnvironmentPreparation {
    /** Maximum allowed preparation duration. / 允许的最长准备时长。 */
    public static final Duration TIMEOUT = EnvironmentPreparationShellSupport.TIMEOUT;

    private RockyLinuxEnvironmentPreparation() {
    }

    /** Renders Rocky Linux 9.8 or 10.2 preparation. / 渲染 Rocky Linux 9.8 或 10.2 准备。 */
    public static String renderScript(String username, String version) {
        if (!("9.8".equals(version) || "10.2".equals(version))) {
            throw new IllegalArgumentException("Rocky Linux preparation supports only maintained 9.8 or 10.2");
        }
        boolean ten = version.startsWith("10.");
        return DnfEnvironmentPreparationRenderer.render(new DistributionPreparationProfile(
                "rocky", "", version, "x86_64",
                ten ? CpuMicroarchitectureLevel.X86_64_V3 : CpuMicroarchitectureLevel.X86_64_V1,
                PreparationPackageCatalog.enterprise(ten ? "10" : "9"),
                ten ? PreparationRuntimeProfile.ENTERPRISE_10 : PreparationRuntimeProfile.ENTERPRISE_9), username);
    }
}
