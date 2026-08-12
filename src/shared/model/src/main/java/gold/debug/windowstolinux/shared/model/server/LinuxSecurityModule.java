package gold.debug.windowstolinux.shared.model.server;

/** Host mandatory-access-control implementation observed without mutation. / 在不修改状态的情况下观测到的主机强制访问控制实现。 */
public enum LinuxSecurityModule {
    /** AppArmor. / AppArmor。 */ APPARMOR,
    /** SELinux. / SELinux。 */ SELINUX,
    /** No active implementation was observed. / 未观测到活动实现。 */ NONE,
    /** The implementation could not be determined. / 无法确定实现。 */ UNKNOWN
}
