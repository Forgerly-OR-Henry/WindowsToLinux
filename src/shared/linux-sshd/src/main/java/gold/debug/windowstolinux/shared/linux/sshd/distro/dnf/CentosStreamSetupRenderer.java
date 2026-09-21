package gold.debug.windowstolinux.shared.linux.sshd.distro.dnf;

import gold.debug.windowstolinux.shared.linux.sshd.distro.DistributionSetupRenderer;
import gold.debug.windowstolinux.shared.linux.sshd.distro.contract.profile.DistributionSetupProfile;
import gold.debug.windowstolinux.shared.linux.sshd.distro.contract.profile.EcosystemCapabilityProfile;
import gold.debug.windowstolinux.shared.model.capability.LinuxCapabilityFacts;
import gold.debug.windowstolinux.shared.model.server.CpuMicroarchitectureLevel;
import gold.debug.windowstolinux.shared.model.server.LinuxDistroType;
import gold.debug.windowstolinux.shared.linux.error.LinuxOperationException;

/**
 * Owns CentOS Stream preparation differences. / 持有 CentOS Stream 专属的环境准备差异。
 */
public final class CentosStreamSetupRenderer implements DistributionSetupRenderer {
    /**
     * Returns the prepared distribution. / 返回所准备的发行版。
     *
     * @return the prepared distribution / 所准备的发行版
     */
    @Override
    public LinuxDistroType distro() {
        return LinuxDistroType.CENTOS_STREAM;
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
        if (!("9".equals(version) || "10".equals(version))) {
            throw new IllegalArgumentException("CentOS Stream preparation supports only 9 or 10");
        }
        boolean ten = "10".equals(version);
        return DnfSetupRenderer.renderWithCrb(new DistributionSetupProfile(
                "centos", "", version, "x86_64",
                ten ? CpuMicroarchitectureLevel.X86_64_V3 : CpuMicroarchitectureLevel.X86_64_V2,
                DnfPackageSets.enterprise(version),
                ten ? EcosystemCapabilityProfile.ENTERPRISE_10 : EcosystemCapabilityProfile.ENTERPRISE_9), username);
    }
}
