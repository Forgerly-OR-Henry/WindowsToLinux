package gold.debug.windowstolinux.app.service.recovery;

import java.util.function.Predicate;

import gold.debug.windowstolinux.app.service.server.*;
import gold.debug.windowstolinux.shared.linux.connection.DeploymentLinuxGateway;
import gold.debug.windowstolinux.shared.linux.error.*;

/**
 * Verifies pinned identity, authentication and actual read-only execution. / 验证固定身份、认证和实际只读执行。
 */
final class RecoverySshProbe {
    /**
     * Bound server use case facade collaborator for server-profile and authenticated-session service.
     * <p>处理服务器资料及已认证会话服务的服务器用例门面协作对象。
     */
    private final ServerUseCaseFacade servers;

    /**
     * Bound desktop secret store service collaborator for credential references or scoped secret-access service.
     * <p>处理凭据引用或限定作用域的秘密访问服务的Desktop秘密存储服务协作对象。
     */
    private final DesktopSecretStoreService secrets;

    /**
     * Factory for authenticated Linux sessions.
     * <p>已认证 Linux 会话的工厂。
     */
    private final DeploymentLinuxGateway gateway;
    /**
     * Binds the supplied dependencies and state for recovery ssh probe.
     * <p>为恢复SSH探测绑定传入的依赖及状态。
     *
     * @param servers server-profile and authenticated-session service / 服务器资料及已认证会话服务
     * @param secrets credential references or scoped secret-access service / 凭据引用或限定作用域的秘密访问服务
     * @param gateway factory for authenticated Linux sessions / 已认证 Linux 会话的工厂
     */
    RecoverySshProbe(ServerUseCaseFacade servers, DesktopSecretStoreService secrets, DeploymentLinuxGateway gateway) {
        this.servers = servers;
        this.secrets = secrets;
        this.gateway = gateway;
    }

    /**
     * Opens protected credentials, verifies the pinned SSH identity and performs the read-only connection check. Clears the password buffer after use and returns the original classified failure instead of flattening it to a connection string.
     * <p>打开受保护凭据，验证固定 SSH 身份并执行只读连接检查。使用后清空密码缓冲区，并返回原始分类失败，不将其压缩为连接字符串。
     *
     * @param profile connection or provider settings supplied to the operation / 提供给操作的连接或提供者设置
     * @param master master-password buffer used for the scoped secret operation / 限定秘密操作使用的主密码缓冲区
     * @param fingerprint pinned or freshly observed host-key fingerprint / 固定或新近观测的主机密钥指纹
     * @return verified recovery or the typed failure retaining its original descriptor / 已验证恢复结果，或保留原始描述的类型化失败
     */
    RecoveryProbeResult check(ServerProfile profile, char[] master, Predicate<String> fingerprint) {
        try (var store = secrets.open(profile.credentialMode(), master)) {
            var credential = servers.loadPassword(profile, store);
            try (var session = gateway.connect(profile.endpoint(), credential,
                    servers.hostKeyVerifier(profile, fingerprint))) {
                session.verifyConnection();
                return RecoveryProbeResult.recovered();
            } finally {
                credential.clear();
            }
        } catch (Exception failure) {
            return RecoveryProbeResult.failed(failure);
        }
    }
}
