package gold.debug.windowstolinux.shared.deploy.adapter;

import gold.debug.windowstolinux.shared.deploy.contract.ReviewedDeploymentPlan;
import gold.debug.windowstolinux.shared.deploy.contract.ReviewedDeploymentRequest;
import gold.debug.windowstolinux.shared.deploy.contract.DeploymentStep;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectType;

import java.util.ArrayList;
import java.util.List;

/**
 * Internal shared ordering for typed deployment adapters.
 *
 * <p>类型化部署适配器的内部共用顺序。
 */
public final class DeploymentPlanFactory {
    private DeploymentPlanFactory() { }

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
    public static ReviewedDeploymentPlan plan(ReviewedDeploymentRequest request, DeploymentProjectType expectedType,
                                               boolean staticOutput, boolean containerPolicy) {
        if (request.facts().projectType() != expectedType) {
            throw new IllegalArgumentException("adapter cannot plan a different project type");
        }
        List<DeploymentStep> steps = new ArrayList<>(List.of(
                DeploymentStep.VERIFY_SOURCE_IDENTITY,
                DeploymentStep.VERIFY_CONFIGURATION_SNAPSHOT,
                DeploymentStep.VERIFY_SECRET_REVISIONS,
                DeploymentStep.PREPARE_CANDIDATE,
                DeploymentStep.BUILD,
                DeploymentStep.VERIFY_ARTIFACT
        ));
        if (staticOutput) {
            steps.add(DeploymentStep.VERIFY_STATIC_OUTPUT);
        }
        if (containerPolicy) {
            steps.add(DeploymentStep.VERIFY_CONTAINER_POLICY);
        }
        steps.addAll(List.of(
                DeploymentStep.STOP_PREVIOUS,
                DeploymentStep.ACTIVATE_CANDIDATE,
                DeploymentStep.CHECK_HEALTH,
                DeploymentStep.COMMIT_RELEASE,
                DeploymentStep.ROLLBACK_ON_FAILURE
        ));
        return new ReviewedDeploymentPlan(request, steps);
    }
}
