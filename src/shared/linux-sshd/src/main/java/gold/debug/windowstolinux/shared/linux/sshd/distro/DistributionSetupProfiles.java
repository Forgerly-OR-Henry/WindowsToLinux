package gold.debug.windowstolinux.shared.linux.sshd.distro;

import gold.debug.windowstolinux.shared.linux.error.LinuxOperationException;
import gold.debug.windowstolinux.shared.linux.sshd.distro.setup.DistributionPackageSets;
import gold.debug.windowstolinux.shared.model.capability.LinuxCapabilities;
import gold.debug.windowstolinux.shared.model.server.CpuMicroarchitectureLevel;
import gold.debug.windowstolinux.shared.model.server.LinuxDistro;
import gold.debug.windowstolinux.shared.model.server.LinuxSecurityModule;
import gold.debug.windowstolinux.shared.model.server.LinuxSecurityState;

import java.util.ArrayList;
import java.util.List;

/** Owns the data-driven setup profiles for every supported distribution. / 持有每种受支持发行版的数据驱动准备配置。 */
final class DistributionSetupProfiles {
    private DistributionSetupProfiles() {
    }

    static List<DistributionSetup> defaults() {
        return List.of(
                preparation(LinuxDistro.UBUNTU, DistributionSetupProfiles::ubuntu),
                preparation(LinuxDistro.DEBIAN, DistributionSetupProfiles::debian),
                preparation(LinuxDistro.CENTOS_STREAM, DistributionSetupProfiles::centosStream),
                preparation(LinuxDistro.ROCKY_LINUX, DistributionSetupProfiles::rocky),
                preparation(LinuxDistro.ALMALINUX, DistributionSetupProfiles::almaLinux),
                preparation(LinuxDistro.ORACLE_LINUX, DistributionSetupProfiles::oracleLinux));
    }

    private static String ubuntu(LinuxCapabilities facts, String username) {
        String version = facts.version();
        if (!("22.04".equals(version) || "24.04".equals(version))) {
            throw new IllegalArgumentException("Ubuntu preparation supports only 22.04 or 24.04");
        }
        List<String> packages = new ArrayList<>(DistributionPackageSets.APT_BASE);
        if ("24.04".equals(version)) packages.addAll(DistributionPackageSets.UBUNTU_2404_EXPERIMENTAL);
        EcosystemCapabilityChecks runtime = "24.04".equals(version)
                ? EcosystemCapabilityChecks.UBUNTU_2404 : EcosystemCapabilityChecks.UBUNTU_2204;
        return AptSetupRenderer.render(new DistributionSetupProfile(
                "ubuntu", "", version, "amd64", CpuMicroarchitectureLevel.X86_64_V1,
                packages, runtime), username);
    }

    private static String debian(LinuxCapabilities facts, String username) {
        if (!"13".equals(facts.version())) {
            throw new IllegalArgumentException("Debian preparation supports only stable 13");
        }
        return AptSetupRenderer.render(new DistributionSetupProfile(
                "debian", "", facts.version(), "amd64", CpuMicroarchitectureLevel.X86_64_V1,
                DistributionPackageSets.APT_BASE, EcosystemCapabilityChecks.DEBIAN_13), username);
    }

    private static String centosStream(LinuxCapabilities facts, String username) throws LinuxOperationException {
        requireEnterpriseSecurity(facts);
        String version = facts.version();
        if (!("9".equals(version) || "10".equals(version))) {
            throw new IllegalArgumentException("CentOS Stream preparation supports only 9 or 10");
        }
        boolean ten = "10".equals(version);
        return DnfSetupRenderer.render(new DistributionSetupProfile(
                "centos", "", version, "x86_64",
                ten ? CpuMicroarchitectureLevel.X86_64_V3 : CpuMicroarchitectureLevel.X86_64_V1,
                DistributionPackageSets.enterprise(version),
                ten ? EcosystemCapabilityChecks.ENTERPRISE_10 : EcosystemCapabilityChecks.ENTERPRISE_9), username);
    }

    private static String rocky(LinuxCapabilities facts, String username) throws LinuxOperationException {
        requireEnterpriseSecurity(facts);
        String version = facts.version();
        if (!("9.8".equals(version) || "10.2".equals(version))) {
            throw new IllegalArgumentException("Rocky Linux preparation supports only maintained 9.8 or 10.2");
        }
        boolean ten = version.startsWith("10.");
        return DnfSetupRenderer.render(new DistributionSetupProfile(
                "rocky", "", version, "x86_64",
                ten ? CpuMicroarchitectureLevel.X86_64_V3 : CpuMicroarchitectureLevel.X86_64_V1,
                DistributionPackageSets.enterprise(ten ? "10" : "9"),
                ten ? EcosystemCapabilityChecks.ENTERPRISE_10 : EcosystemCapabilityChecks.ENTERPRISE_9), username);
    }

    private static String almaLinux(LinuxCapabilities facts, String username) throws LinuxOperationException {
        requireEnterpriseSecurity(facts);
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
                DistributionPackageSets.enterprise(ten ? "10" : "9"),
                ten ? EcosystemCapabilityChecks.ENTERPRISE_10 : EcosystemCapabilityChecks.ENTERPRISE_9), username);
    }

    private static String oracleLinux(LinuxCapabilities facts, String username) throws LinuxOperationException {
        requireEnterpriseSecurity(facts);
        String version = facts.version();
        if (!("9.7".equals(version) || "10.2".equals(version))) {
            throw new IllegalArgumentException("Oracle Linux preparation supports only current 9.7 or 10.2 updates");
        }
        boolean ten = version.startsWith("10.");
        return DnfSetupRenderer.render(new DistributionSetupProfile(
                "ol", "", version, "x86_64",
                ten ? CpuMicroarchitectureLevel.X86_64_V3 : CpuMicroarchitectureLevel.X86_64_V1,
                DistributionPackageSets.enterprise(ten ? "10" : "9"),
                ten ? EcosystemCapabilityChecks.ENTERPRISE_10 : EcosystemCapabilityChecks.ENTERPRISE_9), username);
    }

    private static DistributionSetup preparation(LinuxDistro distro, Renderer renderer) {
        return new DistributionSetup() {
            @Override public LinuxDistro distro() { return distro; }
            @Override public String render(LinuxCapabilities capabilities, String username) throws LinuxOperationException {
                return renderer.render(capabilities, username);
            }
        };
    }

    private static void requireEnterpriseSecurity(LinuxCapabilities capabilities) throws LinuxOperationException {
        if (capabilities.securityPosture().module() != LinuxSecurityModule.SELINUX
                || capabilities.securityPosture().state() != LinuxSecurityState.ENFORCING) {
            throw LinuxOperationException.localized("linux.error.environmentUnsupportedDistro",
                    "Enterprise Linux automatic preparation requires collected SELinux enforcing evidence");
        }
    }

    @FunctionalInterface
    private interface Renderer {
        String render(LinuxCapabilities capabilities, String username) throws LinuxOperationException;
    }
}
