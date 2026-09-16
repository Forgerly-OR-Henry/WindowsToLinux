package gold.debug.windowstolinux.shared.linux.sshd.distro.dnf;

import gold.debug.windowstolinux.shared.linux.sshd.distro.DistributionSetupRenderer;
import gold.debug.windowstolinux.shared.linux.sshd.distro.contract.profile.DistributionSetupProfile;
import gold.debug.windowstolinux.shared.linux.sshd.distro.contract.profile.EcosystemCapabilityProfile;
import gold.debug.windowstolinux.shared.model.capability.LinuxCapabilityFacts;
import gold.debug.windowstolinux.shared.model.server.CpuMicroarchitectureLevel;
import gold.debug.windowstolinux.shared.model.server.LinuxDistroType;
import gold.debug.windowstolinux.shared.linux.error.LinuxOperationException;

/** Owns AlmaLinux preparation differences. / 持有 AlmaLinux 专属的环境准备差异。 */
public final class AlmaLinuxSetupRenderer implements DistributionSetupRenderer {
    /** Returns the prepared distribution. / 返回所准备的发行版。 */
    @Override
    public LinuxDistroType distro() {
        return LinuxDistroType.ALMALINUX;
    }

    /** Renders the fixed distribution preparation. / 渲染该发行版的固定环境准备脚本。 */
    @Override
    public String render(LinuxCapabilityFacts facts, String username) throws LinuxOperationException {
        DnfSetupRenderer.requireEnterpriseSecurity(facts);
        String version = facts.version();
        if (!("9.8".equals(version) || "10.2".equals(version))
                || !"x86_64".equals(facts.packageArchitecture())) {
            throw new IllegalArgumentException(
                    "AlmaLinux automatic preparation supports maintained 9.8 or 10.2 default x86_64 only");
        }
        boolean ten = version.startsWith("10.");
        return DnfSetupRenderer.render(new DistributionSetupProfile(
                "almalinux", "", version, facts.packageArchitecture(),
                ten ? CpuMicroarchitectureLevel.X86_64_V3 : CpuMicroarchitectureLevel.X86_64_V1,
                DnfPackageSets.enterprise(ten ? "10" : "9"),
                ten ? EcosystemCapabilityProfile.ENTERPRISE_10 : EcosystemCapabilityProfile.ENTERPRISE_9), username);
    }
}
