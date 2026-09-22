package gold.debug.windowstolinux.shared.model.server.security;

/**
 * Host firewall manager observed by the read-only probe. / 只读探测观测到的主机防火墙管理器。
 */
public enum LinuxFirewallKind {
    /**
     * firewalld. / firewalld 防火墙服务。
     */
    FIREWALLD,
    /**
     * Uncomplicated Firewall. / 简易防火墙。
     */
    UFW,
    /**
     * Native nftables service or tool. / 原生 nftables 服务或工具。
     */
    NFTABLES,
    /**
     * No firewall manager was observed. / 未观测到防火墙管理器。
     */
    NONE,
    /**
     * The manager could not be determined. / 无法确定管理器。
     */
    UNKNOWN
}
