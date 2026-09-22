package gold.debug.windowstolinux.shared.standard.deploy.support.distro;

import java.util.List;

import gold.debug.windowstolinux.shared.model.capability.LinuxCapabilityFacts;
import gold.debug.windowstolinux.shared.model.server.CpuMicroarchitectureLevel;
import gold.debug.windowstolinux.shared.model.server.security.LinuxSecurityModuleType;
import gold.debug.windowstolinux.shared.model.server.security.LinuxSecurityState;
import gold.debug.windowstolinux.shared.standard.deploy.contract.result.compatibility.HostSupportStatus;

/**
 * Fixed checks shared mechanically by independent distribution policies. / 独立发行版策略机械共享的固定检查。
 */
final class DistributionSupportRules {
    /**
     * Prevents instantiation of this static contract helper.
     * <p>防止实例化当前静态契约辅助类。
     */
    private DistributionSupportRules() {
    }

    /**
     * Validates and returns cpu.
     * <p>校验并返回CPU 能力。
     *
     * @param capabilities observed target tools and runtime capabilities / 目标工具及运行能力观测
     * @param required whether the whole application requires this component / 整体应用是否需要此组件
     * @param distribution distribution / 发行版
     * @param evidence observations supporting the reported result / 支持所报告结果的观测证据
     * @return constructed or resolved host support status / 构造或解析得到的主机支持状态
     */
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

    /**
     * Validates and returns enforcing selinux.
     * <p>校验并返回强制Selinux。
     *
     * @param capabilities observed target tools and runtime capabilities / 目标工具及运行能力观测
     * @param distribution distribution / 发行版
     * @param evidence observations supporting the reported result / 支持所报告结果的观测证据
     * @return constructed or resolved host support status / 构造或解析得到的主机支持状态
     */
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

    /**
     * Builds the rejection for a capability outside the supported contract.
     * <p>为超出支持契约的能力构建拒绝结果。
     *
     * @param detail detail / 详情
     * @param evidence observations supporting the reported result / 支持所报告结果的观测证据
     * @return the rejection for a capability outside the supported contract / 为超出支持契约的能力构建拒绝结果
     */
    public static HostSupportStatus unsupported(String detail, List<String> evidence) {
        evidence.add(detail);
        return HostSupportStatus.UNSUPPORTED;
    }

    /**
     * Formats a CPU microarchitecture enum as a lowercase hyphen-separated evidence token.
     * <p>将 CPU 微架构枚举格式化为小写连字符分隔的证据令牌。
     *
     * @param level evidence-backed support level / 证据支撑的支持等级
     * @return a CPU microarchitecture enum as a lowercase hyphen-separated evidence token / 将 CPU 微架构枚举格式化为小写连字符分隔的证据令牌
     */
    private static String cpuName(CpuMicroarchitectureLevel level) {
        return level.name().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
    }
}
