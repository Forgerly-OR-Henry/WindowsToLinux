package gold.debug.windowstolinux.shared.ai.collaboration;

/** Bounded advisory decision that never authorizes execution. / 绝不授权执行的有界建议决策。 */
public enum AiAdviceDecision {
    /** No additional concern was found in the supplied redacted facts. / 在提供的脱敏事实中未发现额外问题。 */
    CLEAR,
    /** A human must resolve an ambiguity or conflict. / 必须由人工解决歧义或冲突。 */
    NEEDS_HUMAN_DECISION,
    /** The safest advisory outcome is to stop. / 最安全的建议结果是停止。 */
    SAFE_STOP
}
