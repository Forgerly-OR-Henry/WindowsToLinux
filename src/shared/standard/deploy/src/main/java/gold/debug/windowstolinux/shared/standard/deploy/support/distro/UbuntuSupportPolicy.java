package gold.debug.windowstolinux.shared.standard.deploy.support.distro;

import java.util.List;

import gold.debug.windowstolinux.shared.model.capability.LinuxCapabilityFacts;
import gold.debug.windowstolinux.shared.model.server.CpuMicroarchitectureLevel;
import gold.debug.windowstolinux.shared.standard.deploy.contract.result.compatibility.HostSupportStatus;

/**
 * Ubuntu LTS compatibility policy. / Ubuntu LTS 兼容性策略。
 */
final class UbuntuSupportPolicy implements DistributionSupportPolicy {
    /**
     * Evaluates the supplied compatibility evidence. / 评估提供的兼容性证据。
     *
     * @param capabilities observed target tools and runtime capabilities / 目标工具及运行能力观测
     * @param evidence observations supporting the reported result / 支持所报告结果的观测证据
     * @return constructed or resolved host support status / 构造或解析得到的主机支持状态
     */
    @Override
    public HostSupportStatus evaluate(LinuxCapabilityFacts capabilities, List<String> evidence) {
        if (!"apt".equals(capabilities.packageManager())
                || !("22.04".equals(capabilities.version()) || "24.04".equals(capabilities.version()))
                || !"amd64".equals(capabilities.packageArchitecture())) {
            return DistributionSupportRules.unsupported("Ubuntu must be 22.04 or 24.04 with apt and amd64 packages",
                    evidence);
        }
        return DistributionSupportRules.requireCpu(capabilities, CpuMicroarchitectureLevel.X86_64_V1, "Ubuntu",
                evidence);
    }
}
