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
}
