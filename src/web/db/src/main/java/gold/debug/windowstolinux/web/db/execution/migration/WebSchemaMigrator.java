package gold.debug.windowstolinux.web.db.execution.migration;

import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Applies versioned DDL through the transaction manager and data source shared with the mappers.
 * <p>通过与映射器共享的事务管理器及数据源应用带版本 DDL。
 */
public final class WebSchemaMigrator {
    /**
     * CURRENT VERSION.
     * <p>当前版本。
     */
    public static final int CURRENT_VERSION = 1;

    /**
     * JDBC database access.
     * <p>JDBC 数据库访问。
     */
    private final JdbcTemplate jdbc;

    /**
     * Transaction.
     * <p>事务。
     */
    private final TransactionTemplate transaction;
    /**
     * Binds the supplied dependencies and state for web schema migrator.
     * <p>为Web结构Migrator绑定传入的依赖及状态。
     *
     * @param jdbc JDBC database access / JDBC 数据库访问
     * @param transaction transaction / 事务
     */
    public WebSchemaMigrator(JdbcTemplate jdbc, TransactionTemplate transaction) {
        this.jdbc = jdbc;
        this.transaction = transaction;
    }

    /**
     * Applies supported Web SQLite schema initialization or migrations transactionally and rejects unsupported versions.
     * <p>在事务内应用受支持 Web SQLite 结构初始化或迁移，并拒绝不支持的版本。
     *
     * @throws IllegalStateException if the required state or runtime facility is unavailable / 所需状态或运行设施不可用时
     */
    public void migrate() {
        transaction.executeWithoutResult(status -> {
            Integer version = jdbc.queryForObject("PRAGMA user_version", Integer.class);
            if (version == null || version < 0 || version > CURRENT_VERSION)
                throw new IllegalStateException("Unsupported Web database schema version");
            if (version == 0) {
                jdbc.execute((ConnectionCallback<Void>) connection -> {
                    ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema/001-web.sql"));
                    return null;
                });
                jdbc.execute("PRAGMA user_version=1");
            }
            if (!jdbc.queryForList("PRAGMA foreign_key_check").isEmpty())
                throw new IllegalStateException("Web database ownership integrity failed");
            if (!"ok".equals(jdbc.queryForObject("PRAGMA quick_check", String.class)))
                throw new IllegalStateException("Web database integrity failed");
        });
    }
}
