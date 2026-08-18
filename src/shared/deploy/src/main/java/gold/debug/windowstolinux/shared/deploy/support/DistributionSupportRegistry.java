package gold.debug.windowstolinux.shared.deploy.support;

import gold.debug.windowstolinux.shared.deploy.support.HostSupport;
import gold.debug.windowstolinux.shared.deploy.support.AlmaLinuxSupportPolicy;
import gold.debug.windowstolinux.shared.deploy.support.CentosStreamSupportPolicy;
import gold.debug.windowstolinux.shared.deploy.support.DebianSupportPolicy;
import gold.debug.windowstolinux.shared.deploy.support.OracleLinuxSupportPolicy;
import gold.debug.windowstolinux.shared.deploy.support.RockyLinuxSupportPolicy;
import gold.debug.windowstolinux.shared.deploy.support.UbuntuSupportPolicy;
import gold.debug.windowstolinux.shared.deploy.support.DistributionSupportRules;
import gold.debug.windowstolinux.shared.deploy.support.DistributionSupportPolicy;
import gold.debug.windowstolinux.shared.model.capability.LinuxCapabilities;

import java.util.List;

/** Routes a classified distribution to exactly one independent policy. / 将已分类发行版路由到恰好一个独立策略。 */
final class DistributionSupportRegistry {
    private static final DistributionSupportPolicy UBUNTU = new UbuntuSupportPolicy();
    private static final DistributionSupportPolicy DEBIAN = new DebianSupportPolicy();
    private static final DistributionSupportPolicy CENTOS = new CentosStreamSupportPolicy();
    private static final DistributionSupportPolicy ROCKY = new RockyLinuxSupportPolicy();
    private static final DistributionSupportPolicy ALMA = new AlmaLinuxSupportPolicy();
    private static final DistributionSupportPolicy ORACLE = new OracleLinuxSupportPolicy();

    private DistributionSupportRegistry() {
    }

    /** Evaluates the supplied compatibility evidence. / 评估提供的兼容性证据。 */
    public static HostSupport evaluate(LinuxCapabilities capabilities, List<String> evidence) {
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
            case OTHER -> DistributionSupportRules.unsupported(
                    "distribution is outside the typed deployment matrix", evidence);
        };
    }
}
