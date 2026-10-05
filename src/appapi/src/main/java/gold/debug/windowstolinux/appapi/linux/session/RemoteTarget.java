package gold.debug.windowstolinux.appapi.linux.session;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import gold.debug.windowstolinux.appapi.server.model.ServerId;

/** Non-secret fixed connection expectation; copying it grants no task authority. / 不含秘密的固定连接预期，复制它不授予任务权限。
 * @param server original admitted saved server / 原准入已保存服务器
 * @param taskId original accepted task identity / 原已接纳任务身份
 * @param host exact original configured host / 精确原配置主机
 * @param port exact original SSH port / 精确原 SSH 端口
 * @param username exact original account / 精确原账户
 * @param fingerprint original trusted wire key, absent only for first trust / 原受信 wire 密钥，仅首次信任可为空 */
public record RemoteTarget(ServerId server, UUID taskId, String host, int port, String username,
                           Optional<String> fingerprint) {
    /** Validates the fixed expectation without adding credentials or endpoints. / 校验固定预期，不增加凭据或端点权限。
     * @param server original admitted saved server / 原准入已保存服务器
     * @param taskId original accepted task identity / 原已接纳任务身份
     * @param host exact original configured host / 精确原配置主机
     * @param port exact original SSH port / 精确原 SSH 端口
     * @param username exact original account / 精确原账户
     * @param fingerprint original trusted wire key / 原受信 wire 密钥 */
    public RemoteTarget {
        Objects.requireNonNull(server);
        Objects.requireNonNull(taskId);
        Objects.requireNonNull(host);
        Objects.requireNonNull(username);
        Objects.requireNonNull(fingerprint);
        if (host.isBlank() || username.isBlank() || host.length() > 512 || username.length() > 128
            || port < 1 || port > 65535 || host.chars().anyMatch(c -> c < 32)
            || username.chars().anyMatch(c -> c < 32)
            || fingerprint.filter(value -> !value.matches("SHA256:[A-Za-z0-9+/]{43}=?")).isPresent())
            throw new IllegalArgumentException("Invalid original SSH target expectation");
    }
}
