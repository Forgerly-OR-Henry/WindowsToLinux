package gold.debug.windowstolinux.shared.linux.connection;

import java.util.Locale;
import java.util.Objects;

/**
 * Target address before a host key has been accepted and persisted.
 *
 * <p>接受并持久化主机密钥之前的目标地址。
 *
 * @param serverId the {@code serverId} value / {@code serverId} 值
 * @param host the {@code host} value / {@code host} 值
 * @param port the {@code port} value / {@code port} 值
 * @param username the {@code username} value / {@code username} 值
 */
public record SshEndpoint(String serverId, String host, int port, String username) {
    /**
     * Creates a {@code SshEndpoint} instance.
     *
     * <p>创建 {@code SshEndpoint} 实例。
     *
     * @param serverId the {@code serverId} value / {@code serverId} 值
     * @param host the {@code host} value / {@code host} 值
     * @param port the {@code port} value / {@code port} 值
     * @param username the {@code username} value / {@code username} 值
     * @throws IllegalArgumentException if an argument violates the required constraints / 参数违反必要约束时
     * @throws NullPointerException if a required argument is {@code null} / 必要参数为 {@code null} 时
     */
    public SshEndpoint {
        serverId = requireIdentifier(serverId, "serverId");
        host = Objects.requireNonNull(host, "host").trim();
        username = Objects.requireNonNull(username, "username").trim();
        if (host.isBlank() || host.contains(" ") || username.isBlank() || username.contains("\n")) {
            throw new IllegalArgumentException("host and username must be non-blank plain values");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("port must be between 1 and 65535");
        }
    }

    private static String requireIdentifier(String value, String name) {
        value = Objects.requireNonNull(value, name).toLowerCase(Locale.ROOT);
        if (!value.matches("[a-z0-9][a-z0-9-]{0,62}")) {
            throw new IllegalArgumentException(name + " must use lowercase letters, digits and hyphens");
        }
        return value;
    }
}
