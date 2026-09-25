package gold.debug.windowstolinux.shared.model.ecosystem.db;

/**
 * Database ecosystem families supported by native provisioning. / 原生配置支持的数据库生态类别。
 */
public enum DatabaseEngineType {
    /**
     * SQLITE classification within database engine type.
     * <p>数据库引擎类型中的SQLITE分类。
     */
    SQLITE(0, DatabaseCategoryType.SQL),
    /**
     * POSTGRESQL classification within database engine type.
     * <p>数据库引擎类型中的POSTGRESQL分类。
     */
    POSTGRESQL(5432, DatabaseCategoryType.SQL),
    /**
     * MYSQL classification within database engine type.
     * <p>数据库引擎类型中的MYSQL分类。
     */
    MYSQL(3306, DatabaseCategoryType.SQL),
    /**
     * MARIADB classification within database engine type.
     * <p>数据库引擎类型中的MARIADB分类。
     */
    MARIADB(3306, DatabaseCategoryType.SQL),
    /**
     * REDIS classification within database engine type.
     * <p>数据库引擎类型中的REDIS分类。
     */
    REDIS(6379, DatabaseCategoryType.OTHER);
    /**
     * Distinguishes database storage categories used by analysis and deployment.
     * <p>区分分析及部署使用的数据库存储类别。
     */
    public enum DatabaseCategoryType {
        /**
         * SQL classification within database category type.
         * <p>数据库类别类型中的SQL分类。
         */
        SQL,
        /**
         * DOCUMENT classification within database category type.
         * <p>数据库类别类型中的文档分类。
         */
        DOCUMENT,
        /**
         * OTHER classification within database category type.
         * <p>数据库类别类型中的其他分类。
         */
        OTHER
    }
    /**
     * Network port number in the reviewed endpoint.
     * <p>已审阅端点中的网络端口号。
     */
    private final int port;

    /**
     * Category.
     * <p>类别。
     */
    private final DatabaseCategoryType category;
    /**
     * Binds the supplied dependencies and state for database engine type.
     * <p>为数据库引擎类型绑定传入的依赖及状态。
     *
     * @param port network port number in the reviewed endpoint / 已审阅端点中的网络端口号
     * @param category category / 类别
     */
    DatabaseEngineType(int port, DatabaseCategoryType category) {
        this.port = port;
        this.category = category;
    }

    /**
     * Returns network port number in the reviewed endpoint.
     * <p>返回已审阅端点中的网络端口号。
     *
     * @return network port number in the reviewed endpoint / 已审阅端点中的网络端口号
     */
    public int defaultPort() {
        return port;
    }

    /**
     * Returns category.
     * <p>返回类别。
     *
     * @return category / 类别
     */
    public DatabaseCategoryType category() {
        return category;
    }
}
