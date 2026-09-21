package gold.debug.windowstolinux.shared.ai.collaboration.advice;

import java.util.List;
import java.util.Objects;

/**
 * Validated bounded advisory output from one fixed role. / 来自一个固定角色的已验证有界建议输出。
 *
 * @param decision decision / 决定
 * @param summary summary / 摘要
 * @param findings findings / 分析发现集合
 */
public record RoleAdviceAssessment(AiAdviceDecision decision, String summary, List<String> findings) {
    /**
     * Validates the bounded output after strict parsing. / 严格解析后验证有界输出。
     *
     * @param decision decision / 决定
     * @param summary summary / 摘要
     * @param findings findings / 分析发现集合
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public RoleAdviceAssessment {
        decision = Objects.requireNonNull(decision, "decision");
        summary = bounded(summary, "summary", 512);
        findings = Objects.requireNonNull(findings, "findings");
        if (findings.size() > 5) throw new IllegalArgumentException("too many findings");
        findings = findings.stream().map(value -> bounded(value, "finding", 256)).toList();
    }

    /**
     * Rejects content exceeding the explicit size or count bound.
     * <p>拒绝超出显式大小或数量限制的内容。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     * @param name human-readable name or diagnostic field label / 可读名称或诊断字段标签
     * @param maximum maximum / 最大
     * @return bounded text / 有界文本
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    private static String bounded(String value, String name, int maximum) {
        value = Objects.requireNonNull(value, name).trim();
        if (value.isBlank() || value.length() > maximum) {
            throw new IllegalArgumentException(name + " is blank or too long");
        }
        return value;
    }
}
