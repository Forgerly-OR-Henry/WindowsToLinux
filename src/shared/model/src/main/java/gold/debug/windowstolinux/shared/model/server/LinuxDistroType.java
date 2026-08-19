package gold.debug.windowstolinux.shared.model.server;

/**
 * Distribution families for which typed deployment keeps separate evidence and compatibility decisions.
 *
 * <p>部署为其保留独立证据和兼容性决策的发行版系列。
 */
public enum LinuxDistroType {
    /** Ubuntu LTS. / Ubuntu 长期支持版。 */
    UBUNTU,
    /** Debian stable. / Debian 稳定版。 */
    DEBIAN,
    /** CentOS Stream. / CentOS 滚动发行版。 */
    CENTOS_STREAM,
    /** Rocky Linux. / Rocky Linux 企业级发行版。 */
    ROCKY_LINUX,
    /** AlmaLinux. / AlmaLinux 企业级发行版。 */
    ALMALINUX,
    /** Oracle Linux. / Oracle Linux 企业级发行版。 */
    ORACLE_LINUX,
    /** Discontinued CentOS Linux or Stream 8. / 已停止维护的 CentOS Linux 或 Stream 8。 */
    LEGACY_CENTOS,
    /** A distribution outside the typed deployment matrix. / 部署矩阵之外的发行版。 */
    OTHER
}
