package gold.debug.windowstolinux.web.db;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import gold.debug.windowstolinux.web.db.config.WebPersistenceConfiguration;
import gold.debug.windowstolinux.web.db.persistence.repository.*;
import gold.debug.windowstolinux.web.db.runtime.WebStorageLocation;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;
import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;

/** Real SQLite/MyBatis/Spring fixture shared by Web module tests. */
public final class WebDatabaseTestContext implements AutoCloseable {
    private final AnnotationConfigApplicationContext context;
    public WebDatabaseTestContext(Path root) throws Exception {
        Files.createDirectories(root);
        var sqlite = new SQLiteConfig(); sqlite.enforceForeignKeys(true); sqlite.setBusyTimeout(5000);
        var source = new SQLiteDataSource(sqlite);
        source.setUrl("jdbc:sqlite:" + root.resolve(WebStorageLocation.DATABASE_NAME));
        var pool = new HikariConfig(); pool.setDataSource(source); pool.setMaximumPoolSize(1); pool.setMinimumIdle(1);
        var dataSource = new HikariDataSource(pool);
        context = new AnnotationConfigApplicationContext();
        context.registerBean(DataSource.class, () -> dataSource, definition -> definition.setDestroyMethodName("close"));
        context.register(WebPersistenceConfiguration.class);
        try { context.refresh(); context.getBean(WebPersistence.class).initializeInternalScope(); }
        catch (RuntimeException failure) { context.close(); dataSource.close(); throw failure; }
    }
    public DataSource source() { return context.getBean(DataSource.class); }
    public JdbcTemplate jdbc() { return new JdbcTemplate(source()); }
    public WebResourceRepository resources() { return context.getBean(WebResourceRepository.class); }
    public WebTaskRepository tasks() { return context.getBean(WebTaskRepository.class); }
    public WebSecretRepository secrets() { return context.getBean(WebSecretRepository.class); }
    @Override public void close() { context.close(); }
}
