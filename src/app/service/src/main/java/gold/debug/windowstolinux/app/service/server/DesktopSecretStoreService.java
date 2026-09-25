package gold.debug.windowstolinux.app.service.server;

import java.util.Arrays;
import java.util.Objects;

import gold.debug.windowstolinux.app.secret.*;
import gold.debug.windowstolinux.app.service.security.DesktopSecurityService;
import gold.debug.windowstolinux.shared.linux.connection.SshCredential;

/** Supplies credentials only to accepted desktop operations. / 仅为已接纳的桌面操作提供凭据。 */
public final class DesktopSecretStoreService {
    /** Service-owned interactive authorization boundary. / 服务持有的交互授权边界。
     */
    private final DesktopSecurityService security;
    /** Binds the validated inputs and owned state for this scope. / 绑定当前作用域的已验证输入及持有状态。
     * @param security security supplied to this boundary / 传入当前边界的security值
     */
    public DesktopSecretStoreService(DesktopSecurityService security) {
        this.security = Objects.requireNonNull(security);
    }

    /** Opens a scoped credential capability for the accepted operation. / 为已接纳操作打开限定凭据能力。
     * @return the validated result or retained scoped handle / 已验证结果或保留的限定句柄
     * @throws SecretStoreException if validation or the scoped operation fails / 校验或限定操作失败时
     */
    public SecretStore open() throws SecretStoreException {
        return security.openCredentials();
    }

    /** Forks the accepted operation capability for an asynchronous continuation. / 为异步延续复制已接纳操作的凭据能力。
     * @return the validated result or retained scoped handle / 已验证结果或保留的限定句柄
     */
    public DesktopSecurityService.Task captureTask() {
        return security.captureTask();
    }

    /** Serializes a short manual control action against locking and security changes. / 使短暂的人工控制动作与锁定、安全变更互斥。
     * @param operation operation supplied to this boundary / 传入当前边界的operation值
     * @param <T> t supplied to this boundary / 传入当前边界的T值
     * @return the validated result or retained scoped handle / 已验证结果或保留的限定句柄
     */
    public <T> T whileUnlocked(java.util.function.Supplier<T> operation) {
        return security.whileUnlocked(operation);
    }

    /** Registers an owned native window for lock transitions. / 为锁定转换登记自有原生窗口。
     * @param participant native privacy action / 原生隐私动作
     * @return registration closed after browser cleanup / 浏览器清理后关闭的登记
     */
    public AutoCloseable observeLock(java.util.function.Consumer<Boolean> participant) {
        return security.observeLock(participant);
    }

    /** Rejects interactive access during lock, shutdown or a security change. / 在锁定、关闭或安全变更时拒绝交互访问。
     */
    public void requireUnlocked() {
        security.requireUnlocked();
    }

    /**
     * Returns the value produced by {@code loadPassword}.
     *
     *  <p>返回 {@code loadPassword} 生成的值。
     *
     * @param profile connection or provider settings supplied to the operation / 提供给操作的连接或提供者设置
     * @param store store / 存储
     * @return the operation result / 操作结果
     * @throws SecretStoreException if the protected credential cannot be accessed or updated / 无法访问或更新受保护凭据时
     */
    public SshCredential.Password loadPassword(ServerProfile profile, SecretStore store) throws SecretStoreException {
        char[] password = store.read(profile.credentialKey())
                .orElseThrow(() -> SecretStoreException.create(SecretStoreFailureType.SSH_CREDENTIAL_MISSING,
                        "The target server SSH credential was not found"));
        try {
            return new SshCredential.Password(password);
        } finally {
            Arrays.fill(password, '\0');
        }
    }
}
