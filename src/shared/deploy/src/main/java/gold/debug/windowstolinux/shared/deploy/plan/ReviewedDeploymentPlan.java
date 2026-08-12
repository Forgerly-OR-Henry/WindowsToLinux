package gold.debug.windowstolinux.shared.deploy.plan;

import java.util.List;
import java.util.Objects;

/**
 * A fully deterministic typed deployment deployment transaction plan; it contains no transport implementation or raw command.
 *
 * <p>完全确定性的部署部署事务计划；不包含传输实现或原始命令。
 *
 * @param request the reviewed deployment input / 经审阅的部署输入
 * @param steps the ordered fixed transaction stages / 有序固定事务阶段
 */
public record ReviewedDeploymentPlan(ReviewedDeploymentRequest request, List<DeploymentStep> steps) {
    /**
     * Creates a {@code ReviewedDeploymentPlan} instance.
     *
     * <p>创建 {@code ReviewedDeploymentPlan} 实例。
     */
    public ReviewedDeploymentPlan {
        request = Objects.requireNonNull(request, "request");
        steps = List.copyOf(Objects.requireNonNull(steps, "steps"));
        if (steps.isEmpty() || steps.getFirst() != DeploymentStep.VERIFY_SOURCE_IDENTITY
                || !steps.contains(DeploymentStep.ROLLBACK_ON_FAILURE)
                || !steps.contains(DeploymentStep.CHECK_HEALTH)) {
            throw new IllegalArgumentException("deployment plans must bind source, check health, and retain rollback");
        }
        if (steps.stream().distinct().count() != steps.size()) {
            throw new IllegalArgumentException("deployment plan steps must not repeat");
        }
    }
}
