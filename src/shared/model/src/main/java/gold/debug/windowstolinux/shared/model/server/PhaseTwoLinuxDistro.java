package gold.debug.windowstolinux.shared.model.server;

/**
 * Distribution families for which Phase Two keeps separate evidence and compatibility decisions.
 *
 * <p>二期为其保留独立证据和兼容性决策的发行版系列。
 */
public enum PhaseTwoLinuxDistro {
    /** Ubuntu LTS. / Ubuntu LTS。 */
    UBUNTU,
    /** CentOS Stream. / CentOS Stream。 */
    CENTOS_STREAM,
    /** Discontinued CentOS Linux or Stream 8. / 已停止维护的 CentOS Linux 或 Stream 8。 */
    LEGACY_CENTOS,
    /** A distribution outside the Phase Two matrix. / 二期矩阵之外的发行版。 */
    OTHER
}
