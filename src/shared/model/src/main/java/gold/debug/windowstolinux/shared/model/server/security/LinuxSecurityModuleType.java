package gold.debug.windowstolinux.shared.model.server.security;

/**
 * Host mandatory-access-control implementation observed without mutation. / 在不修改状态的情况下观测到的主机强制访问控制实现。
 */
public enum LinuxSecurityModuleType {
    /**
     * AppArmor. / AppArmor 强制访问控制模块。
     */ APPARMOR,
    /**
     * SELinux. / SELinux 强制访问控制模块。
     */ SELINUX,
    /**
     * No active implementation was observed. / 未观测到活动实现。
     */ NONE,
    /**
     * The implementation could not be determined. / 无法确定实现。
     */ UNKNOWN
}
