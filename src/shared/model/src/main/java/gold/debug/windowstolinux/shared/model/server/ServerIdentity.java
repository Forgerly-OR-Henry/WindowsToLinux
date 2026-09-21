package gold.debug.windowstolinux.shared.model.server;

import java.util.Locale;
import java.util.Objects;

/**
 * Non-secret identity of a target server and its trusted SSH host key.
 *
 *  <p>目标服务器及其可信 SSH 主机密钥的非秘密身份。
 *
 * @param id stable identifier within the owning registry / 所属登记表内的稳定标识
 * @param host reviewed server hostname or IP address / 已审阅服务器主机名或 IP 地址
 * @param sshPort ssh port / SSH端口
 * @param hostKeySha256 host key sha 256 / 主机键SHA256
 */
public record ServerIdentity(String id, String host, int sshPort, String hostKeySha256) {
    /**
     * Validates and binds the inputs required by server identity.
     * <p>校验并绑定服务器身份所需输入。
     *
     * @param id stable identifier within the owning registry / 所属登记表内的稳定标识
     * @param host reviewed server hostname or IP address / 已审阅服务器主机名或 IP 地址
     * @param sshPort ssh port / SSH端口
     * @param hostKeySha256 host key sha 256 / 主机键SHA256
     * @throws IllegalArgumentException if an argument violates the required constraints / 参数违反必要约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public ServerIdentity {
        id = requireIdentifier(id, "id");
        host = Objects.requireNonNull(host, "host").trim();
        if (host.isBlank() || host.contains(" ")) {
            throw new IllegalArgumentException("host must be a non-blank host name or address");
        }
        if (sshPort < 1 || sshPort > 65535) {
            throw new IllegalArgumentException("sshPort must be between 1 and 65535");
        }
        hostKeySha256 = Objects.requireNonNull(hostKeySha256, "hostKeySha256").trim();
        if (!hostKeySha256.startsWith("SHA256:") || hostKeySha256.length() < 12) {
            throw new IllegalArgumentException("hostKeySha256 must use SSH SHA256 fingerprint form");
        }
    }

    /**
     * Validates the input through {@code requireIdentifier}.
     *
     *  <p>通过 {@code requireIdentifier} 验证输入。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     * @param name human-readable name or diagnostic field label / 可读名称或诊断字段标签
     * @return the operation result / 操作结果
     * @throws IllegalArgumentException if an argument violates the required constraints / 参数违反必要约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public static String requireIdentifier(String value, String name) {
        value = Objects.requireNonNull(value, name).toLowerCase(Locale.ROOT);
        if (!value.matches("[a-z0-9][a-z0-9-]{0,62}")) {
            throw new IllegalArgumentException(name + " must use lowercase letters, digits and hyphens");
        }
        return value;
    }
}
