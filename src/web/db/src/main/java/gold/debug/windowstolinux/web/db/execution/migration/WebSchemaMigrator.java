package gold.debug.windowstolinux.web.db.execution.migration;

import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.support.TransactionTemplate;

/** Versioned DDL uses the same transaction manager and data source as the Mappers. */
public final class WebSchemaMigrator {
    public static final int CURRENT_VERSION = 1;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    public WebSchemaMigrator(JdbcTemplate jdbc, TransactionTemplate transaction) { this.jdbc = jdbc; this.transaction = transaction; }

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
