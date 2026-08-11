package gold.debug.windowstolinux.shared.model.analysis;

import gold.debug.windowstolinux.shared.model.project.SourceProjectFacts;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Result of deterministic static source assessment; it never means code was executed.
 *
 * <p>确定性静态源码评估结果；绝不表示代码已被执行。
 *
 * @param decision the {@code decision} value / {@code decision} 值
 * @param facts the {@code facts} value / {@code facts} 值
 * @param rejections the {@code rejections} value / {@code rejections} 值
 */
public record ProjectAssessment(
        SupportDecision decision,
        Optional<SourceProjectFacts> facts,
        List<RejectionReason> rejections
) {
    /**
     * Creates a {@code ProjectAssessment} instance.
     *
     * <p>创建 {@code ProjectAssessment} 实例。
     *
     * @param decision the {@code decision} value / {@code decision} 值
     * @param facts the {@code facts} value / {@code facts} 值
     * @param rejections the {@code rejections} value / {@code rejections} 值
     * @throws IllegalArgumentException if an argument violates the required constraints / 参数违反必要约束时
     * @throws NullPointerException if a required argument is {@code null} / 必要参数为 {@code null} 时
     */
    public ProjectAssessment {
        decision = Objects.requireNonNull(decision, "decision");
        facts = Objects.requireNonNull(facts, "facts");
        rejections = List.copyOf(Objects.requireNonNull(rejections, "rejections"));
        if (decision == SupportDecision.SUPPORTED && (facts.isEmpty() || !rejections.isEmpty())) {
            throw new IllegalArgumentException("Supported assessments require facts and no rejections");
        }
        if (decision == SupportDecision.REJECTED && rejections.isEmpty()) {
            throw new IllegalArgumentException("Rejected assessments require at least one reason");
        }
    }

    /**
     * Performs the {@code supported} operation.
     *
     * <p>执行 {@code supported} 操作。
     *
     * @param facts the {@code facts} value / {@code facts} 值
     * @return the operation result / 操作结果
     */
    public static ProjectAssessment supported(SourceProjectFacts facts) {
        return new ProjectAssessment(SupportDecision.SUPPORTED, Optional.of(facts), List.of());
    }

    /**
     * Performs the {@code rejected} operation.
     *
     * <p>执行 {@code rejected} 操作。
     *
     * @param rejections the {@code rejections} value / {@code rejections} 值
     * @return the operation result / 操作结果
     */
    public static ProjectAssessment rejected(List<RejectionReason> rejections) {
        return new ProjectAssessment(SupportDecision.REJECTED, Optional.empty(), rejections);
    }
}
