package gold.debug.windowstolinux.shared.deploy.execution.lifecycle;

import java.util.Objects;

import gold.debug.windowstolinux.shared.model.health.HealthCheck;
import gold.debug.windowstolinux.shared.model.managed.ManagedApplication;

/**
 * Secret-free managed identity and runtime needed for one component lifecycle operation.
 *
 *  <p>单个组件生命周期操作所需的不含秘密的受管身份与运行时。
 *
 * @param componentId identifier within the reviewed component graph / 已审阅组件图内的标识
 * @param application managed target with its server and ownership identity / 携带服务器及归属身份的受管目标
 * @param healthCheck reviewed probe and its success criteria / 已审阅探测及其成功条件
 */
public record ManagedComponentLifecycle(String componentId, ManagedApplication application, HealthCheck healthCheck) {
    /**
     * Validates the stable component identity. / 验证稳定组件身份。
     *
     * @param componentId identifier within the reviewed component graph / 已审阅组件图内的标识
     * @param application managed target with its server and ownership identity / 携带服务器及归属身份的受管目标
     * @param healthCheck reviewed probe and its success criteria / 已审阅探测及其成功条件
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public ManagedComponentLifecycle {
        componentId = Objects.requireNonNull(componentId, "componentId").trim();
        if (!componentId.matches("[a-z0-9][a-z0-9-]{0,62}")) {
            throw new IllegalArgumentException("componentId must be a bounded managed identifier");
        }
        application = Objects.requireNonNull(application, "application");
        healthCheck = Objects.requireNonNull(healthCheck, "healthCheck");
    }
}
