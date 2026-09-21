package gold.debug.windowstolinux.shared.deploy.contract;

import java.util.List;
import java.util.Objects;

/**
 * A fully deterministic typed deployment transaction plan; it contains no transport implementation or raw command.
 *
 *  <p>完全确定性的部署事务计划；不包含传输实现或原始命令。
 *
 * @param request the reviewed deployment input / 经审阅的部署输入
 * @param steps the ordered fixed transaction stages / 有序固定事务阶段
 */
public record ReviewedDeploymentPlan(ReviewedDeploymentRequest request, List<DeploymentPlanAction> steps) {
    /**
     * Validates and binds the inputs required by reviewed deployment plan.
     * <p>校验并绑定已审阅部署计划所需输入。
     *
     * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
     * @param steps the ordered fixed transaction stages / 有序固定事务阶段
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public ReviewedDeploymentPlan {
        request = Objects.requireNonNull(request, "request");
        steps = List.copyOf(Objects.requireNonNull(steps, "steps"));
        if (steps.isEmpty() || steps.getFirst() != DeploymentPlanAction.VERIFY_SOURCE_IDENTITY
                || !steps.contains(DeploymentPlanAction.ROLLBACK_ON_FAILURE)
                || !steps.contains(DeploymentPlanAction.CHECK_HEALTH)) {
            throw new IllegalArgumentException("deployment plans must bind source, check health, and retain rollback");
        }
        if (steps.stream().distinct().count() != steps.size()) {
            throw new IllegalArgumentException("deployment plan steps must not repeat");
        }
    }
}
