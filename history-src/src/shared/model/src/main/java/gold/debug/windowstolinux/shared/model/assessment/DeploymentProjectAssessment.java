package gold.debug.windowstolinux.shared.model.assessment;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import gold.debug.windowstolinux.shared.model.analysis.DeploymentAdmissionStatus;
import gold.debug.windowstolinux.shared.model.analysis.RejectionReason;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectFacts;
import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeAssessment;

/**
 * A typed deployment static-analysis result that separates missing user decisions from hard safety rejection.
 *
 *  <p>将缺失用户决定与硬性安全拒绝分开的部署静态分析结果。
 *
 * @param admission the deterministic admission status / 确定性准入状态
 * @param facts the observed facts when a safe source root exists / 存在安全源码根时的观察事实
 * @param runtimeSuggestion source-backed runtime values that still require user review / 仍需用户审阅的源码依据运行时值
 * @param rejections the hard rejection reasons / 硬性拒绝原因
 */
public record DeploymentProjectAssessment(DeploymentAdmissionStatus admission, Optional<DeploymentProjectFacts> facts,
        Optional<DeploymentRuntimeAssessment> runtimeSuggestion, List<RejectionReason> rejections) {
    /**
     * Validates and binds the inputs required by deployment project assessment.
     * <p>校验并绑定部署项目评估所需输入。
     *
     * @param admission the deterministic admission status / 确定性准入状态
     * @param facts typed facts used for deterministic planning / 确定性计划使用的类型化事实
     * @param runtimeSuggestion source-backed runtime values that still require user review / 仍需用户审阅的源码依据运行时值
     * @param rejections reasons preventing admission to the next stage / 阻止进入下一阶段的原因
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public DeploymentProjectAssessment {
        admission = Objects.requireNonNull(admission, "admission");
        facts = Objects.requireNonNull(facts, "facts");
        runtimeSuggestion = Objects.requireNonNull(runtimeSuggestion, "runtimeSuggestion");
        rejections = List.copyOf(Objects.requireNonNull(rejections, "rejections"));
        if (admission == DeploymentAdmissionStatus.REJECTED && rejections.isEmpty()) {
            throw new IllegalArgumentException("rejected assessments require at least one rejection reason");
        }
        if (admission != DeploymentAdmissionStatus.REJECTED && (facts.isEmpty() || !rejections.isEmpty())) {
            throw new IllegalArgumentException("non-rejected assessments require facts and no rejection reasons");
        }
        if (admission == DeploymentAdmissionStatus.REJECTED && runtimeSuggestion.isPresent()) {
            throw new IllegalArgumentException("rejected assessments must not expose runtime suggestions");
        }
        if (runtimeSuggestion.isPresent() && facts.isPresent()
                && runtimeSuggestion.orElseThrow().projectType() != facts.orElseThrow().projectType()) {
            throw new IllegalArgumentException("runtime suggestion must match the analyzed project type");
        }
        if (admission == DeploymentAdmissionStatus.READY_FOR_PLANNING && !facts.orElseThrow().readyForPlanning()) {
            throw new IllegalArgumentException("ready assessments must not have conflicts or missing information");
        }
        if (admission == DeploymentAdmissionStatus.RECOGNITION_PREVIEW
                && facts.orElseThrow().support().level().deployable()) {
            throw new IllegalArgumentException("recognition preview must not expose a deployable support level");
        }
    }

    /**
     * Creates a ready assessment.
     *
     *  <p>创建可计划评估。
     *
     * @param facts the complete facts / 完整事实
     * @return the ready assessment / 可计划评估
     */
    public static DeploymentProjectAssessment ready(DeploymentProjectFacts facts) {
        return new DeploymentProjectAssessment(DeploymentAdmissionStatus.READY_FOR_PLANNING, Optional.of(facts),
                Optional.empty(), List.of());
    }

    /**
     * Creates a ready assessment with source-backed runtime suggestions. / 创建带有源码依据运行时建议的可计划评估。
     *
     * @param facts typed facts used for deterministic planning / 确定性计划使用的类型化事实
     * @param runtimeSuggestion source-backed runtime values that still require user review / 仍需用户审阅的源码依据运行时值
     * @return a ready assessment with source-backed runtime suggestions / 带有源码依据运行时建议的可计划评估
     */
    public static DeploymentProjectAssessment ready(DeploymentProjectFacts facts,
            DeploymentRuntimeAssessment runtimeSuggestion) {
        return new DeploymentProjectAssessment(DeploymentAdmissionStatus.READY_FOR_PLANNING, Optional.of(facts),
                Optional.of(runtimeSuggestion), List.of());
    }

    /**
     * Creates an assessment that needs explicit input.
     *
     *  <p>创建需要显式输入的评估。
     *
     * @param facts the partial safe facts / 部分安全事实
     * @return the input-required assessment / 需要输入的评估
     */
    public static DeploymentProjectAssessment requiresInput(DeploymentProjectFacts facts) {
        return new DeploymentProjectAssessment(DeploymentAdmissionStatus.REQUIRES_INPUT, Optional.of(facts),
                Optional.empty(), List.of());
    }

    /**
     * Creates an input-required assessment with source-backed runtime suggestions. / 创建带有源码依据运行时建议的需要输入评估。
     *
     * @param facts typed facts used for deterministic planning / 确定性计划使用的类型化事实
     * @param runtimeSuggestion source-backed runtime values that still require user review / 仍需用户审阅的源码依据运行时值
     * @return an input-required assessment with source-backed runtime suggestions / 带有源码依据运行时建议的需要输入评估
     */
    public static DeploymentProjectAssessment requiresInput(DeploymentProjectFacts facts,
            DeploymentRuntimeAssessment runtimeSuggestion) {
        return new DeploymentProjectAssessment(DeploymentAdmissionStatus.REQUIRES_INPUT, Optional.of(facts),
                Optional.of(runtimeSuggestion), List.of());
    }

    /**
     * Creates a mutation-free recognition preview. / 创建禁止修改目标机的识别预览。
     *
     * @param facts typed facts used for deterministic planning / 确定性计划使用的类型化事实
     * @return a mutation-free recognition preview / 禁止修改目标机的识别预览
     */
    public static DeploymentProjectAssessment recognitionPreview(DeploymentProjectFacts facts) {
        return new DeploymentProjectAssessment(DeploymentAdmissionStatus.RECOGNITION_PREVIEW, Optional.of(facts),
                Optional.empty(), List.of());
    }

    /**
     * Creates a rejected assessment.
     *
     *  <p>创建被拒绝的评估。
     *
     * @param rejections the rejection reasons / 拒绝原因
     * @return the rejected assessment / 被拒绝的评估
     */
    public static DeploymentProjectAssessment rejected(List<RejectionReason> rejections) {
        return new DeploymentProjectAssessment(DeploymentAdmissionStatus.REJECTED, Optional.empty(), Optional.empty(),
                rejections);
    }
}
