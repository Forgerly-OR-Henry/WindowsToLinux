package gold.debug.windowstolinux.shared.deploy.compatibility;

import gold.debug.windowstolinux.shared.model.server.CpuMicroarchitectureLevel;
import gold.debug.windowstolinux.shared.model.server.LinuxCapabilities;

import java.util.List;

/** CentOS Stream compatibility policy. / CentOS Stream 兼容性策略。 */
final class CentosStreamCompatibilityPolicy implements DistributionCompatibilityPolicy {
    @Override
    public HostSupport evaluate(LinuxCapabilities capabilities, List<String> evidence) {
        if (!"dnf".equals(capabilities.packageManager())
                || !("9".equals(capabilities.version()) || "10".equals(capabilities.version()))
                || !"x86_64".equals(capabilities.packageArchitecture())) {
            return DistributionPolicySupport.unsupported(
                    "CentOS Stream must be 9 or 10 with dnf and x86_64 packages", evidence);
        }
        CpuMicroarchitectureLevel required = "10".equals(capabilities.version())
                ? CpuMicroarchitectureLevel.X86_64_V3 : CpuMicroarchitectureLevel.X86_64_V1;
        HostSupport cpu = DistributionPolicySupport.requireCpu(capabilities, required, "CentOS Stream", evidence);
        return cpu == HostSupport.READY_FOR_RUNTIME_VALIDATION
                ? DistributionPolicySupport.requireEnforcingSelinux(capabilities, "CentOS Stream", evidence) : cpu;
    }
}
