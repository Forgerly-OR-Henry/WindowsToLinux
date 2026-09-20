package gold.debug.windowstolinux.web.main.startup;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import gold.debug.windowstolinux.web.db.runtime.WebStorageLocation;
import gold.debug.windowstolinux.web.file.workspace.WebWorkspace;
import gold.debug.windowstolinux.web.main.config.WebRuntimeProperties;
import gold.debug.windowstolinux.web.main.runtime.WebInstanceLease;
import gold.debug.windowstolinux.web.secret.masterkey.*;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ClassPathResource;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;
import java.io.IOException;
import java.nio.file.*;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(WebRuntimeProperties.class)
public class WebStorageConfiguration {
    @Bean
    public WebStorageLocation webDataLocation(WebRuntimeProperties properties) throws IOException {
        return WebStorageLocation.resolve(properties.storage().root());
    }
    @Bean(destroyMethod = "close")
    public WebInstanceLease webInstanceLease(WebStorageLocation location, WebRuntimeProperties properties,
                                            @org.springframework.beans.factory.annotation.Value("${server.address}") String address) throws IOException {
        if (!"127.0.0.1".equals(address)) throw new IOException("Phase 5 server.address must be 127.0.0.1");
        if (!new ClassPathResource("static/index.html").exists()) throw new IOException("Web frontend is missing; run the Maven build before WebMain");
        return WebInstanceLease.acquire(location.root(), properties.storage().minimumFreeBytes());
    }
    @Bean(destroyMethod = "close")
    public WebMasterKey webMasterKey(WebStorageLocation location, WebRuntimeProperties properties, WebInstanceLease lease) throws IOException {
        return new WebMasterKey(WebMasterKeyStore.loadOrCreate(location.root(), properties.secrets().directory()));
    }
    @Bean(destroyMethod = "close")
    public HikariDataSource dataSource(WebStorageLocation location, WebRuntimeProperties properties, WebMasterKey key) throws IOException {
        WebWorkspace.safeAncestors(location.database());
        if (Files.exists(location.database(), LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(location.database(), LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Web database path is not a regular file");
        var sqlite = new SQLiteConfig(); sqlite.enforceForeignKeys(true);
        sqlite.setBusyTimeout(Math.toIntExact(properties.database().busyTimeout().toMillis()));
        var source = new SQLiteDataSource(sqlite); source.setUrl(location.jdbcUrl());
        var pool = new HikariConfig(); pool.setDataSource(source); pool.setMaximumPoolSize(1); pool.setMinimumIdle(1);
        pool.setPoolName("web-sqlite"); pool.setConnectionTimeout(properties.database().connectionTimeout().toMillis());
        return new HikariDataSource(pool);
    }
}
