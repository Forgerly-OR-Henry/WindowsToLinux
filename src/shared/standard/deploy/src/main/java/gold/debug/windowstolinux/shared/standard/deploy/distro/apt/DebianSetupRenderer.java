package gold.debug.windowstolinux.shared.standard.deploy.distro.apt;

import gold.debug.windowstolinux.shared.model.capability.LinuxCapabilityFacts;
import gold.debug.windowstolinux.shared.model.server.CpuMicroarchitectureLevel;
import gold.debug.windowstolinux.shared.model.server.LinuxDistroType;
import gold.debug.windowstolinux.shared.standard.deploy.distro.DistributionSetupRenderer;
import gold.debug.windowstolinux.shared.standard.deploy.distro.contract.profile.DistributionSetupProfile;
import gold.debug.windowstolinux.shared.standard.deploy.distro.contract.profile.EcosystemCapabilityProfile;

/**
 * Owns Debian preparation differences. / 持有 Debian 专属的环境准备差异。
 */
public final class DebianSetupRenderer implements DistributionSetupRenderer {
    /**
     * Returns the prepared distribution. / 返回所准备的发行版。
     *
     * @return the prepared distribution / 所准备的发行版
     */
    @Override
    public LinuxDistroType distro() {
        return LinuxDistroType.DEBIAN;
    }

    /**
     * Renders the fixed distribution preparation. / 渲染该发行版的固定环境准备脚本。
     *
     * @param facts typed facts used for deterministic planning / 确定性计划使用的类型化事实
     * @param username account name used by the reviewed connection / 已审阅连接使用的账户名
     * @return render text / 渲染文本
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     */
    @Override
    public String render(LinuxCapabilityFacts facts, String username) {
        if (!"13".equals(facts.version())) {
            throw new IllegalArgumentException("Debian preparation supports only stable 13");
        }
        return AptSetupRenderer.render(
                new DistributionSetupProfile("debian", "", facts.version(), "amd64",
                        CpuMicroarchitectureLevel.X86_64_V1, AptPackageSets.BASE, EcosystemCapabilityProfile.DEBIAN_13),
                username, "");
    }
}
