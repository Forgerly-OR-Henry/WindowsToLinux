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

/**
 * Wires controlled data locations, instance ownership and master-key storage.
 * <p>装配受控数据位置、实例归属及主密钥存储。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(WebRuntimeProperties.class)
public class WebStorageConfiguration {
    /**
     * Assembles the web storage location managed by the Spring application context.
     * <p>装配由 Spring 应用上下文管理的Web存储位置。
     *
     * @param properties properties / 属性集合
     * @return constructed or resolved web storage location / 构造或解析得到的Web存储位置
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
    @Bean
    public WebStorageLocation webDataLocation(WebRuntimeProperties properties) throws IOException {
        return WebStorageLocation.resolve(properties.storage().root());
    }
    /**
     * Assembles the web instance lease managed by the Spring application context.
     * <p>装配由 Spring 应用上下文管理的Web实例租约。
     *
     * @param location the remote URI / 远端 URI
     * @param properties properties / 属性集合
     * @param address address / 地址
     * @return constructed or resolved web instance lease / 构造或解析得到的Web实例租约
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
    @Bean(destroyMethod = "close")
    public WebInstanceLease webInstanceLease(WebStorageLocation location, WebRuntimeProperties properties,
                                            @org.springframework.beans.factory.annotation.Value("${server.address}") String address) throws IOException {
        if (!"127.0.0.1".equals(address)) throw new IOException("Phase 5 server.address must be 127.0.0.1");
        if (!new ClassPathResource("static/index.html").exists()) throw new IOException("Web frontend is missing; run the Maven build before WebMain");
        return WebInstanceLease.acquire(location.root(), properties.storage().minimumFreeBytes());
    }
    /**
     * Assembles the Web master key managed by the Spring application context.
     * <p>装配由 Spring 应用上下文管理的Web 主密钥。
     *
     * @param location the remote URI / 远端 URI
     * @param properties properties / 属性集合
     * @param lease lease / 租约
     * @return constructed or resolved Web master key / 构造或解析得到的Web 主密钥
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
    @Bean(destroyMethod = "close")
    public WebMasterKey webMasterKey(WebStorageLocation location, WebRuntimeProperties properties, WebInstanceLease lease) throws IOException {
        return new WebMasterKey(WebMasterKeyStore.loadOrCreate(location.root(), properties.secrets().directory()));
    }
    /**
     * Assembles the hikari data source managed by the Spring application context.
     * <p>装配由 Spring 应用上下文管理的Hikari数据源码。
     *
     * @param location the remote URI / 远端 URI
     * @param properties properties / 属性集合
     * @param key lookup key within the current contract / 当前契约内的查找键
     * @return constructed or resolved hikari data source / 构造或解析得到的Hikari数据源码
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
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
