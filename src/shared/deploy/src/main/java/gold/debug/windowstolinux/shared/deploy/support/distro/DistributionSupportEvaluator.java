package gold.debug.windowstolinux.shared.deploy.support.distro;

import gold.debug.windowstolinux.shared.deploy.contract.result.compatibility.HostSupportStatus;
import gold.debug.windowstolinux.shared.model.capability.LinuxCapabilityFacts;

import java.util.List;

/**
 * Routes a classified distribution to exactly one independent policy. / 将已分类发行版路由到恰好一个独立策略。
 */
public final class DistributionSupportEvaluator {
    /**
     * Bound distribution support policy collaborator for UBUNTU.
     * <p>处理UBUNTU 对应的输入或状态的发行版支持策略协作对象。
     */
    private static final DistributionSupportPolicy UBUNTU = new UbuntuSupportPolicy();
    /**
     * Bound distribution support policy collaborator for DEBIAN.
     * <p>处理DEBIAN 对应的输入或状态的发行版支持策略协作对象。
     */
    private static final DistributionSupportPolicy DEBIAN = new DebianSupportPolicy();
    /**
     * Bound distribution support policy collaborator for CENTOS.
     * <p>处理CENTOS 对应的输入或状态的发行版支持策略协作对象。
     */
    private static final DistributionSupportPolicy CENTOS = new CentosStreamSupportPolicy();
    /**
     * Bound distribution support policy collaborator for ROCKY.
     * <p>处理ROCKY 对应的输入或状态的发行版支持策略协作对象。
     */
    private static final DistributionSupportPolicy ROCKY = new RockyLinuxSupportPolicy();
    /**
     * Bound distribution support policy collaborator for ALMA.
     * <p>处理ALMA 对应的输入或状态的发行版支持策略协作对象。
     */
    private static final DistributionSupportPolicy ALMA = new AlmaLinuxSupportPolicy();
    /**
     * Bound distribution support policy collaborator for ORACLE.
     * <p>处理ORACLE 对应的输入或状态的发行版支持策略协作对象。
     */
    private static final DistributionSupportPolicy ORACLE = new OracleLinuxSupportPolicy();

    /**
     * Prevents instantiation of this static contract helper.
     * <p>防止实例化当前静态契约辅助类。
     */
    private DistributionSupportEvaluator() {
    }

    /**
     * Evaluates the supplied compatibility evidence. / 评估提供的兼容性证据。
     *
     * @param capabilities observed target tools and runtime capabilities / 目标工具及运行能力观测
     * @param evidence observations supporting the reported result / 支持所报告结果的观测证据
     * @return constructed or resolved host support status / 构造或解析得到的主机支持状态
     */
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
