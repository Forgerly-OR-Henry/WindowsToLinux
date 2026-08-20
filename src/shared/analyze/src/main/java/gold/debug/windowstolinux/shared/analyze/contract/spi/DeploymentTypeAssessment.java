package gold.debug.windowstolinux.shared.analyze.contract.spi;

import gold.debug.windowstolinux.shared.model.project.DeploymentProjectFacts;
import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeAssessment;

import java.util.Objects;

/**
 * Local facts and runtime suggestions returned by one type inspector.
 *
 * <p>由一个类型检查器返回的局部事实与运行时建议。
 *
 * @param facts deterministic project facts / 确定性项目事实
 * @param runtimeSuggestion reviewable runtime suggestions / 可审阅的运行时建议
 */
public record DeploymentTypeAssessment(DeploymentProjectFacts facts,
                                       DeploymentRuntimeAssessment runtimeSuggestion) {
    /** Validates matching local results. / 验证匹配的局部结果。 */
    public DeploymentTypeAssessment {
        facts = Objects.requireNonNull(facts, "facts");
        runtimeSuggestion = Objects.requireNonNull(runtimeSuggestion, "runtimeSuggestion");
        if (facts.projectType() != runtimeSuggestion.projectType()) {
            throw new IllegalArgumentException("facts and runtime suggestion must use the same project type");
        }
    }
}
