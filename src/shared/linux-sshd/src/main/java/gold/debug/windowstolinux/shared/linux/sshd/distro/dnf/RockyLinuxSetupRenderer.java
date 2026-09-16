package gold.debug.windowstolinux.shared.linux.sshd.distro.dnf;

import gold.debug.windowstolinux.shared.linux.sshd.distro.DistributionSetupRenderer;
import gold.debug.windowstolinux.shared.linux.sshd.distro.contract.profile.DistributionSetupProfile;
import gold.debug.windowstolinux.shared.linux.sshd.distro.contract.profile.EcosystemCapabilityProfile;
import gold.debug.windowstolinux.shared.model.capability.LinuxCapabilityFacts;
import gold.debug.windowstolinux.shared.model.server.CpuMicroarchitectureLevel;
import gold.debug.windowstolinux.shared.model.server.LinuxDistroType;
import gold.debug.windowstolinux.shared.linux.error.LinuxOperationException;

/** Owns Rocky Linux preparation differences. / 持有 Rocky Linux 专属的环境准备差异。 */
public final class RockyLinuxSetupRenderer implements DistributionSetupRenderer {
    /** Returns the prepared distribution. / 返回所准备的发行版。 */
    @Override
    public LinuxDistroType distro() {
        return LinuxDistroType.ROCKY_LINUX;
    }

    /** Renders the fixed distribution preparation. / 渲染该发行版的固定环境准备脚本。 */
    @Override
    public String render(LinuxCapabilityFacts facts, String username) throws LinuxOperationException {
        DnfSetupRenderer.requireEnterpriseSecurity(facts);
        String version = facts.version();
        if (!("9.8".equals(version) || "10.2".equals(version))) {
            throw new IllegalArgumentException("Rocky Linux preparation supports only maintained 9.8 or 10.2");
        }
        boolean ten = version.startsWith("10.");
        return DnfSetupRenderer.render(new DistributionSetupProfile(
                "rocky", "", version, "x86_64",
                ten ? CpuMicroarchitectureLevel.X86_64_V3 : CpuMicroarchitectureLevel.X86_64_V1,
                DnfPackageSets.enterprise(ten ? "10" : "9"),
                ten ? EcosystemCapabilityProfile.ENTERPRISE_10 : EcosystemCapabilityProfile.ENTERPRISE_9), username);
    }
}
