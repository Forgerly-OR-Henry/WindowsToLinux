package gold.debug.windowstolinux.web.db.config;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import gold.debug.windowstolinux.web.db.execution.migration.WebSchemaMigrator;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.*;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;

/**
 * Assembles the scoped MyBatis persistence and shared transaction infrastructure.
 * <p>装配限定作用域的 MyBatis 持久化及共享事务基础设施。
 */
@Configuration(proxyBeanMethods = false)
@EnableTransactionManagement
@ComponentScan("gold.debug.windowstolinux.web.db")
@MapperScan(basePackages = "gold.debug.windowstolinux.web.db.persistence.mapper", annotationClass = Mapper.class)
public class WebPersistenceConfiguration {
    /**
     * Assembles the platform transaction manager managed by the Spring application context.
     * <p>装配由 Spring 应用上下文管理的平台事务管理器。
     *
     * @param source source identity or content read by the operation / 操作读取的源身份或内容
     * @return constructed or resolved platform transaction manager / 构造或解析得到的平台事务管理器
     */
    @Bean
    public PlatformTransactionManager transactionManager(DataSource source) { return new JdbcTransactionManager(source); }

    /**
     * Assembles the web schema migrator managed by the Spring application context.
     * <p>装配由 Spring 应用上下文管理的Web结构Migrator。
     *
     * @param source source identity or content read by the operation / 操作读取的源身份或内容
     * @param transactions transactions / 事务集合
     * @return constructed or resolved web schema migrator / 构造或解析得到的Web结构Migrator
     */
    @Bean(initMethod = "migrate")
    public WebSchemaMigrator webSchemaMigrator(DataSource source, PlatformTransactionManager transactions) {
        return new WebSchemaMigrator(new JdbcTemplate(source), new TransactionTemplate(transactions));
    }

    /**
     * Assembles the sql session factory managed by the Spring application context.
     * <p>装配由 Spring 应用上下文管理的SQL会话工厂。
     *
     * @param source source identity or content read by the operation / 操作读取的源身份或内容
     * @param schema schema / 结构
     * @return constructed or resolved sql session factory / 构造或解析得到的SQL会话工厂
     * @throws Exception if the delegated operation or caller-provided interaction fails / 被委派操作或调用方提供的交互失败时
     */
    @Bean
    public SqlSessionFactory sqlSessionFactory(DataSource source, WebSchemaMigrator schema) throws Exception {
        var configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setArgNameBasedConstructorAutoMapping(true);
        configuration.getTypeHandlerRegistry().register(byte[].class, org.apache.ibatis.type.JdbcType.BLOB, new org.apache.ibatis.type.ByteArrayTypeHandler());
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(source); factory.setConfiguration(configuration);
        factory.setMapperLocations(new PathMatchingResourcePatternResolver().getResources("classpath*:mapper/*.xml"));
        return factory.getObject();
    }
}
