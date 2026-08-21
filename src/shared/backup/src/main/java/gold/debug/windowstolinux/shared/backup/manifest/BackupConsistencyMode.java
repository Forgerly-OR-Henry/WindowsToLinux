package gold.debug.windowstolinux.shared.backup.manifest;

/** Evidence-bearing consistency method used for a database backup. / 数据库备份采用的带证据一致性方式。 */
public enum BackupConsistencyMode {
    NOT_APPLICABLE,
    SQLITE_ONLINE_BACKUP,
    SQLITE_WRITES_STOPPED,
    POSTGRESQL_LOGICAL_DUMP,
    MYSQL_TRANSACTION_SNAPSHOT,
    MYSQL_WRITES_STOPPED
}
