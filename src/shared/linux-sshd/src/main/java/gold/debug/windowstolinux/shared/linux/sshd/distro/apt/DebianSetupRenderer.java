package gold.debug.windowstolinux.shared.linux.sshd.distro.apt;

import gold.debug.windowstolinux.shared.linux.sshd.distro.DistributionSetupRenderer;
import gold.debug.windowstolinux.shared.linux.sshd.distro.contract.profile.DistributionSetupProfile;
import gold.debug.windowstolinux.shared.linux.sshd.distro.contract.profile.EcosystemCapabilityProfile;
import gold.debug.windowstolinux.shared.model.capability.LinuxCapabilityFacts;
import gold.debug.windowstolinux.shared.model.server.CpuMicroarchitectureLevel;
import gold.debug.windowstolinux.shared.model.server.LinuxDistroType;

/** Owns Debian preparation differences. / 持有 Debian 专属的环境准备差异。 */
public final class DebianSetupRenderer implements DistributionSetupRenderer {
    /** Returns the prepared distribution. / 返回所准备的发行版。 */
    @Override
    public LinuxDistroType distro() {
        return LinuxDistroType.DEBIAN;
    }

    /** Renders the fixed distribution preparation. / 渲染该发行版的固定环境准备脚本。 */
    @Override
    public String render(LinuxCapabilityFacts facts, String username) {
        if (!"13".equals(facts.version())) {
            throw new IllegalArgumentException("Debian preparation supports only stable 13");
        }
        return AptSetupRenderer.render(new DistributionSetupProfile(
                "debian", "", facts.version(), "amd64", CpuMicroarchitectureLevel.X86_64_V1,
                AptPackageSets.BASE, EcosystemCapabilityProfile.DEBIAN_13), username, "");
    }
}
