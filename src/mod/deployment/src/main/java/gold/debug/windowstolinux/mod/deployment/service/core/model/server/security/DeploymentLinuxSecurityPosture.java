package gold.debug.windowstolinux.mod.deployment.service.core.model.server.security;

import java.util.Objects;

/**
 * Read-only security and firewall facts retained as one coherent host observation.
 *
 *  <p>作为一个连贯主机观测保留的只读安全与防火墙事实。
 *
 * @param module mandatory-access-control implementation / 强制访问控制实现
 * @param state mandatory-access-control state / 强制访问控制状态
 * @param firewall firewall manager / 防火墙管理器
 * @param firewallState firewall service state / 防火墙服务状态
 */
public record DeploymentLinuxSecurityPosture(DeploymentLinuxSecurityModuleType module,
        DeploymentLinuxSecurityState state, DeploymentLinuxFirewallKind firewall,
        DeploymentLinuxFirewallState firewallState) {
    /**
     * Creates a coherent posture observation. / 创建连贯的安全态势观测。
     *
     * @param module mandatory-access-control implementation / 强制访问控制实现
     * @param state current lifecycle or workflow state / 当前生命周期或工作流状态
     * @param firewall firewall manager / 防火墙管理器
     * @param firewallState firewall service state / 防火墙服务状态
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public DeploymentLinuxSecurityPosture {
        module = Objects.requireNonNull(module, "module");
        state = Objects.requireNonNull(state, "state");
        firewall = Objects.requireNonNull(firewall, "firewall");
        firewallState = Objects.requireNonNull(firewallState, "firewallState");
        if (module == DeploymentLinuxSecurityModuleType.SELINUX && state == DeploymentLinuxSecurityState.ENABLED) {
            throw new IllegalArgumentException("SELinux must report enforcing, permissive, disabled, or unknown");
        }
        if (module == DeploymentLinuxSecurityModuleType.APPARMOR && (state == DeploymentLinuxSecurityState.ENFORCING
                || state == DeploymentLinuxSecurityState.PERMISSIVE)) {
            throw new IllegalArgumentException("AppArmor must report enabled, disabled, or unknown");
        }
    }
}
