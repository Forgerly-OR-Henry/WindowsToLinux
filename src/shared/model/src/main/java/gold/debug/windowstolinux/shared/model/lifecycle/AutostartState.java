package gold.debug.windowstolinux.shared.model.lifecycle;

/**
 * The verified systemd enablement state for a managed unit.
 *
 * <p>受管单元经过验证的 systemd 启用状态。
 */
public enum AutostartState {
    /**
     * Represents the {@code ENABLED} option.
     *
     * <p>表示 {@code ENABLED} 选项。
     */
    ENABLED,
    /**
     * Represents the {@code DISABLED} option.
     *
     * <p>表示 {@code DISABLED} 选项。
     */
    DISABLED,
    /**
     * Represents the {@code UNKNOWN} option.
     *
     * <p>表示 {@code UNKNOWN} 选项。
     */
    UNKNOWN,
    /**
     * Represents the {@code ERROR} option.
     *
     * <p>表示 {@code ERROR} 选项。
     */
    ERROR
}
