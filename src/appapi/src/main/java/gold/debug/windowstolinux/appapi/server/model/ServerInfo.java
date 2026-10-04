package gold.debug.windowstolinux.appapi.server.model;

import java.time.Instant;
import java.util.Optional;

/**
 * Non-secret server configuration and observed trust metadata. / 不含秘密的服务器配置与已观察信任信息。
 * @param ref stable platform server identifier / 稳定的平台服务器标识
 * @param name human-readable server name / 可读的服务器名称
 * @param host configured SSH host name or address / 已配置的 SSH 主机名或地址
 * @param port SSH port from one to 65535 / 一到 65535 之间的 SSH 端口
 * @param username configured SSH account name / 已配置的 SSH 账户名
 * @param trustedFingerprint optional previously trusted host-key fingerprint / 可选的先前受信主机密钥指纹
 * @param observedAt time at which the reported facts were observed / 报告事实的观察时间
 */
public record ServerInfo(ServerId ref, String name, String host, int port, String username,
                         Optional<String> trustedFingerprint, Optional<Instant> observedAt) {
    /**
     * Validates the immutable boundary value and copies mutable input. / 校验不可变边界值并复制可变输入。
     * @param ref stable platform server identifier / 稳定的平台服务器标识
     * @param name human-readable server name / 可读的服务器名称
     * @param host configured SSH host name or address / 已配置的 SSH 主机名或地址
     * @param port SSH port from one to 65535 / 一到 65535 之间的 SSH 端口
     * @param username configured SSH account name / 已配置的 SSH 账户名
     * @param trustedFingerprint optional previously trusted host-key fingerprint / 可选的先前受信主机密钥指纹
     * @param observedAt time at which the reported facts were observed / 报告事实的观察时间
     */
    public ServerInfo {
        java.util.Objects.requireNonNull(ref, "ref");
        java.util.Objects.requireNonNull(name, "name");
        java.util.Objects.requireNonNull(host, "host");
        if (port < 1)
            throw new IllegalArgumentException("port is below its minimum");
        if (port > 65535)
            throw new IllegalArgumentException("port is above 65535");
        java.util.Objects.requireNonNull(username, "username");
        java.util.Objects.requireNonNull(trustedFingerprint, "trustedFingerprint");
        java.util.Objects.requireNonNull(observedAt, "observedAt");
    }
}
