package gold.debug.windowstolinux.shared.deploy.support.distro;

import gold.debug.windowstolinux.shared.deploy.contract.result.compatibility.HostSupportStatus;

import gold.debug.windowstolinux.shared.model.server.CpuMicroarchitectureLevel;
import gold.debug.windowstolinux.shared.model.capability.LinuxCapabilityFacts;

import java.util.List;

/** CentOS Stream compatibility policy. / CentOS Stream 兼容性策略。 */
final class CentosStreamSupportPolicy implements DistributionSupportPolicy {
    /** Evaluates the supplied compatibility evidence. / 评估提供的兼容性证据。 */
    @Override
    public HostSupportStatus evaluate(LinuxCapabilityFacts capabilities, List<String> evidence) {
        if (!"dnf".equals(capabilities.packageManager())
                || !("9".equals(capabilities.version()) || "10".equals(capabilities.version()))
                || !"x86_64".equals(capabilities.packageArchitecture())) {
            return DistributionSupportRules.unsupported(
                    "CentOS Stream must be 9 or 10 with dnf and x86_64 packages", evidence);
        }
        CpuMicroarchitectureLevel required = "10".equals(capabilities.version())
                ? CpuMicroarchitectureLevel.X86_64_V3 : CpuMicroarchitectureLevel.X86_64_V1;
        HostSupportStatus cpu = DistributionSupportRules.requireCpu(capabilities, required, "CentOS Stream", evidence);
        return cpu == HostSupportStatus.READY_FOR_RUNTIME_VALIDATION
                ? DistributionSupportRules.requireEnforcingSelinux(capabilities, "CentOS Stream", evidence) : cpu;
    }
}
