package gold.debug.windowstolinux.shared.linux.sshd.distro.apt;

import gold.debug.windowstolinux.shared.linux.sshd.distro.DistributionSetupRenderer;
import gold.debug.windowstolinux.shared.linux.sshd.distro.contract.profile.DistributionSetupProfile;
import gold.debug.windowstolinux.shared.linux.sshd.distro.contract.profile.EcosystemCapabilityProfile;
import gold.debug.windowstolinux.shared.model.capability.LinuxCapabilityFacts;
import gold.debug.windowstolinux.shared.model.server.CpuMicroarchitectureLevel;
import gold.debug.windowstolinux.shared.model.server.LinuxDistroType;

import java.util.ArrayList;
import java.util.List;

/**
 * Owns Ubuntu preparation differences. / 持有 Ubuntu 专属的环境准备差异。
 */
public final class UbuntuSetupRenderer implements DistributionSetupRenderer {
    /**
     * ADDITIONAL PACKAGES 2404.
     * <p>额外软件包集合2404。
     */
    private static final List<String> ADDITIONAL_PACKAGES_2404 = List.of(
            "golang-go", "rustc", "cargo", "dotnet-sdk-8.0", "php-cli", "composer", "ruby", "ruby-bundler");

    /**
     * Returns the prepared distribution. / 返回所准备的发行版。
     *
     * @return the prepared distribution / 所准备的发行版
     */
    @Override
    public LinuxDistroType distro() {
        return LinuxDistroType.UBUNTU;
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
        String version = facts.version();
        if (!("22.04".equals(version) || "24.04".equals(version))) {
            throw new IllegalArgumentException("Ubuntu preparation supports only 22.04 or 24.04");
        }
        boolean ubuntu2404 = "24.04".equals(version);
        List<String> packages = new ArrayList<>(AptPackageSets.BASE);
        if (ubuntu2404) packages.addAll(ADDITIONAL_PACKAGES_2404);
        EcosystemCapabilityProfile checks = ubuntu2404
                ? EcosystemCapabilityProfile.UBUNTU_2404 : EcosystemCapabilityProfile.UBUNTU_2204;
        return AptSetupRenderer.render(new DistributionSetupProfile(
                "ubuntu", "", version, "amd64", CpuMicroarchitectureLevel.X86_64_V1,
                packages, checks), username, "");
    }
}
