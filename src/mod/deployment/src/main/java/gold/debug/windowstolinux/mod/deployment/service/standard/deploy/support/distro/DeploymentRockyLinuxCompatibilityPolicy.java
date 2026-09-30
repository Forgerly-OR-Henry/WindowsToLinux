package gold.debug.windowstolinux.mod.deployment.service.standard.deploy.support.distro;

import java.util.List;

import gold.debug.windowstolinux.mod.deployment.service.core.model.capability.DeploymentHostCapabilityStatus;
import gold.debug.windowstolinux.mod.deployment.service.core.model.capability.DeploymentLinuxCapabilityFacts;
import gold.debug.windowstolinux.mod.deployment.service.core.model.server.DeploymentCpuMicroarchitectureLevel;

/**
 * Rocky Linux compatibility policy frozen to maintained minor releases. / 固定到受维护小版本的 Rocky Linux 兼容性策略。
 */
final class DeploymentRockyLinuxCompatibilityPolicy implements DeploymentDistributionCompatibilityPolicy {
    /**
     * Evaluates the supplied compatibility evidence. / 评估提供的兼容性证据。
     *
     * @param capabilities observed target tools and runtime capabilities / 目标工具及运行能力观测
     * @param evidence observations supporting the reported result / 支持所报告结果的观测证据
     * @return constructed or resolved host support status / 构造或解析得到的主机支持状态
     */
    @Override
    public DeploymentHostCapabilityStatus evaluate(DeploymentLinuxCapabilityFacts capabilities, List<String> evidence) {
        if (!"dnf".equals(capabilities.packageManager()) || !"x86_64".equals(capabilities.packageArchitecture())
                || !("9.8".equals(capabilities.version()) || "10.2".equals(capabilities.version()))) {
            return DeploymentDistributionCompatibilityRules
                    .unsupported("Rocky Linux must be maintained 9.8 or 10.2 with dnf and x86_64 packages", evidence);
        }
        DeploymentCpuMicroarchitectureLevel required = capabilities.version().startsWith("10.")
                ? DeploymentCpuMicroarchitectureLevel.X86_64_V3
                : DeploymentCpuMicroarchitectureLevel.X86_64_V1;
        DeploymentHostCapabilityStatus cpu = DeploymentDistributionCompatibilityRules.requireCpu(capabilities, required,
                "Rocky Linux", evidence);
        return cpu == DeploymentHostCapabilityStatus.READY_FOR_RUNTIME_VALIDATION
                ? DeploymentDistributionCompatibilityRules.requireEnforcingSelinux(capabilities, "Rocky Linux",
                        evidence)
                : cpu;
    }
}
