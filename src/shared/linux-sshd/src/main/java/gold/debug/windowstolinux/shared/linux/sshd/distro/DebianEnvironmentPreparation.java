package gold.debug.windowstolinux.shared.linux.sshd.distro;

import gold.debug.windowstolinux.shared.model.server.CpuMicroarchitectureLevel;

import java.time.Duration;

/** Debian stable-specific environment preparation adapter. / Debian 稳定版专用环境准备适配器。 */
public final class DebianEnvironmentPreparation {
    /** Maximum allowed preparation duration. / 允许的最长准备时长。 */
    public static final Duration TIMEOUT = EnvironmentPreparationShellSupport.TIMEOUT;

    private DebianEnvironmentPreparation() {
    }

    /** Renders Debian 13 preparation only. / 仅渲染 Debian 13 准备。 */
    public static String renderScript(String username, String version) {
        if (!"13".equals(version)) {
            throw new IllegalArgumentException("Debian preparation supports only stable 13");
        }
        return AptEnvironmentPreparationRenderer.render(new DistributionPreparationProfile(
                "debian", "", version, "amd64", CpuMicroarchitectureLevel.X86_64_V1,
                PreparationPackageCatalog.APT_BASE, PreparationRuntimeProfile.DEBIAN_13), username);
    }
}
