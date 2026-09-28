package gold.debug.windowstolinux.mod.deployment.service.standard.deploy.support.distro;

import java.util.List;

import gold.debug.windowstolinux.mod.deployment.service.core.model.capability.DeploymentHostSupportStatus;
import gold.debug.windowstolinux.mod.deployment.service.core.model.capability.DeploymentLinuxCapabilityFacts;
import gold.debug.windowstolinux.mod.deployment.service.core.model.server.DeploymentCpuMicroarchitectureLevel;

/**
 * Debian stable compatibility policy. / Debian 稳定版兼容性策略。
 */
final class DeploymentDebianSupportPolicy implements DeploymentDistributionSupportPolicy {
    /**
     * Evaluates the supplied compatibility evidence. / 评估提供的兼容性证据。
     *
     * @param capabilities observed target tools and runtime capabilities / 目标工具及运行能力观测
     * @param evidence observations supporting the reported result / 支持所报告结果的观测证据
     * @return constructed or resolved host support status / 构造或解析得到的主机支持状态
     */
    @Override
    public DeploymentHostSupportStatus evaluate(DeploymentLinuxCapabilityFacts capabilities, List<String> evidence) {
        if (!"13".equals(capabilities.version()) || !"apt".equals(capabilities.packageManager())
                || !"amd64".equals(capabilities.packageArchitecture())) {
            return DeploymentDistributionSupportRules
                    .unsupported("Debian must be stable 13 with apt and amd64 packages", evidence);
        }
        return DeploymentDistributionSupportRules.requireCpu(capabilities,
                DeploymentCpuMicroarchitectureLevel.X86_64_V1, "Debian", evidence);
    }
}
