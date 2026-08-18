package gold.debug.windowstolinux.shared.deploy.support.distro.policy.rocky;

import gold.debug.windowstolinux.shared.deploy.support.HostSupport;
import gold.debug.windowstolinux.shared.deploy.support.distro.rule.DistributionSupportRules;
import gold.debug.windowstolinux.shared.deploy.support.distro.spi.DistributionSupportPolicy;

import gold.debug.windowstolinux.shared.model.server.CpuMicroarchitectureLevel;
import gold.debug.windowstolinux.shared.model.server.LinuxCapabilities;

import java.util.List;

/** Rocky Linux compatibility policy frozen to maintained minor releases. / 固定到受维护小版本的 Rocky Linux 兼容性策略。 */
public final class RockyLinuxSupportPolicy implements DistributionSupportPolicy {
    /** Evaluates the supplied compatibility evidence. / 评估提供的兼容性证据。 */
    @Override
    public HostSupport evaluate(LinuxCapabilities capabilities, List<String> evidence) {
        if (!"dnf".equals(capabilities.packageManager()) || !"x86_64".equals(capabilities.packageArchitecture())
                || !("9.8".equals(capabilities.version()) || "10.2".equals(capabilities.version()))) {
            return DistributionSupportRules.unsupported(
                    "Rocky Linux must be maintained 9.8 or 10.2 with dnf and x86_64 packages", evidence);
        }
        CpuMicroarchitectureLevel required = capabilities.version().startsWith("10.")
                ? CpuMicroarchitectureLevel.X86_64_V3 : CpuMicroarchitectureLevel.X86_64_V1;
        HostSupport cpu = DistributionSupportRules.requireCpu(capabilities, required, "Rocky Linux", evidence);
        return cpu == HostSupport.READY_FOR_RUNTIME_VALIDATION
                ? DistributionSupportRules.requireEnforcingSelinux(capabilities, "Rocky Linux", evidence) : cpu;
    }
}
