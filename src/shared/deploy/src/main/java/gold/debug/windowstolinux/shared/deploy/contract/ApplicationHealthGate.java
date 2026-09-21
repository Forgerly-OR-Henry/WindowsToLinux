package gold.debug.windowstolinux.shared.deploy.contract;

import gold.debug.windowstolinux.shared.model.health.HealthCheck;

import java.util.Objects;

/**
 * Whole-application health probe owned by one reviewed component endpoint.
 *
 *  <p>由一个经审阅组件端点承载的整体应用健康探测。
 *
 * @param componentId identifier within the reviewed component graph / 已审阅组件图内的标识
 * @param healthCheck reviewed probe and its success criteria / 已审阅探测及其成功条件
 */
public record ApplicationHealthGate(String componentId, HealthCheck healthCheck) {
    /**
     * Validates a bounded component health gate. / 验证有界组件健康门。
     *
     * @param componentId identifier within the reviewed component graph / 已审阅组件图内的标识
     * @param healthCheck reviewed probe and its success criteria / 已审阅探测及其成功条件
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public ApplicationHealthGate {
        componentId = Objects.requireNonNull(componentId, "componentId").trim();
        if (!componentId.matches("[a-z0-9][a-z0-9-]{0,62}")) {
            throw new IllegalArgumentException("componentId must be a bounded managed identifier");
        }
        healthCheck = Objects.requireNonNull(healthCheck, "healthCheck");
    }
}
