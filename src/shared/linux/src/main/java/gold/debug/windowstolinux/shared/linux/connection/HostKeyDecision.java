package gold.debug.windowstolinux.shared.linux.connection;

/**
 * The application, not the SSH adapter, decides whether a host key is trusted.
 *
 * <p>由应用而非 SSH 适配器决定是否信任主机密钥。
 */
public enum HostKeyDecision {
    /**
     * Represents the {@code ACCEPT_FIRST_USE} option.
     *
     * <p>表示 {@code ACCEPT_FIRST_USE} 选项。
     */
    ACCEPT_FIRST_USE,
    /**
     * Represents the {@code ACCEPT_EXISTING} option.
     *
     * <p>表示 {@code ACCEPT_EXISTING} 选项。
     */
    ACCEPT_EXISTING,
    /**
     * Represents the {@code REJECT} option.
     *
     * <p>表示 {@code REJECT} 选项。
     */
    REJECT
}
