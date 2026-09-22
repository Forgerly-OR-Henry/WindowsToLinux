package gold.debug.windowstolinux.shared.model.server.security;

/**
 * Observed host firewall service state. / 观测到的主机防火墙服务状态。
 */
public enum LinuxFirewallState {
    /**
     * The observed firewall service is active. / 观测到的防火墙服务处于活动状态。
     */
    ACTIVE,
    /**
     * The observed firewall manager is installed but inactive. / 观测到的防火墙管理器已安装但未活动。
     */
    INACTIVE,
    /**
     * State could not be determined. / 无法确定状态。
     */
    UNKNOWN
}
