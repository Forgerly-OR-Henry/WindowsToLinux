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

@Configuration(proxyBeanMethods = false)
@EnableTransactionManagement
@ComponentScan("gold.debug.windowstolinux.web.db")
@MapperScan(basePackages = "gold.debug.windowstolinux.web.db.persistence.mapper", annotationClass = Mapper.class)
public class WebPersistenceConfiguration {
    @Bean
    public PlatformTransactionManager transactionManager(DataSource source) { return new JdbcTransactionManager(source); }

    @Bean(initMethod = "migrate")
    public WebSchemaMigrator webSchemaMigrator(DataSource source, PlatformTransactionManager transactions) {
        return new WebSchemaMigrator(new JdbcTemplate(source), new TransactionTemplate(transactions));
    }

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
