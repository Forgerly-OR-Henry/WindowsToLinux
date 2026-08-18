package gold.debug.windowstolinux.shared.deploy.support;

import gold.debug.windowstolinux.shared.deploy.support.HostSupport;
import gold.debug.windowstolinux.shared.deploy.support.DistributionSupportRules;
import gold.debug.windowstolinux.shared.deploy.support.DistributionSupportPolicy;

import gold.debug.windowstolinux.shared.model.server.CpuMicroarchitectureLevel;
import gold.debug.windowstolinux.shared.model.capability.LinuxCapabilities;

import java.util.List;

/** Ubuntu LTS compatibility policy. / Ubuntu LTS 兼容性策略。 */
final class UbuntuSupportPolicy implements DistributionSupportPolicy {
    /** Evaluates the supplied compatibility evidence. / 评估提供的兼容性证据。 */
    @Override
    public HostSupport evaluate(LinuxCapabilities capabilities, List<String> evidence) {
        if (!"apt".equals(capabilities.packageManager())
                || !("22.04".equals(capabilities.version()) || "24.04".equals(capabilities.version()))
                || !"amd64".equals(capabilities.packageArchitecture())) {
            return DistributionSupportRules.unsupported(
                    "Ubuntu must be 22.04 or 24.04 with apt and amd64 packages", evidence);
        }
        return DistributionSupportRules.requireCpu(
                capabilities, CpuMicroarchitectureLevel.X86_64_V1, "Ubuntu", evidence);
    }
}
