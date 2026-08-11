package gold.debug.windowstolinux.shared.model.lifecycle;

/**
 * Supported phase-one actions; no delete or arbitrary service action exists.
 *
 * <p>受支持的一期动作；不存在删除或任意服务动作。
 */
public enum LifecycleAction {
    /**
     * Represents the {@code REFRESH_STATUS} option.
     *
     * <p>表示 {@code REFRESH_STATUS} 选项。
     */
    REFRESH_STATUS,
    /**
     * Represents the {@code START} option.
     *
     * <p>表示 {@code START} 选项。
     */
    START,
    /**
     * Represents the {@code STOP} option.
     *
     * <p>表示 {@code STOP} 选项。
     */
    STOP,
    /**
     * Represents the {@code RESTART} option.
     *
     * <p>表示 {@code RESTART} 选项。
     */
    RESTART,
    /**
     * Represents the {@code ENABLE_AUTOSTART} option.
     *
     * <p>表示 {@code ENABLE_AUTOSTART} 选项。
     */
    ENABLE_AUTOSTART,
    /**
     * Represents the {@code DISABLE_AUTOSTART} option.
     *
     * <p>表示 {@code DISABLE_AUTOSTART} 选项。
     */
    DISABLE_AUTOSTART
}
