package gold.debug.windowstolinux.shared.model.server;

import java.util.Locale;
import java.util.Objects;

/**
 * Non-secret identity of a target server and its trusted SSH host key.
 *
 * <p>目标服务器及其可信 SSH 主机密钥的非秘密身份。
 *
 * @param id the {@code id} value / {@code id} 值
 * @param host the {@code host} value / {@code host} 值
 * @param sshPort the {@code sshPort} value / {@code sshPort} 值
 * @param hostKeySha256 the {@code hostKeySha256} value / {@code hostKeySha256} 值
 */
public record ServerIdentity(String id, String host, int sshPort, String hostKeySha256) {
    /**
     * Creates a {@code ServerIdentity} instance.
     *
     * <p>创建 {@code ServerIdentity} 实例。
     *
     * @param id the {@code id} value / {@code id} 值
     * @param host the {@code host} value / {@code host} 值
     * @param sshPort the {@code sshPort} value / {@code sshPort} 值
     * @param hostKeySha256 the {@code hostKeySha256} value / {@code hostKeySha256} 值
     * @throws IllegalArgumentException if an argument violates the required constraints / 参数违反必要约束时
     * @throws NullPointerException if a required argument is {@code null} / 必要参数为 {@code null} 时
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
     * <p>通过 {@code requireIdentifier} 验证输入。
     *
     * @param value the {@code value} value / {@code value} 值
     * @param name the {@code name} value / {@code name} 值
     * @return the operation result / 操作结果
     * @throws IllegalArgumentException if an argument violates the required constraints / 参数违反必要约束时
     * @throws NullPointerException if a required argument is {@code null} / 必要参数为 {@code null} 时
     */
    public static String requireIdentifier(String value, String name) {
        value = Objects.requireNonNull(value, name).toLowerCase(Locale.ROOT);
        if (!value.matches("[a-z0-9][a-z0-9-]{0,62}")) {
            throw new IllegalArgumentException(name + " must use lowercase letters, digits and hyphens");
        }
        return value;
    }
}
