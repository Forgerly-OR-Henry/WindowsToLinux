package gold.debug.windowstolinux.shared.deploy.compatibility;

import gold.debug.windowstolinux.shared.model.server.LinuxCapabilities;

import java.util.List;

/** Routes a classified distribution to exactly one independent policy. / 将已分类发行版路由到恰好一个独立策略。 */
final class DistributionSupportPolicies {
    private static final DistributionCompatibilityPolicy UBUNTU = new UbuntuCompatibilityPolicy();
    private static final DistributionCompatibilityPolicy DEBIAN = new DebianCompatibilityPolicy();
    private static final DistributionCompatibilityPolicy CENTOS = new CentosStreamCompatibilityPolicy();
    private static final DistributionCompatibilityPolicy ROCKY = new RockyLinuxCompatibilityPolicy();
    private static final DistributionCompatibilityPolicy ALMA = new AlmaLinuxCompatibilityPolicy();
    private static final DistributionCompatibilityPolicy ORACLE = new OracleLinuxCompatibilityPolicy();

    private DistributionSupportPolicies() {
    }

    static HostSupport evaluate(LinuxCapabilities capabilities, List<String> evidence) {
        evidence.add("package-architecture=" + capabilities.packageArchitecture()
                + "; cpu=" + capabilities.cpuMicroarchitecture()
                + "; security=" + capabilities.securityPosture().module() + "/" + capabilities.securityPosture().state()
                + "; firewall=" + capabilities.securityPosture().firewall() + "/"
                + capabilities.securityPosture().firewallState());
        return switch (capabilities.distro()) {
            case UBUNTU -> UBUNTU.evaluate(capabilities, evidence);
            case DEBIAN -> DEBIAN.evaluate(capabilities, evidence);
            case CENTOS_STREAM -> CENTOS.evaluate(capabilities, evidence);
            case ROCKY_LINUX -> ROCKY.evaluate(capabilities, evidence);
            case ALMALINUX -> ALMA.evaluate(capabilities, evidence);
            case ORACLE_LINUX -> ORACLE.evaluate(capabilities, evidence);
            case LEGACY_CENTOS -> {
                evidence.add("discontinued CentOS requires explicit maintenance and repository-risk acknowledgement");
                yield HostSupport.LEGACY_RISK_CONFIRMATION_REQUIRED;
            }
            case OTHER -> DistributionPolicySupport.unsupported(
                    "distribution is outside the typed deployment matrix", evidence);
        };
    }
}
