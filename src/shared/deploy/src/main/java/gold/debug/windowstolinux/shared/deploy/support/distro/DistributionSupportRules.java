package gold.debug.windowstolinux.shared.deploy.support.distro;

import gold.debug.windowstolinux.shared.deploy.result.compatibility.HostSupportStatus;
import gold.debug.windowstolinux.shared.model.server.CpuMicroarchitectureLevel;
import gold.debug.windowstolinux.shared.model.capability.LinuxCapabilityFacts;
import gold.debug.windowstolinux.shared.model.server.security.LinuxSecurityModuleType;
import gold.debug.windowstolinux.shared.model.server.security.LinuxSecurityState;

import java.util.List;

/** Fixed checks shared mechanically by independent distribution policies. / 独立发行版策略机械共享的固定检查。 */
final class DistributionSupportRules {
    private DistributionSupportRules() {
    }

    /** Performs the {@code requireCpu} operation. / 执行 {@code requireCpu} 操作。 */
    public static HostSupportStatus requireCpu(LinuxCapabilityFacts capabilities, CpuMicroarchitectureLevel required,
                                  String distribution, List<String> evidence) {
        if (!capabilities.cpuMicroarchitecture().supports(required)) {
            evidence.add(distribution + " requires " + cpuName(required) + "; observed "
                    + cpuName(capabilities.cpuMicroarchitecture()));
            return HostSupportStatus.REQUIRES_CPU_REVIEW;
        }
        evidence.add(distribution + " CPU baseline satisfied: " + cpuName(capabilities.cpuMicroarchitecture()));
        return HostSupportStatus.READY_FOR_RUNTIME_VALIDATION;
    }

    /** Performs the {@code requireEnforcingSelinux} operation. / 执行 {@code requireEnforcingSelinux} 操作。 */
    public static HostSupportStatus requireEnforcingSelinux(LinuxCapabilityFacts capabilities, String distribution,
                                               List<String> evidence) {
        if (capabilities.securityPosture().module() != LinuxSecurityModuleType.SELINUX
                || capabilities.securityPosture().state() != LinuxSecurityState.ENFORCING) {
            evidence.add(distribution + " requires SELinux enforcing evidence before target mutation");
            return HostSupportStatus.REQUIRES_SECURITY_REVIEW;
        }
        evidence.add(distribution + " SELinux state is enforcing");
        return HostSupportStatus.READY_FOR_RUNTIME_VALIDATION;
    }

    /** Performs the {@code unsupported} operation. / 执行 {@code unsupported} 操作。 */
    public static HostSupportStatus unsupported(String detail, List<String> evidence) {
        evidence.add(detail);
        return HostSupportStatus.UNSUPPORTED;
    }

    private static String cpuName(CpuMicroarchitectureLevel level) {
        return level.name().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
    }
}
