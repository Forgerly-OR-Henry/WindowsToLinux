package gold.debug.windowstolinux.shared.deploy.adapter;

import gold.debug.windowstolinux.shared.deploy.plan.PhaseTwoDeploymentPlan;
import gold.debug.windowstolinux.shared.deploy.plan.PhaseTwoDeploymentRequest;
import gold.debug.windowstolinux.shared.deploy.plan.PhaseTwoDeploymentStep;
import gold.debug.windowstolinux.shared.model.project.PhaseTwoProjectType;

import java.util.ArrayList;
import java.util.List;

/**
 * Internal shared ordering for typed Phase Two deployment adapters.
 *
 * <p>类型化二期部署适配器的内部共用顺序。
 */
public final class PhaseTwoPlanSupport {
    private PhaseTwoPlanSupport() { }

    /**
     * Produces a safe short-downtime transaction shape for one adapter.
     *
     * <p>为一个适配器生成安全短停机事务形状。
     *
     * @param request the reviewed request / 经审阅的请求
     * @param expectedType the expected type / 预期类型
     * @param staticOutput whether static output verification is required / 是否需要静态输出验证
     * @param containerPolicy whether container policy verification is required / 是否需要容器策略验证
     * @return the deterministic plan / 确定性计划
     */
    public static PhaseTwoDeploymentPlan plan(PhaseTwoDeploymentRequest request, PhaseTwoProjectType expectedType,
                                               boolean staticOutput, boolean containerPolicy) {
        if (request.facts().projectType() != expectedType) {
            throw new IllegalArgumentException("adapter cannot plan a different project type");
        }
        List<PhaseTwoDeploymentStep> steps = new ArrayList<>(List.of(
                PhaseTwoDeploymentStep.VERIFY_SOURCE_IDENTITY,
                PhaseTwoDeploymentStep.VERIFY_CONFIGURATION_SNAPSHOT,
                PhaseTwoDeploymentStep.VERIFY_SECRET_REVISIONS,
                PhaseTwoDeploymentStep.PREPARE_CANDIDATE,
                PhaseTwoDeploymentStep.BUILD,
                PhaseTwoDeploymentStep.VERIFY_ARTIFACT
        ));
        if (staticOutput) {
            steps.add(PhaseTwoDeploymentStep.VERIFY_STATIC_OUTPUT);
        }
        if (containerPolicy) {
            steps.add(PhaseTwoDeploymentStep.VERIFY_CONTAINER_POLICY);
        }
        steps.addAll(List.of(
                PhaseTwoDeploymentStep.STOP_PREVIOUS,
                PhaseTwoDeploymentStep.ACTIVATE_CANDIDATE,
                PhaseTwoDeploymentStep.CHECK_HEALTH,
                PhaseTwoDeploymentStep.COMMIT_RELEASE,
                PhaseTwoDeploymentStep.ROLLBACK_ON_FAILURE
        ));
        return new PhaseTwoDeploymentPlan(request, steps);
    }
}
