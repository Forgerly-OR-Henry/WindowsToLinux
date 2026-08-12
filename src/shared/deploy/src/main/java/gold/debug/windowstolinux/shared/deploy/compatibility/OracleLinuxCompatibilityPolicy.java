package gold.debug.windowstolinux.shared.deploy.compatibility;

import gold.debug.windowstolinux.shared.model.server.CpuMicroarchitectureLevel;
import gold.debug.windowstolinux.shared.model.server.LinuxCapabilities;

import java.util.List;

/** Oracle Linux rolling-major compatibility policy. / Oracle Linux 滚动主版本兼容性策略。 */
final class OracleLinuxCompatibilityPolicy implements DistributionCompatibilityPolicy {
    @Override
    public HostSupport evaluate(LinuxCapabilities capabilities, List<String> evidence) {
        if (!"dnf".equals(capabilities.packageManager()) || !"x86_64".equals(capabilities.packageArchitecture())
                || !("9.7".equals(capabilities.version()) || "10.2".equals(capabilities.version()))) {
            return DistributionPolicySupport.unsupported(
                    "Oracle Linux must be the current 9.7 or 10.2 update with dnf and x86_64 packages", evidence);
        }
        boolean ten = capabilities.version().startsWith("10.");
        CpuMicroarchitectureLevel required = ten
                ? CpuMicroarchitectureLevel.X86_64_V3 : CpuMicroarchitectureLevel.X86_64_V1;
        HostSupport cpu = DistributionPolicySupport.requireCpu(capabilities, required, "Oracle Linux", evidence);
        if (cpu != HostSupport.READY_FOR_RUNTIME_VALIDATION) {
            return cpu;
        }
        evidence.add("Oracle Linux update releases are rolling snapshots; current package updates remain required");
        return DistributionPolicySupport.requireEnforcingSelinux(capabilities, "Oracle Linux", evidence);
    }
}
