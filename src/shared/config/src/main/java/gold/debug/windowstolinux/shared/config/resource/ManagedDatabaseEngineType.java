package gold.debug.windowstolinux.shared.config.resource;

/**
 * Supported managed database families without backup-specific evidence. / 不包含备份专属证据的受管数据库族。
 */
public enum ManagedDatabaseEngineType {
    /**
     * SQLITE classification within managed database engine type.
     * <p>受管数据库引擎类型中的SQLITE分类。
     */
    SQLITE,
    /**
     * POSTGRESQL classification within managed database engine type.
     * <p>受管数据库引擎类型中的POSTGRESQL分类。
     */
    POSTGRESQL,
    /**
     * MYSQL classification within managed database engine type.
     * <p>受管数据库引擎类型中的MYSQL分类。
     */
    MYSQL,
    /**
     * MARIADB classification within managed database engine type.
     * <p>受管数据库引擎类型中的MARIADB分类。
     */
    MARIADB,
    /**
     * REDIS classification within managed database engine type.
     * <p>受管数据库引擎类型中的REDIS分类。
     */
    REDIS
}
