package gold.debug.windowstolinux.shared.model.analysis;

import gold.debug.windowstolinux.shared.model.message.LocalizedMessage;

import java.util.Objects;

/**
 * A compact, non-secret source of one deterministic project fact.
 *
 * <p>单个确定性项目事实的紧凑、非秘密来源。
 *
 * @param subject the {@code subject} value / {@code subject} 值
 * @param source the {@code source} value / {@code source} 值
 * @param conclusion the {@code conclusion} value / {@code conclusion} 值
 * @param confidence the {@code confidence} value / {@code confidence} 值
 */
public record AnalysisEvidence(
        LocalizedMessage subject,
        String source,
        LocalizedMessage conclusion,
        EvidenceConfidence confidence
) {
    /**
     * Creates a {@code AnalysisEvidence} instance.
     *
     * <p>创建 {@code AnalysisEvidence} 实例。
     *
     * @param subject the {@code subject} value / {@code subject} 值
     * @param source the {@code source} value / {@code source} 值
     * @param conclusion the {@code conclusion} value / {@code conclusion} 值
     * @param confidence the {@code confidence} value / {@code confidence} 值
     * @throws NullPointerException if a required argument is {@code null} / 必要参数为 {@code null} 时
     */
    public AnalysisEvidence {
        subject = Objects.requireNonNull(subject, "subject");
        source = requireText(source, "source");
        conclusion = Objects.requireNonNull(conclusion, "conclusion");
        confidence = Objects.requireNonNull(confidence, "confidence");
    }

    private static String requireText(String value, String name) {
        value = Objects.requireNonNull(value, name).trim();
        if (value.isBlank() || value.length() > 512) {
            throw new IllegalArgumentException(name + " must be a bounded non-blank value");
        }
        return value;
    }
}
