package gold.debug.windowstolinux.shared.config.resource;

/** Supported managed database families without backup-specific evidence. / 不包含备份专属证据的受管数据库族。 */
public enum ManagedDatabaseEngineType {
    SQLITE,
    POSTGRESQL,
    MYSQL,
    MARIADB,
    REDIS
}
