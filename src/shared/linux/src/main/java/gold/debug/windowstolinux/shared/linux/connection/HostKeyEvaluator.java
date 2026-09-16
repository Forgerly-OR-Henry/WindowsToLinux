package gold.debug.windowstolinux.shared.linux.connection;

/**
 * Called before authentication for first-use confirmation and key-change blocking.
 *
 * <p>在认证前调用，用于首次使用确认和阻止密钥变更。
 */
@FunctionalInterface
public interface HostKeyEvaluator {
    /**
     * Validates the input through {@code verify}.
     *
     * <p>通过 {@code verify} 验证输入。
     *
     * @param endpoint the {@code endpoint} value / {@code endpoint} 值
     * @param observedSha256Fingerprint the {@code observedSha256Fingerprint} value / {@code observedSha256Fingerprint} 值
     * @return the operation result / 操作结果
     */
    HostKeyDecision verify(SshEndpoint endpoint, String observedSha256Fingerprint);
    /** Verifies one key with both representations for controlled historical migration. / 使用两种表示验证同一公钥，以受控迁移历史记录。 */
    default HostKeyDecision verify(SshEndpoint endpoint, HostKeyObservation observation) {
        return verify(endpoint, observation.sshSha256());
    }

    /** Commits trust only after the accepted key has completed authentication. / 仅在已接受公钥完成认证后提交信任记录。 */
    default boolean authenticated(SshEndpoint endpoint, HostKeyObservation observation) {
        return true;
    }
}
