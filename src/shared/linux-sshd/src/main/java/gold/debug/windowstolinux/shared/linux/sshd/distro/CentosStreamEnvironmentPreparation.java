package gold.debug.windowstolinux.shared.linux.sshd.distro;

import gold.debug.windowstolinux.shared.model.server.CpuMicroarchitectureLevel;

import java.time.Duration;

/** CentOS Stream-specific environment preparation adapter. / CentOS Stream 专用环境准备适配器。 */
public final class CentosStreamEnvironmentPreparation {
    /** Maximum allowed preparation duration. / 允许的最长准备时长。 */
    public static final Duration TIMEOUT = EnvironmentPreparationShellSupport.TIMEOUT;

    private CentosStreamEnvironmentPreparation() {
    }

    /** Renders CentOS Stream 9 or 10 preparation. / 渲染 CentOS Stream 9 或 10 准备。 */
    public static String renderScript(String username, String version) {
        if (!("9".equals(version) || "10".equals(version))) {
            throw new IllegalArgumentException("CentOS Stream preparation supports only 9 or 10");
        }
        boolean ten = "10".equals(version);
        return DnfEnvironmentPreparationRenderer.render(new DistributionPreparationProfile(
                "centos", "stream", version, "x86_64",
                ten ? CpuMicroarchitectureLevel.X86_64_V3 : CpuMicroarchitectureLevel.X86_64_V1,
                PreparationPackageCatalog.enterprise(version),
                ten ? PreparationRuntimeProfile.ENTERPRISE_10 : PreparationRuntimeProfile.ENTERPRISE_9), username);
    }
}
