package gold.debug.windowstolinux.shared.linux.connection;

/**
 * Called before authentication for first-use confirmation and key-change blocking.
 *
 *  <p>在认证前调用，用于首次使用确认和阻止密钥变更。
 */
@FunctionalInterface
public interface HostKeyEvaluator {
    /**
     * Validates the input through {@code verify}.
     *
     *  <p>通过 {@code verify} 验证输入。
     *
     * @param endpoint reviewed network endpoint / 已审阅网络端点
     * @param observedSha256Fingerprint observed sha 256 fingerprint / 已观测SHA256指纹
     * @return the operation result / 操作结果
     */
    HostKeyDecision verify(SshEndpoint endpoint, String observedSha256Fingerprint);

    /**
     * Verifies one key with both representations for controlled historical migration. / 使用两种表示验证同一公钥，以受控迁移历史记录。
     *
     * @param endpoint reviewed network endpoint / 已审阅网络端点
     * @param observation observation / 观测
     * @return constructed or resolved host key decision / 构造或解析得到的主机键决定
     */
    default HostKeyDecision verify(SshEndpoint endpoint, HostKeyObservation observation) {
        return verify(endpoint, observation.sshSha256());
    }

    /**
     * Commits trust only after the accepted key has completed authentication. / 仅在已接受公钥完成认证后提交信任记录。
     *
     * @param endpoint reviewed network endpoint / 已审阅网络端点
     * @param observation observation / 观测
     * @return true when commits trust only after the accepted key has completed authentication, false otherwise / 仅在已接受公钥完成认证后提交信任记录时为 true，否则为 false
     */
    default boolean authenticated(SshEndpoint endpoint, HostKeyObservation observation) {
        return true;
    }
}
