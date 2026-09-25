package gold.debug.windowstolinux.app.linux.ssh;

import java.util.Objects;

/** Exact platform server identity and authentication endpoint. / 精确的平台服务器身份及认证端点。
 * @param serverId stable saved server identity / 稳定的已保存服务器身份
 * @param host explicitly selected host / 明确选择的主机
 * @param port SSH port / SSH 端口
 * @param username explicitly selected login user / 明确选择的登录用户 */
public record SshEndpoint(String serverId, String host, int port, String username) {
    /**
     * Validates an endpoint without discovering ambient SSH configuration. / 校验端点，不发现环境 SSH 配置。
     * @param serverId stable server identity / 稳定服务器身份
     * @param host selected host / 所选主机
     * @param port selected SSH port / 所选 SSH 端口
     * @param username selected login user / 所选登录用户
     */
    public SshEndpoint {
        text(serverId, 512);
        text(host, 512);
        text(username, 256);
        if (port < 1 || port > 65535)
            throw new IllegalArgumentException("Invalid SSH port");
    }

    /**
     * Rejects empty, oversized or control-bearing endpoint fields. / 拒绝空白、过大或含控制字符的端点字段。
     * @param value candidate field / 候选字段
     * @param maximum maximum character count / 最大字符数
     */
    private static void text(String value, int maximum) {
        Objects.requireNonNull(value);
        if (value.isBlank() || value.length() > maximum || !value.equals(value.strip())
                || value.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Invalid SSH endpoint field");
    }
}
