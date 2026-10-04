package gold.debug.windowstolinux.appapi.linux.session;

/**
 * Observed remote platform and authenticated host fingerprint. / 已观察的远端平台与认证主机指纹。
 * @param operatingSystem observed remote operating system / 已观察的远端操作系统
 * @param architecture observed processor architecture / 已观察的处理器架构
 * @param fingerprint authenticated SSH host-key fingerprint / 已认证的 SSH 主机密钥指纹
 */
public record RemoteFacts(String operatingSystem, String architecture, String fingerprint) {
    /**
     * Validates the immutable boundary value and copies mutable input. / 校验不可变边界值并复制可变输入。
     * @param operatingSystem observed remote operating system / 已观察的远端操作系统
     * @param architecture observed processor architecture / 已观察的处理器架构
     * @param fingerprint authenticated SSH host-key fingerprint / 已认证的 SSH 主机密钥指纹
     */
    public RemoteFacts {
        java.util.Objects.requireNonNull(operatingSystem, "operatingSystem");
        java.util.Objects.requireNonNull(architecture, "architecture");
        java.util.Objects.requireNonNull(fingerprint, "fingerprint");
    }
}
