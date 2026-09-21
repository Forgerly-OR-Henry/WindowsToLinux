package gold.debug.windowstolinux.shared.backup.manifest;

/**
 * Supported database families recorded in one backup. / 单个备份记录的受支持数据库族。
 */
public enum BackupDatabaseType {
    /**
     * NONE classification within backup database type.
     * <p>备份数据库类型中的无分类。
     */
    NONE,
    /**
     * SQLITE classification within backup database type.
     * <p>备份数据库类型中的SQLITE分类。
     */
    SQLITE,
    /**
     * POSTGRESQL classification within backup database type.
     * <p>备份数据库类型中的POSTGRESQL分类。
     */
    POSTGRESQL,
    /**
     * MYSQL classification within backup database type.
     * <p>备份数据库类型中的MYSQL分类。
     */
    MYSQL,
    /**
     * MARIADB classification within backup database type.
     * <p>备份数据库类型中的MARIADB分类。
     */
    MARIADB
}
