package gold.debug.windowstolinux.shared.model.analysis;

import gold.debug.windowstolinux.shared.model.project.DeploymentProjectFacts;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A typed deployment static-analysis result that separates missing user decisions from hard safety rejection.
 *
 * <p>将缺失用户决定与硬性安全拒绝分开的部署静态分析结果。
 *
 * @param admission the deterministic admission status / 确定性准入状态
 * @param facts the observed facts when a safe source root exists / 存在安全源码根时的观察事实
 * @param rejections the hard rejection reasons / 硬性拒绝原因
 */
public record DeploymentProjectAssessment(
        DeploymentAdmission admission,
        Optional<DeploymentProjectFacts> facts,
        List<RejectionReason> rejections
) {
    /**
     * Creates a {@code DeploymentProjectAssessment} instance.
     *
     * <p>创建 {@code DeploymentProjectAssessment} 实例。
     */
    public DeploymentProjectAssessment {
        admission = Objects.requireNonNull(admission, "admission");
        facts = Objects.requireNonNull(facts, "facts");
        rejections = List.copyOf(Objects.requireNonNull(rejections, "rejections"));
        if (admission == DeploymentAdmission.REJECTED && rejections.isEmpty()) {
            throw new IllegalArgumentException("rejected assessments require at least one rejection reason");
        }
        if (admission != DeploymentAdmission.REJECTED && (facts.isEmpty() || !rejections.isEmpty())) {
            throw new IllegalArgumentException("non-rejected assessments require facts and no rejection reasons");
        }
        if (admission == DeploymentAdmission.READY_FOR_PLANNING && !facts.orElseThrow().readyForPlanning()) {
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
    public static DeploymentProjectAssessment ready(DeploymentProjectFacts facts) {
        return new DeploymentProjectAssessment(DeploymentAdmission.READY_FOR_PLANNING, Optional.of(facts), List.of());
    }

    /**
     * Creates an assessment that needs explicit input.
     *
     * <p>创建需要显式输入的评估。
     *
     * @param facts the partial safe facts / 部分安全事实
     * @return the input-required assessment / 需要输入的评估
     */
    public static DeploymentProjectAssessment requiresInput(DeploymentProjectFacts facts) {
        return new DeploymentProjectAssessment(DeploymentAdmission.REQUIRES_INPUT, Optional.of(facts), List.of());
    }

    /**
     * Creates a rejected assessment.
     *
     * <p>创建被拒绝的评估。
     *
     * @param rejections the rejection reasons / 拒绝原因
     * @return the rejected assessment / 被拒绝的评估
     */
    public static DeploymentProjectAssessment rejected(List<RejectionReason> rejections) {
        return new DeploymentProjectAssessment(DeploymentAdmission.REJECTED, Optional.empty(), rejections);
    }
}
