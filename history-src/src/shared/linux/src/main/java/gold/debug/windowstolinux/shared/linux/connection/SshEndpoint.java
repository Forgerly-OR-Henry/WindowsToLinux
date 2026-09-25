package gold.debug.windowstolinux.shared.linux.connection;

import java.util.Locale;
import java.util.Objects;

/**
 * Target address before a host key has been accepted and persisted.
 *
 *  <p>接受并持久化主机密钥之前的目标地址。
 *
 * @param serverId persisted server identifier / 持久化服务器标识
 * @param host reviewed server hostname or IP address / 已审阅服务器主机名或 IP 地址
 * @param port network port number in the reviewed endpoint / 已审阅端点中的网络端口号
 * @param username account name used by the reviewed connection / 已审阅连接使用的账户名
 */
public record SshEndpoint(String serverId, String host, int port, String username) {
    /**
     * Validates and binds the inputs required by ssh endpoint.
     * <p>校验并绑定SSH端点所需输入。
     *
     * @param serverId persisted server identifier / 持久化服务器标识
     * @param host reviewed server hostname or IP address / 已审阅服务器主机名或 IP 地址
     * @param port network port number in the reviewed endpoint / 已审阅端点中的网络端口号
     * @param username account name used by the reviewed connection / 已审阅连接使用的账户名
     * @throws IllegalArgumentException if an argument violates the required constraints / 参数违反必要约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
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

    /**
     * Validates and returns the stable secret identifier and rejects inputs outside the declared constraints.
     * <p>校验并返回稳定的秘密标识并拒绝超出已声明约束的输入。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     * @param name human-readable name or diagnostic field label / 可读名称或诊断字段标签
     * @return require identifier text / 要求标识文本
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    private static String requireIdentifier(String value, String name) {
        value = Objects.requireNonNull(value, name).toLowerCase(Locale.ROOT);
        if (!value.matches("[a-z0-9][a-z0-9-]{0,62}")) {
            throw new IllegalArgumentException(name + " must use lowercase letters, digits and hyphens");
        }
        return value;
    }
}
