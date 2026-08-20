package gold.debug.windowstolinux.shared.linux.sshd.distro.contract.profile;

/** Fixed runtime checks selected only by implementation-owned distribution adapters. / 仅由实现持有的发行版适配器选择的固定运行时检查。 */
public enum EcosystemCapabilityProfile {
    /** Represents the {@code UBUNTU_2204} value. / 表示 {@code UBUNTU_2204} 值。 */
    UBUNTU_2204,
    /** Represents the {@code UBUNTU_2404} value. / 表示 {@code UBUNTU_2404} 值。 */
    UBUNTU_2404,
    /** Represents the {@code DEBIAN_13} value. / 表示 {@code DEBIAN_13} 值。 */
    DEBIAN_13,
    /** Represents the {@code ENTERPRISE_9} value. / 表示 {@code ENTERPRISE_9} 值。 */
    ENTERPRISE_9,
    /** Represents the {@code ENTERPRISE_10} value. / 表示 {@code ENTERPRISE_10} 值。 */
    ENTERPRISE_10;

    /** Performs the {@code pythonCommand} operation. / 执行 {@code pythonCommand} 操作。 */
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
