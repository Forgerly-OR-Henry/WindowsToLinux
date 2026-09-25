package gold.debug.windowstolinux.shared.standard.deploy.distro.dnf;

import gold.debug.windowstolinux.shared.linux.error.LinuxOperationException;
import gold.debug.windowstolinux.shared.model.capability.LinuxCapabilityFacts;
import gold.debug.windowstolinux.shared.model.server.CpuMicroarchitectureLevel;
import gold.debug.windowstolinux.shared.model.server.LinuxDistroType;
import gold.debug.windowstolinux.shared.standard.deploy.distro.DistributionSetupRenderer;
import gold.debug.windowstolinux.shared.standard.deploy.distro.contract.profile.DistributionSetupProfile;
import gold.debug.windowstolinux.shared.standard.deploy.distro.contract.profile.EcosystemCapabilityProfile;

/**
 * Owns Rocky Linux preparation differences. / 持有 Rocky Linux 专属的环境准备差异。
 */
public final class RockyLinuxSetupRenderer implements DistributionSetupRenderer {
    /**
     * Returns the prepared distribution. / 返回所准备的发行版。
     *
     * @return the prepared distribution / 所准备的发行版
     */
    @Override
    public LinuxDistroType distro() {
        return LinuxDistroType.ROCKY_LINUX;
    }

    /**
     * Renders the fixed distribution preparation. / 渲染该发行版的固定环境准备脚本。
     *
     * @param facts typed facts used for deterministic planning / 确定性计划使用的类型化事实
     * @param username account name used by the reviewed connection / 已审阅连接使用的账户名
     * @return render text / 渲染文本
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     */
    @Override
    public String render(LinuxCapabilityFacts facts, String username) throws LinuxOperationException {
        DnfSetupRenderer.requireEnterpriseSecurity(facts);
        String version = facts.version();
        if (!("9.8".equals(version) || "10.2".equals(version))) {
            throw new IllegalArgumentException("Rocky Linux preparation supports only maintained 9.8 or 10.2");
        }
        boolean ten = version.startsWith("10.");
        return DnfSetupRenderer.render(
                new DistributionSetupProfile("rocky", "", version, "x86_64",
                        ten ? CpuMicroarchitectureLevel.X86_64_V3 : CpuMicroarchitectureLevel.X86_64_V1,
                        DnfPackageSets.enterprise(ten ? "10" : "9"),
                        ten ? EcosystemCapabilityProfile.ENTERPRISE_10 : EcosystemCapabilityProfile.ENTERPRISE_9),
                username);
    }
}
