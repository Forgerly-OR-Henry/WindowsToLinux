package gold.debug.windowstolinux.shared.linux.sshd.distro.contract.profile;

/**
 * Fixed runtime checks selected only by implementation-owned distribution adapters. / 仅由实现持有的发行版适配器选择的固定运行时检查。
 */
public enum EcosystemCapabilityProfile {
    /**
     * UBUNTU 2204 classification within ecosystem capability profile.
     * <p>生态能力配置资料中的UBUNTU2204分类。
     */
    UBUNTU_2204,
    /**
     * Ubuntu 24.04 validation target classification within ecosystem capability profile.
     * <p>生态能力配置资料中的Ubuntu 24.04 验证目标分类。
     */
    UBUNTU_2404,
    /**
     * DEBIAN 13 classification within ecosystem capability profile.
     * <p>生态能力配置资料中的DEBIAN13分类。
     */
    DEBIAN_13,
    /**
     * ENTERPRISE 9 classification within ecosystem capability profile.
     * <p>生态能力配置资料中的企业版9分类。
     */
    ENTERPRISE_9,
    /**
     * ENTERPRISE 10 classification within ecosystem capability profile.
     * <p>生态能力配置资料中的企业版10分类。
     */
    ENTERPRISE_10;

    /**
     * Returns python command.
     * <p>返回python命令。
     *
     * @return python command / python命令
     */
    public String pythonCommand() {
        return switch (this) {
            case UBUNTU_2204 -> "python3.10";
            case UBUNTU_2404 -> "python3.12";
            case DEBIAN_13 -> "python3.13";
            case ENTERPRISE_9 -> "python3.11";
            case ENTERPRISE_10 -> "python3.12";
        };
    }

}
