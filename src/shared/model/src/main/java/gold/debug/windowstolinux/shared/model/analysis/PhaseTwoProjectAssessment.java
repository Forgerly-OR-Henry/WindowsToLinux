package gold.debug.windowstolinux.shared.model.analysis;

import gold.debug.windowstolinux.shared.model.project.PhaseTwoProjectFacts;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A Phase Two static-analysis result that separates missing user decisions from hard safety rejection.
 *
 * <p>将缺失用户决定与硬性安全拒绝分开的二期静态分析结果。
 *
 * @param admission the deterministic admission status / 确定性准入状态
 * @param facts the observed facts when a safe source root exists / 存在安全源码根时的观察事实
 * @param rejections the hard rejection reasons / 硬性拒绝原因
 */
public record PhaseTwoProjectAssessment(
        PhaseTwoAdmission admission,
        Optional<PhaseTwoProjectFacts> facts,
        List<RejectionReason> rejections
) {
    /**
     * Creates a {@code PhaseTwoProjectAssessment} instance.
     *
     * <p>创建 {@code PhaseTwoProjectAssessment} 实例。
     */
    public PhaseTwoProjectAssessment {
        admission = Objects.requireNonNull(admission, "admission");
        facts = Objects.requireNonNull(facts, "facts");
        rejections = List.copyOf(Objects.requireNonNull(rejections, "rejections"));
        if (admission == PhaseTwoAdmission.REJECTED && rejections.isEmpty()) {
            throw new IllegalArgumentException("rejected assessments require at least one rejection reason");
        }
        if (admission != PhaseTwoAdmission.REJECTED && (facts.isEmpty() || !rejections.isEmpty())) {
            throw new IllegalArgumentException("non-rejected assessments require facts and no rejection reasons");
        }
        if (admission == PhaseTwoAdmission.READY_FOR_PLANNING && !facts.orElseThrow().readyForPlanning()) {
            throw new IllegalArgumentException("ready assessments must not have conflicts or missing information");
        }
    }

    /**
     * Creates a ready assessment.
     *
     * <p>创建可计划评估。
     *
     * @param facts the complete facts / 完整事实
     * @return the ready assessment / 可计划评估
     */
    public static PhaseTwoProjectAssessment ready(PhaseTwoProjectFacts facts) {
        return new PhaseTwoProjectAssessment(PhaseTwoAdmission.READY_FOR_PLANNING, Optional.of(facts), List.of());
    }

    /**
     * Creates an assessment that needs explicit input.
     *
     * <p>创建需要显式输入的评估。
     *
     * @param facts the partial safe facts / 部分安全事实
     * @return the input-required assessment / 需要输入的评估
     */
    public static PhaseTwoProjectAssessment requiresInput(PhaseTwoProjectFacts facts) {
        return new PhaseTwoProjectAssessment(PhaseTwoAdmission.REQUIRES_INPUT, Optional.of(facts), List.of());
    }

    /**
     * Creates a rejected assessment.
     *
     * <p>创建被拒绝的评估。
     *
     * @param rejections the rejection reasons / 拒绝原因
     * @return the rejected assessment / 被拒绝的评估
     */
    public static PhaseTwoProjectAssessment rejected(List<RejectionReason> rejections) {
        return new PhaseTwoProjectAssessment(PhaseTwoAdmission.REJECTED, Optional.empty(), rejections);
    }
}
