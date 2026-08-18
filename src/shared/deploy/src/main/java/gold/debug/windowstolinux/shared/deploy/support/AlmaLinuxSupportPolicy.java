package gold.debug.windowstolinux.shared.deploy.support;

import gold.debug.windowstolinux.shared.deploy.support.HostSupport;
import gold.debug.windowstolinux.shared.deploy.support.DistributionSupportRules;
import gold.debug.windowstolinux.shared.deploy.support.DistributionSupportPolicy;

import gold.debug.windowstolinux.shared.model.server.CpuMicroarchitectureLevel;
import gold.debug.windowstolinux.shared.model.capability.LinuxCapabilities;

import java.util.List;

/** AlmaLinux compatibility policy including its explicit x86-64-v2 variant boundary. / 包含明确 x86-64-v2 变体边界的 AlmaLinux 兼容性策略。 */
final class AlmaLinuxSupportPolicy implements DistributionSupportPolicy {
    /** Evaluates the supplied compatibility evidence. / 评估提供的兼容性证据。 */
    @Override
    public HostSupport evaluate(LinuxCapabilities capabilities, List<String> evidence) {
        if (!"dnf".equals(capabilities.packageManager())
                || !("9.8".equals(capabilities.version()) || "10.2".equals(capabilities.version()))) {
            return DistributionSupportRules.unsupported(
                    "AlmaLinux must be maintained 9.8 or 10.2 with dnf", evidence);
        }
        if ("9.8".equals(capabilities.version())) {
            if (!"x86_64".equals(capabilities.packageArchitecture())) {
                return DistributionSupportRules.unsupported("AlmaLinux 9.8 requires x86_64 packages", evidence);
            }
            return enterprise(capabilities, CpuMicroarchitectureLevel.X86_64_V1, evidence);
        }
        if ("x86_64_v2".equals(capabilities.packageArchitecture())) {
            HostSupport cpu = DistributionSupportRules.requireCpu(
                    capabilities, CpuMicroarchitectureLevel.X86_64_V2, "AlmaLinux 10 x86_64_v2", evidence);
            if (cpu != HostSupport.READY_FOR_RUNTIME_VALIDATION) {
                return cpu;
            }
            evidence.add("AlmaLinux 10 x86_64_v2 requires a separate third-party dependency CPU review");
            return HostSupport.REQUIRES_CPU_REVIEW;
        }
        if (!"x86_64".equals(capabilities.packageArchitecture())) {
            return DistributionSupportRules.unsupported(
                    "AlmaLinux 10.2 package architecture must be x86_64 or x86_64_v2", evidence);
        }
        return enterprise(capabilities, CpuMicroarchitectureLevel.X86_64_V3, evidence);
    }

    private static HostSupport enterprise(LinuxCapabilities capabilities, CpuMicroarchitectureLevel cpuLevel,
                                          List<String> evidence) {
        HostSupport cpu = DistributionSupportRules.requireCpu(capabilities, cpuLevel, "AlmaLinux", evidence);
        return cpu == HostSupport.READY_FOR_RUNTIME_VALIDATION
                ? DistributionSupportRules.requireEnforcingSelinux(capabilities, "AlmaLinux", evidence) : cpu;
    }
}
