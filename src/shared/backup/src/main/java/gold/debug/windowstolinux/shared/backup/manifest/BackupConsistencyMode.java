package gold.debug.windowstolinux.shared.backup.manifest;

/**
 * Evidence-bearing consistency method used for a database backup. / 数据库备份采用的带证据一致性方式。
 */
public enum BackupConsistencyMode {
    /**
     * NOT APPLICABLE classification within backup consistency mode.
     * <p>备份一致性模式中的未适用分类。
     */
    NOT_APPLICABLE,
    /**
     * SQLITE ONLINE BACKUP classification within backup consistency mode.
     * <p>备份一致性模式中的SQLITE在线备份分类。
     */
    SQLITE_ONLINE_BACKUP,
    /**
     * SQLITE WRITES STOPPED classification within backup consistency mode.
     * <p>备份一致性模式中的SQLITE写入集合已停止分类。
     */
    SQLITE_WRITES_STOPPED,
    /**
     * POSTGRESQL LOGICAL DUMP classification within backup consistency mode.
     * <p>备份一致性模式中的POSTGRESQL逻辑转储分类。
     */
    POSTGRESQL_LOGICAL_DUMP,
    /**
     * MYSQL TRANSACTION SNAPSHOT classification within backup consistency mode.
     * <p>备份一致性模式中的MYSQL事务快照分类。
     */
    MYSQL_TRANSACTION_SNAPSHOT,
    /**
     * MYSQL WRITES STOPPED classification within backup consistency mode.
     * <p>备份一致性模式中的MYSQL写入集合已停止分类。
     */
    MYSQL_WRITES_STOPPED
}
