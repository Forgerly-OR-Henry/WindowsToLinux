package gold.debug.windowstolinux.shared.ai.parser;

import java.util.Objects;

/**
 * Optional explanatory text; it never changes deterministic project support or deployment decisions.
 *
 * <p>可选解释文本；它绝不改变确定性的项目支持判断或部署决策。
 *
 * @param explanation the {@code explanation} value / {@code explanation} 值
 */
public record AiStructuralAnalysis(String explanation) {
    /**
     * Creates a {@code AiStructuralAnalysis} instance.
     *
     * <p>创建 {@code AiStructuralAnalysis} 实例。
     *
     * @param explanation the {@code explanation} value / {@code explanation} 值
     * @throws IllegalArgumentException if an argument violates the required constraints / 参数违反必要约束时
     * @throws NullPointerException if a required argument is {@code null} / 必要参数为 {@code null} 时
     */
    public AiStructuralAnalysis {
        explanation = Objects.requireNonNull(explanation, "explanation").trim();
        if (explanation.isBlank()) {
            throw new IllegalArgumentException("explanation must not be blank");
        }
    }
}
