package gold.debug.windowstolinux.mod.deployment.service.standard.deploy.input;

/** Distinguishes missing database review, reviewed absence, and one reviewed database family. / 区分尚未审阅、已审阅且为空及一种已审阅数据库族。 */
public enum DeploymentDatabaseReviewModeType {
    /** No decision has been reviewed. / 尚未审阅。 */
    UNREVIEWED,
    /** Explicit absence of database bindings. / 明确没有数据库绑定。 */
    NONE,
    /** PostgreSQL server binding. / PostgreSQL 服务器绑定。 */
    POSTGRESQL,
    /** MySQL server binding. / MySQL 服务器绑定。 */
    MYSQL,
    /** MariaDB server binding. / MariaDB 服务器绑定。 */
    MARIADB,
    /** Managed SQLite file binding. / 受管 SQLite 文件绑定。 */
    SQLITE
}
