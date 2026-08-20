package gold.debug.windowstolinux.shared.deploy.execution.lifecycle;

import gold.debug.windowstolinux.shared.model.managed.ManagedApplication;
import gold.debug.windowstolinux.shared.model.health.HealthCheck;

import java.util.Objects;

/**
 * Secret-free managed identity and runtime needed for one component lifecycle operation.
 *
 * <p>单个组件生命周期操作所需的不含秘密的受管身份与运行时。
 */
public record ManagedComponentLifecycle(
        String componentId,
        ManagedApplication application,
        HealthCheck healthCheck
) {
    /** Validates the stable component identity. / 验证稳定组件身份。 */
    public ManagedComponentLifecycle {
        componentId = Objects.requireNonNull(componentId, "componentId").trim();
        if (!componentId.matches("[a-z0-9][a-z0-9-]{0,62}")) {
            throw new IllegalArgumentException("componentId must be a bounded managed identifier");
        }
        application = Objects.requireNonNull(application, "application");
        healthCheck = Objects.requireNonNull(healthCheck, "healthCheck");
    }
}
