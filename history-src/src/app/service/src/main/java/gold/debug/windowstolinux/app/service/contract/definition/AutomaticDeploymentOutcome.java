package gold.debug.windowstolinux.app.service.contract.definition;

import java.util.Map;

import gold.debug.windowstolinux.app.service.deployment.single.DeploymentHandoff;
import gold.debug.windowstolinux.shared.model.deployment.DeploymentStatus;

/**
 * Whole-operation handoff; unsuccessful operations never expose success links. / 整次操作的交付信息，失败操作不暴露成功链接。
 *
 * @param applicationId managed application identifier / 受管应用标识
 * @param status classification of the current operation result / 当前操作结果的分类
 * @param handoffs handoffs / 交接集合
 */
public record AutomaticDeploymentOutcome(String applicationId, DeploymentStatus status,
        Map<String, DeploymentHandoff> handoffs) {
    /**
     * Validates that only successful complete transactions provide handoffs. / 校验仅成功完成的事务提供交付信息。
     *
     * @param applicationId managed application identifier / 受管应用标识
     * @param status classification of the current operation result / 当前操作结果的分类
     * @param handoffs handoffs / 交接集合
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     */
    public AutomaticDeploymentOutcome {
        handoffs = Map.copyOf(handoffs);
        if (status != DeploymentStatus.SUCCEEDED && !handoffs.isEmpty())
            throw new IllegalArgumentException("unsuccessful operation cannot expose handoffs");
    }
}
