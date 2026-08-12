package gold.debug.windowstolinux.shared.deploy.compatibility;

import gold.debug.windowstolinux.shared.model.server.CpuMicroarchitectureLevel;
import gold.debug.windowstolinux.shared.model.server.LinuxCapabilities;

import java.util.List;

/** Ubuntu LTS compatibility policy. / Ubuntu LTS 兼容性策略。 */
final class UbuntuCompatibilityPolicy implements DistributionCompatibilityPolicy {
    @Override
    public HostSupport evaluate(LinuxCapabilities capabilities, List<String> evidence) {
        if (!"apt".equals(capabilities.packageManager())
                || !("22.04".equals(capabilities.version()) || "24.04".equals(capabilities.version()))
                || !"amd64".equals(capabilities.packageArchitecture())) {
            return DistributionPolicySupport.unsupported(
                    "Ubuntu must be 22.04 or 24.04 with apt and amd64 packages", evidence);
        }
        return DistributionPolicySupport.requireCpu(
                capabilities, CpuMicroarchitectureLevel.X86_64_V1, "Ubuntu", evidence);
    }
}
