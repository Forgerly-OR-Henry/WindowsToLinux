package gold.debug.windowstolinux.shared.model.analysis;

/**
 * Confidence of one deterministic fact, distinct from any AI suggestion.
 *
 * <p>单个确定性事实的置信度，与任何 AI 建议相互独立。
 */
public enum EvidenceConfidence {
    /**
     * Represents the {@code HIGH} option.
     *
     * <p>表示 {@code HIGH} 选项。
     */
    HIGH,
    /**
     * Represents the {@code MEDIUM} option.
     *
     * <p>表示 {@code MEDIUM} 选项。
     */
    MEDIUM,
    /**
     * Represents the {@code LOW} option.
     *
     * <p>表示 {@code LOW} 选项。
     */
    LOW
}
