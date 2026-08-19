package gold.debug.windowstolinux.shared.linux.sshd.distro.dnf;

import gold.debug.windowstolinux.shared.linux.error.LinuxOperationException;
import gold.debug.windowstolinux.shared.linux.sshd.distro.DistributionSetupRenderer;
import gold.debug.windowstolinux.shared.linux.sshd.distro.profile.DistributionSetupProfile;
import gold.debug.windowstolinux.shared.linux.sshd.distro.profile.EcosystemCapabilityProfile;
import gold.debug.windowstolinux.shared.model.capability.LinuxCapabilityFacts;
import gold.debug.windowstolinux.shared.model.server.CpuMicroarchitectureLevel;
import gold.debug.windowstolinux.shared.model.server.LinuxDistroType;
import gold.debug.windowstolinux.shared.model.server.security.LinuxSecurityModuleType;
import gold.debug.windowstolinux.shared.model.server.security.LinuxSecurityState;

import java.util.List;

/** Owns Enterprise Linux profile differences while sharing DNF mechanics. / 持有企业 Linux 配置差异，同时共享 DNF 机械流程。 */
public final class EnterpriseLinuxSetupCatalog {
    private EnterpriseLinuxSetupCatalog() {
    }

    public static List<DistributionSetupRenderer> defaults() {
        return List.of(
                DistributionSetupRenderer.of(LinuxDistroType.CENTOS_STREAM, EnterpriseLinuxSetupCatalog::centosStream),
                DistributionSetupRenderer.of(LinuxDistroType.ROCKY_LINUX, EnterpriseLinuxSetupCatalog::rocky),
                DistributionSetupRenderer.of(LinuxDistroType.ALMALINUX, EnterpriseLinuxSetupCatalog::almaLinux),
                DistributionSetupRenderer.of(LinuxDistroType.ORACLE_LINUX, EnterpriseLinuxSetupCatalog::oracleLinux));
    }

    private static String centosStream(LinuxCapabilityFacts facts, String username) throws LinuxOperationException {
        requireEnterpriseSecurity(facts);
        String version = facts.version();
        if (!("9".equals(version) || "10".equals(version))) {
            throw new IllegalArgumentException("CentOS Stream preparation supports only 9 or 10");
        }
        boolean ten = "10".equals(version);
        return DnfSetupRenderer.render(new DistributionSetupProfile(
                "centos", "", version, "x86_64",
                ten ? CpuMicroarchitectureLevel.X86_64_V3 : CpuMicroarchitectureLevel.X86_64_V1,
                DnfPackageSets.enterprise(version),
                ten ? EcosystemCapabilityProfile.ENTERPRISE_10 : EcosystemCapabilityProfile.ENTERPRISE_9), username);
    }

    private static String rocky(LinuxCapabilityFacts facts, String username) throws LinuxOperationException {
        requireEnterpriseSecurity(facts);
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

    private static String almaLinux(LinuxCapabilityFacts facts, String username) throws LinuxOperationException {
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
                DnfPackageSets.enterprise(ten ? "10" : "9"),
                ten ? EcosystemCapabilityProfile.ENTERPRISE_10 : EcosystemCapabilityProfile.ENTERPRISE_9), username);
    }

    private static String oracleLinux(LinuxCapabilityFacts facts, String username) throws LinuxOperationException {
        requireEnterpriseSecurity(facts);
        String version = facts.version();
        if (!("9.7".equals(version) || "10.2".equals(version))) {
            throw new IllegalArgumentException("Oracle Linux preparation supports only current 9.7 or 10.2 updates");
        }
        boolean ten = version.startsWith("10.");
        return DnfSetupRenderer.render(new DistributionSetupProfile(
                "ol", "", version, "x86_64",
                ten ? CpuMicroarchitectureLevel.X86_64_V3 : CpuMicroarchitectureLevel.X86_64_V1,
                DnfPackageSets.enterprise(ten ? "10" : "9"),
                ten ? EcosystemCapabilityProfile.ENTERPRISE_10 : EcosystemCapabilityProfile.ENTERPRISE_9), username);
    }

    private static void requireEnterpriseSecurity(LinuxCapabilityFacts capabilities) throws LinuxOperationException {
        if (capabilities.securityPosture().module() != LinuxSecurityModuleType.SELINUX
                || capabilities.securityPosture().state() != LinuxSecurityState.ENFORCING) {
            throw LinuxOperationException.localized("linux.error.environmentUnsupportedDistro",
                    "Enterprise Linux automatic preparation requires collected SELinux enforcing evidence");
        }
    }
}
