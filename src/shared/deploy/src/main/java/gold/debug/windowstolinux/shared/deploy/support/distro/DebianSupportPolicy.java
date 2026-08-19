package gold.debug.windowstolinux.shared.deploy.support.distro;

import gold.debug.windowstolinux.shared.deploy.result.compatibility.HostSupportStatus;

import gold.debug.windowstolinux.shared.model.server.CpuMicroarchitectureLevel;
import gold.debug.windowstolinux.shared.model.capability.LinuxCapabilityFacts;

import java.util.List;

/** Debian stable compatibility policy. / Debian 稳定版兼容性策略。 */
final class DebianSupportPolicy implements DistributionSupportPolicy {
    /** Evaluates the supplied compatibility evidence. / 评估提供的兼容性证据。 */
    @Override
    public HostSupportStatus evaluate(LinuxCapabilityFacts capabilities, List<String> evidence) {
        if (!"13".equals(capabilities.version()) || !"apt".equals(capabilities.packageManager())
                || !"amd64".equals(capabilities.packageArchitecture())) {
            return DistributionSupportRules.unsupported(
                    "Debian must be stable 13 with apt and amd64 packages", evidence);
        }
        return DistributionSupportRules.requireCpu(
                capabilities, CpuMicroarchitectureLevel.X86_64_V1, "Debian", evidence);
    }
}
