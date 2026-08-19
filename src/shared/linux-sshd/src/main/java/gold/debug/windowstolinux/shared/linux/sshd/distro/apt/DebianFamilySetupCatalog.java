package gold.debug.windowstolinux.shared.linux.sshd.distro.apt;

import gold.debug.windowstolinux.shared.linux.sshd.distro.DistributionSetupRenderer;
import gold.debug.windowstolinux.shared.linux.sshd.distro.profile.DistributionSetupProfile;
import gold.debug.windowstolinux.shared.linux.sshd.distro.profile.EcosystemCapabilityProfile;
import gold.debug.windowstolinux.shared.model.capability.LinuxCapabilityFacts;
import gold.debug.windowstolinux.shared.model.server.CpuMicroarchitectureLevel;
import gold.debug.windowstolinux.shared.model.server.LinuxDistroType;

import java.util.ArrayList;
import java.util.List;

/** Owns Debian-family profile differences while sharing APT mechanics. / 持有 Debian 家族配置差异，同时共享 APT 机械流程。 */
public final class DebianFamilySetupCatalog {
    private DebianFamilySetupCatalog() {
    }

    public static List<DistributionSetupRenderer> defaults() {
        return List.of(
                DistributionSetupRenderer.of(LinuxDistroType.UBUNTU, DebianFamilySetupCatalog::ubuntu),
                DistributionSetupRenderer.of(LinuxDistroType.DEBIAN, DebianFamilySetupCatalog::debian));
    }

    private static String ubuntu(LinuxCapabilityFacts facts, String username) {
        String version = facts.version();
        if (!("22.04".equals(version) || "24.04".equals(version))) {
            throw new IllegalArgumentException("Ubuntu preparation supports only 22.04 or 24.04");
        }
        List<String> packages = new ArrayList<>(AptPackageSets.BASE);
        if ("24.04".equals(version)) packages.addAll(AptPackageSets.UBUNTU_2404_EXPERIMENTAL);
        EcosystemCapabilityProfile runtime = "24.04".equals(version)
                ? EcosystemCapabilityProfile.UBUNTU_2404 : EcosystemCapabilityProfile.UBUNTU_2204;
        return AptSetupRenderer.render(new DistributionSetupProfile(
                "ubuntu", "", version, "amd64", CpuMicroarchitectureLevel.X86_64_V1,
                packages, runtime), username);
    }

    private static String debian(LinuxCapabilityFacts facts, String username) {
        if (!"13".equals(facts.version())) {
            throw new IllegalArgumentException("Debian preparation supports only stable 13");
        }
        return AptSetupRenderer.render(new DistributionSetupProfile(
                "debian", "", facts.version(), "amd64", CpuMicroarchitectureLevel.X86_64_V1,
                AptPackageSets.BASE, EcosystemCapabilityProfile.DEBIAN_13), username);
    }
}
