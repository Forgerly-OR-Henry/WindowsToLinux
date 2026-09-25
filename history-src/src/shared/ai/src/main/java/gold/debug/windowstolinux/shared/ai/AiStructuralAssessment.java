package gold.debug.windowstolinux.shared.ai;

import java.util.Objects;

/**
 * Optional explanatory text; it never changes deterministic project support or deployment decisions.
 *
 *  <p>可选解释文本；它绝不改变确定性的项目支持判断或部署决策。
 *
 * @param explanation explanation / 解释
 */
public record AiStructuralAssessment(String explanation) {
    /**
     * Validates and binds the inputs required by ai structural assessment.
     * <p>校验并绑定AIStructural评估所需输入。
     *
     * @param explanation explanation / 解释
     * @throws IllegalArgumentException if an argument violates the required constraints / 参数违反必要约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public AiStructuralAssessment {
        explanation = Objects.requireNonNull(explanation, "explanation").trim();
        if (explanation.isBlank()) {
            throw new IllegalArgumentException("explanation must not be blank");
        }
    }
}
