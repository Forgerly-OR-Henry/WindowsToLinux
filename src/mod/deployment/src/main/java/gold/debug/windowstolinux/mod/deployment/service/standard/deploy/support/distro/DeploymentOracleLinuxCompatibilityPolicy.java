package gold.debug.windowstolinux.mod.deployment.service.standard.deploy.support.distro;

import java.util.List;

import gold.debug.windowstolinux.mod.deployment.service.core.model.capability.DeploymentHostCapabilityStatus;
import gold.debug.windowstolinux.mod.deployment.service.core.model.capability.DeploymentLinuxCapabilityFacts;
import gold.debug.windowstolinux.mod.deployment.service.core.model.server.DeploymentCpuMicroarchitectureLevel;

/**
 * Oracle Linux rolling-major compatibility policy. / Oracle Linux 滚动主版本兼容性策略。
 */
final class DeploymentOracleLinuxCompatibilityPolicy implements DeploymentDistributionCompatibilityPolicy {
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
                || !("9.7".equals(capabilities.version()) || "10.2".equals(capabilities.version()))) {
            return DeploymentDistributionCompatibilityRules.unsupported(
                    "Oracle Linux must be the current 9.7 or 10.2 update with dnf and x86_64 packages", evidence);
        }
        boolean ten = capabilities.version().startsWith("10.");
        DeploymentCpuMicroarchitectureLevel required = ten
                ? DeploymentCpuMicroarchitectureLevel.X86_64_V3
                : DeploymentCpuMicroarchitectureLevel.X86_64_V1;
        DeploymentHostCapabilityStatus cpu = DeploymentDistributionCompatibilityRules.requireCpu(capabilities, required,
                "Oracle Linux", evidence);
        if (cpu != DeploymentHostCapabilityStatus.READY_FOR_RUNTIME_VALIDATION) {
            return cpu;
        }
        evidence.add("Oracle Linux update releases are rolling snapshots; current package updates remain required");
        return DeploymentDistributionCompatibilityRules.requireEnforcingSelinux(capabilities, "Oracle Linux", evidence);
    }
}
