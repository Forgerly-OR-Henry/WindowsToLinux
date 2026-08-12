package gold.debug.windowstolinux.shared.deploy.compatibility;

import gold.debug.windowstolinux.shared.model.server.CpuMicroarchitectureLevel;
import gold.debug.windowstolinux.shared.model.server.LinuxCapabilities;

import java.util.List;

/** Debian stable compatibility policy. / Debian 稳定版兼容性策略。 */
final class DebianCompatibilityPolicy implements DistributionCompatibilityPolicy {
    @Override
    public HostSupport evaluate(LinuxCapabilities capabilities, List<String> evidence) {
        if (!"13".equals(capabilities.version()) || !"apt".equals(capabilities.packageManager())
                || !"amd64".equals(capabilities.packageArchitecture())) {
            return DistributionPolicySupport.unsupported(
                    "Debian must be stable 13 with apt and amd64 packages", evidence);
        }
        return DistributionPolicySupport.requireCpu(
                capabilities, CpuMicroarchitectureLevel.X86_64_V1, "Debian", evidence);
    }
}
