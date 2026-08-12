package gold.debug.windowstolinux.shared.model.server;

/**
 * Distribution families for which typed deployment keeps separate evidence and compatibility decisions.
 *
 * <p>部署为其保留独立证据和兼容性决策的发行版系列。
 */
public enum LinuxDistro {
    /** Ubuntu LTS. / Ubuntu LTS。 */
    UBUNTU,
    /** Debian stable. / Debian 稳定版。 */
    DEBIAN,
    /** CentOS Stream. / CentOS Stream。 */
    CENTOS_STREAM,
    /** Rocky Linux. / Rocky Linux。 */
    ROCKY_LINUX,
    /** AlmaLinux. / AlmaLinux。 */
    ALMALINUX,
    /** Oracle Linux. / Oracle Linux。 */
    ORACLE_LINUX,
    /** Discontinued CentOS Linux or Stream 8. / 已停止维护的 CentOS Linux 或 Stream 8。 */
    LEGACY_CENTOS,
    /** A distribution outside the typed deployment matrix. / 部署矩阵之外的发行版。 */
    OTHER
}
