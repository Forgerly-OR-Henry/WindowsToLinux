package gold.debug.windowstolinux.shared.model.deployment;

/**
 * Explicit database review choices, including the unreviewed state. / 包含未审阅状态的显式数据库范围选择。
 */
public enum DatabaseReviewMode {
    /**
     * No scope has been reviewed. / 尚未审阅范围。
     */
    UNREVIEWED,
    /**
     * Explicitly no database. / 明确无数据库。
     */
    NONE,
    /**
     * PostgreSQL binding. / PostgreSQL 绑定。
     */
    POSTGRESQL,
    /**
     * MySQL binding. / MySQL 绑定。
     */
    MYSQL,
    /**
     * MariaDB binding. / MariaDB 绑定。
     */
    MARIADB,
    /**
     * Embedded SQLite file binding. / 嵌入式 SQLite 文件绑定。
     */
    SQLITE
}
