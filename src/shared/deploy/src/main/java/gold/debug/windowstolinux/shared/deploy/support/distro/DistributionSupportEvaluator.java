package gold.debug.windowstolinux.shared.deploy.support.distro;

import gold.debug.windowstolinux.shared.deploy.result.compatibility.HostSupportStatus;
import gold.debug.windowstolinux.shared.model.capability.LinuxCapabilityFacts;

import java.util.List;

/** Routes a classified distribution to exactly one independent policy. / 将已分类发行版路由到恰好一个独立策略。 */
public final class DistributionSupportEvaluator {
    private static final DistributionSupportPolicy UBUNTU = new UbuntuSupportPolicy();
    private static final DistributionSupportPolicy DEBIAN = new DebianSupportPolicy();
    private static final DistributionSupportPolicy CENTOS = new CentosStreamSupportPolicy();
    private static final DistributionSupportPolicy ROCKY = new RockyLinuxSupportPolicy();
    private static final DistributionSupportPolicy ALMA = new AlmaLinuxSupportPolicy();
    private static final DistributionSupportPolicy ORACLE = new OracleLinuxSupportPolicy();

    private DistributionSupportEvaluator() {
    }

    /** Evaluates the supplied compatibility evidence. / 评估提供的兼容性证据。 */
    public static HostSupportStatus evaluate(LinuxCapabilityFacts capabilities, List<String> evidence) {
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
                yield HostSupportStatus.LEGACY_RISK_CONFIRMATION_REQUIRED;
            }
            case OTHER -> DistributionSupportRules.unsupported(
                    "distribution is outside the typed deployment matrix", evidence);
        };
    }
}
