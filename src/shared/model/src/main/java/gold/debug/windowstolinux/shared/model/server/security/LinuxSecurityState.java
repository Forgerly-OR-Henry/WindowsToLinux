package gold.debug.windowstolinux.shared.model.server.security;

/**
 * Observed mandatory-access-control state. / 观测到的强制访问控制状态。
 */
public enum LinuxSecurityState {
    /**
     * Policy is enforced. / 策略正在强制执行。
     */ ENFORCING,
    /**
     * Policy is loaded without enforcement. / 策略已加载但未强制执行。
     */ PERMISSIVE,
    /**
     * The module is enabled with module-specific enforcement. / 模块已启用并由模块自身执行策略。
     */ ENABLED,
    /**
     * The module is disabled. / 模块已禁用。
     */ DISABLED,
    /**
     * State could not be determined. / 无法确定状态。
     */ UNKNOWN
}
