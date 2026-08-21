package gold.debug.windowstolinux.shared.backup.manifest;

/** Supported database families recorded in one backup. / 单个备份记录的受支持数据库族。 */
public enum BackupDatabaseType {
    NONE,
    SQLITE,
    POSTGRESQL,
    MYSQL,
    MARIADB
}
