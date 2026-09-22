package gold.debug.windowstolinux.shared.standard.deploy.support.distro;

import java.util.List;

import gold.debug.windowstolinux.shared.model.capability.LinuxCapabilityFacts;
import gold.debug.windowstolinux.shared.model.server.CpuMicroarchitectureLevel;
import gold.debug.windowstolinux.shared.standard.deploy.contract.result.compatibility.HostSupportStatus;

/**
 * CentOS Stream compatibility policy. / CentOS Stream 兼容性策略。
 */
final class CentosStreamSupportPolicy implements DistributionSupportPolicy {
    /**
     * Evaluates the supplied compatibility evidence. / 评估提供的兼容性证据。
     *
     * @param capabilities observed target tools and runtime capabilities / 目标工具及运行能力观测
     * @param evidence observations supporting the reported result / 支持所报告结果的观测证据
     * @return constructed or resolved host support status / 构造或解析得到的主机支持状态
     */
    @Override
    public HostSupportStatus evaluate(LinuxCapabilityFacts capabilities, List<String> evidence) {
        if (!"dnf".equals(capabilities.packageManager())
                || !("9".equals(capabilities.version()) || "10".equals(capabilities.version()))
                || !"x86_64".equals(capabilities.packageArchitecture())) {
            return DistributionSupportRules.unsupported("CentOS Stream must be 9 or 10 with dnf and x86_64 packages",
                    evidence);
        }
        CpuMicroarchitectureLevel required = "10".equals(capabilities.version())
                ? CpuMicroarchitectureLevel.X86_64_V3
                : CpuMicroarchitectureLevel.X86_64_V2;
        HostSupportStatus cpu = DistributionSupportRules.requireCpu(capabilities, required, "CentOS Stream", evidence);
        return cpu == HostSupportStatus.READY_FOR_RUNTIME_VALIDATION
                ? DistributionSupportRules.requireEnforcingSelinux(capabilities, "CentOS Stream", evidence)
                : cpu;
    }
}
