package gold.debug.windowstolinux.shared.model.analysis;

import gold.debug.windowstolinux.shared.model.message.LocalizedMessage;

import java.util.Objects;

/**
 * A compact, non-secret source of one deterministic project fact.
 *
 *  <p>单个确定性项目事实的紧凑、非秘密来源。
 *
 * @param subject subject / 对象
 * @param source source identity or content read by the operation / 操作读取的源身份或内容
 * @param conclusion conclusion / 结论
 * @param confidence confidence / 置信度
 */
public record AnalysisEvidence(
        LocalizedMessage subject,
        String source,
        LocalizedMessage conclusion,
        EvidenceConfidenceLevel confidence
) {
    /**
     * Validates and binds the inputs required by analysis evidence.
     * <p>校验并绑定分析证据所需输入。
     *
     * @param subject subject / 对象
     * @param source source identity or content read by the operation / 操作读取的源身份或内容
     * @param conclusion conclusion / 结论
     * @param confidence confidence / 置信度
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public AnalysisEvidence {
        subject = Objects.requireNonNull(subject, "subject");
        source = requireText(source, "source");
        conclusion = Objects.requireNonNull(conclusion, "conclusion");
        confidence = Objects.requireNonNull(confidence, "confidence");
    }

    /**
     * Trims required text and rejects missing or invalid content.
     * <p>去除必填文本首尾空白，并拒绝缺失或无效内容。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     * @param name human-readable name or diagnostic field label / 可读名称或诊断字段标签
     * @return require text text / 要求文本文本
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    private static String requireText(String value, String name) {
        value = Objects.requireNonNull(value, name).trim();
        if (value.isBlank() || value.length() > 512) {
            throw new IllegalArgumentException(name + " must be a bounded non-blank value");
        }
        return value;
    }
}
