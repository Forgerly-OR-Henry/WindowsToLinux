package gold.debug.windowstolinux.shared.model.analysis;

/**
 * Whether a source project is eligible for the current delivery phase.
 *
 * <p>源码项目是否符合当前交付阶段条件。
 */
public enum SupportDecision {
    /**
     * Represents the {@code SUPPORTED} option.
     *
     * <p>表示 {@code SUPPORTED} 选项。
     */
    SUPPORTED,
    /**
     * Represents the {@code REJECTED} option.
     *
     * <p>表示 {@code REJECTED} 选项。
     */
    REJECTED
}
