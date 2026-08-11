package gold.debug.windowstolinux.shared.deploy.plan;

import java.util.List;
import java.util.Objects;

/**
 * A fully deterministic Phase Two deployment transaction plan; it contains no transport implementation or raw command.
 *
 * <p>完全确定性的二期部署事务计划；不包含传输实现或原始命令。
 *
 * @param request the reviewed deployment input / 经审阅的部署输入
 * @param steps the ordered fixed transaction stages / 有序固定事务阶段
 */
public record PhaseTwoDeploymentPlan(PhaseTwoDeploymentRequest request, List<PhaseTwoDeploymentStep> steps) {
    /**
     * Creates a {@code PhaseTwoDeploymentPlan} instance.
     *
     * <p>创建 {@code PhaseTwoDeploymentPlan} 实例。
     */
    public PhaseTwoDeploymentPlan {
        request = Objects.requireNonNull(request, "request");
        steps = List.copyOf(Objects.requireNonNull(steps, "steps"));
        if (steps.isEmpty() || steps.getFirst() != PhaseTwoDeploymentStep.VERIFY_SOURCE_IDENTITY
                || !steps.contains(PhaseTwoDeploymentStep.ROLLBACK_ON_FAILURE)
                || !steps.contains(PhaseTwoDeploymentStep.CHECK_HEALTH)) {
            throw new IllegalArgumentException("deployment plans must bind source, check health, and retain rollback");
        }
        if (steps.stream().distinct().count() != steps.size()) {
            throw new IllegalArgumentException("deployment plan steps must not repeat");
        }
    }
}
