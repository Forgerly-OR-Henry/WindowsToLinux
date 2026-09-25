package gold.debug.windowstolinux.web.main.startup;

import gold.debug.windowstolinux.shared.linux.connection.DeploymentLinuxGateway;
import gold.debug.windowstolinux.shared.linux.sshd.connection.SshdLinuxGateway;
import gold.debug.windowstolinux.web.api.config.WebHttpPolicy;
import gold.debug.windowstolinux.web.db.WebPersistence;
import gold.debug.windowstolinux.web.db.persistence.repository.*;
import gold.debug.windowstolinux.web.db.runtime.WebStorageLocation;
import gold.debug.windowstolinux.web.file.workspace.WebWorkspace;
import gold.debug.windowstolinux.web.main.config.WebRuntimeProperties;
import gold.debug.windowstolinux.web.main.runtime.WebInstanceLease;
import gold.debug.windowstolinux.web.secret.credential.WebCredentialStore;
import gold.debug.windowstolinux.web.secret.crypto.WebSecretCipher;
import gold.debug.windowstolinux.web.secret.masterkey.WebMasterKey;
import gold.debug.windowstolinux.web.service.WebApplicationService;
import gold.debug.windowstolinux.web.service.ai.WebAiService;
import gold.debug.windowstolinux.web.service.backup.WebBackupService;
import gold.debug.windowstolinux.web.service.config.WebApplicationSecrets;
import gold.debug.windowstolinux.web.service.contract.*;
import gold.debug.windowstolinux.web.service.deployment.WebDeploymentService;
import gold.debug.windowstolinux.web.service.execution.lifecycle.WebApplicationInventory;
import gold.debug.windowstolinux.web.service.persistence.serialization.WebJsonCodec;
import gold.debug.windowstolinux.web.service.server.WebServerService;
import gold.debug.windowstolinux.web.service.source.WebSourceService;
import gold.debug.windowstolinux.web.task.scheduler.WebTaskScheduler;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.*;
import tools.jackson.databind.json.JsonMapper;

/**
 * Wires Web use cases to shared domain services and platform-owned adapters.
 * <p>将 Web 用例连接到共享领域服务及平台持有的适配器。
 */
@Configuration(proxyBeanMethods = false)
public class WebServiceConfiguration {
    /**
     * Assembles the json mapper managed by the Spring application context.
     * <p>装配由 Spring 应用上下文管理的JSON映射器。
     *
     * @return constructed or resolved json mapper / 构造或解析得到的JSON映射器
     */
    @Bean
    public JsonMapper webJsonMapper() {
        return WebJsonCodec.mapper();
    }

    /**
     * Assembles the web http policy managed by the Spring application context.
     * <p>装配由 Spring 应用上下文管理的WebHTTP策略。
     *
     * @param properties properties / 属性集合
     * @return constructed or resolved web http policy / 构造或解析得到的WebHTTP策略
     */
    @Bean
    public WebHttpPolicy webHttpPolicy(WebRuntimeProperties properties) {
        return properties.http();
    }

    /**
     * Assembles the deployment linux gateway managed by the Spring application context.
     * <p>装配由 Spring 应用上下文管理的部署Linux网关。
     *
     * @return constructed or resolved deployment linux gateway / 构造或解析得到的部署Linux网关
     */
    @Bean
    @ConditionalOnMissingBean(DeploymentLinuxGateway.class)
    public DeploymentLinuxGateway deploymentLinuxGateway() {
        return new SshdLinuxGateway(gold.debug.windowstolinux.shared.standard.deploy.build.DeploymentBuildExecutor::new,
                gold.debug.windowstolinux.shared.standard.deploy.distro.extension.registry.DistributionSetupRegistry
                        .defaults());
    }

    /**
     * Assembles the web credential store managed by the Spring application context.
     * <p>装配由 Spring 应用上下文管理的Web凭据存储。
     *
     * @param repository persistence boundary for the owned records / 所属记录的持久化边界
     * @param key lookup key within the current contract / 当前契约内的查找键
     * @return constructed or resolved web credential store / 构造或解析得到的Web凭据存储
     */
    @Bean
    public WebCredentialStore webCredentialStore(WebSecretRepository repository, WebMasterKey key) {
        return new WebCredentialStore(repository, new WebSecretCipher(key));
    }

    /**
     * Assembles the web workspace managed by the Spring application context.
     * <p>装配由 Spring 应用上下文管理的Web工作区。
     *
     * @param location the remote URI / 远端 URI
     * @param properties properties / 属性集合
     * @param lease lease / 租约
     * @return constructed or resolved web workspace / 构造或解析得到的Web工作区
     * @throws Exception if the delegated operation or caller-provided interaction fails / 被委派操作或调用方提供的交互失败时
     */
    @Bean
    public WebWorkspace sourceWorkspace(WebStorageLocation location, WebRuntimeProperties properties,
            WebInstanceLease lease) throws Exception {
        return new WebWorkspace(location.files(), properties.files(), properties.storage().minimumFreeBytes());
    }

    /**
     * Assembles the web workspace managed by the Spring application context.
     * <p>装配由 Spring 应用上下文管理的Web工作区。
     *
     * @param location the remote URI / 远端 URI
     * @param properties properties / 属性集合
     * @param lease lease / 租约
     * @return constructed or resolved web workspace / 构造或解析得到的Web工作区
     * @throws Exception if the delegated operation or caller-provided interaction fails / 被委派操作或调用方提供的交互失败时
     */
    @Bean
    public WebWorkspace backupWorkspace(WebStorageLocation location, WebRuntimeProperties properties,
            WebInstanceLease lease) throws Exception {
        return new WebWorkspace(location.backups(), properties.backups(), properties.storage().minimumFreeBytes());
    }

    /**
     * Assembles the web server service managed by the Spring application context.
     * <p>装配由 Spring 应用上下文管理的Web服务器服务。
     *
     * @param resources reviewed file, configuration and database bindings for this component / 当前组件已审阅的文件、配置及数据库绑定
     * @param credentials credentials / 凭据
     * @param gateway factory for authenticated Linux sessions / 已认证 Linux 会话的工厂
     * @return constructed or resolved web server service / 构造或解析得到的Web服务器服务
     */
    @Bean
    public WebServerService webServerService(WebResourceRepository resources, WebCredentialStore credentials,
            DeploymentLinuxGateway gateway) {
        return new WebServerService(resources, credentials, gateway);
    }

    /**
     * Assembles the web source service managed by the Spring application context.
     * <p>装配由 Spring 应用上下文管理的Web源码服务。
     *
     * @param resources reviewed file, configuration and database bindings for this component / 当前组件已审阅的文件、配置及数据库绑定
     * @param files controlled filesystem access or reviewed file inventory / 受控文件系统访问或已审阅文件清单
     * @param properties properties / 属性集合
     * @return constructed or resolved web source service / 构造或解析得到的Web源码服务
     */
    @Bean
    public WebSourceService webSourceService(WebResourceRepository resources,
            @Qualifier("sourceWorkspace") WebWorkspace files, WebRuntimeProperties properties) {
        return new WebSourceService(resources, files, properties.maintenance().sourceRetention());
    }

    /**
     * Assembles the web application secrets managed by the Spring application context.
     * <p>装配由 Spring 应用上下文管理的Web应用秘密集合。
     *
     * @param credentials credentials / 凭据
     * @return constructed or resolved web application secrets / 构造或解析得到的Web应用秘密集合
     */
    @Bean
    public WebApplicationSecrets webApplicationSecrets(WebCredentialStore credentials) {
        return new WebApplicationSecrets(credentials);
    }

    /**
     * Assembles the web application inventory managed by the Spring application context.
     * <p>装配由 Spring 应用上下文管理的Web应用清单。
     *
     * @param resources reviewed file, configuration and database bindings for this component / 当前组件已审阅的文件、配置及数据库绑定
     * @param tasks tasks / 任务集合
     * @param servers server-profile and authenticated-session service / 服务器资料及已认证会话服务
     * @param properties properties / 属性集合
     * @return constructed or resolved web application inventory / 构造或解析得到的Web应用清单
     */
    @Bean
    public WebApplicationInventory webApplicationInventory(WebResourceRepository resources, WebTaskRepository tasks,
            WebServerService servers, WebRuntimeProperties properties) {
        return new WebApplicationInventory(resources, tasks, servers, properties.maintenance().scanValidity());
    }

    /**
     * Assembles the web ai service managed by the Spring application context.
     * <p>装配由 Spring 应用上下文管理的WebAI服务。
     *
     * @param resources reviewed file, configuration and database bindings for this component / 当前组件已审阅的文件、配置及数据库绑定
     * @param credentials credentials / 凭据
     * @return constructed or resolved web ai service / 构造或解析得到的WebAI服务
     */
    @Bean
    public WebAiService webAiService(WebResourceRepository resources, WebCredentialStore credentials) {
        return new WebAiService(resources, credentials);
    }

    /**
     * Assembles the web deployment service managed by the Spring application context.
     * <p>装配由 Spring 应用上下文管理的Web部署服务。
     *
     * @param servers server-profile and authenticated-session service / 服务器资料及已认证会话服务
     * @param sources sources / 源码集合
     * @param applications applications / 应用集合
     * @param secrets credential references or scoped secret-access service / 凭据引用或限定作用域的秘密访问服务
     * @param ai the supplied web ai service / 所提供的WebAI服务
     * @return constructed or resolved web deployment service / 构造或解析得到的Web部署服务
     */
    @Bean
    public WebDeploymentService webDeploymentService(WebServerService servers, WebSourceService sources,
            WebApplicationInventory applications, WebApplicationSecrets secrets, WebAiService ai) {
        return new WebDeploymentService(servers, sources, applications, secrets, ai);
    }

    /**
     * Assembles the web backup service managed by the Spring application context.
     * <p>装配由 Spring 应用上下文管理的Web备份服务。
     *
     * @param resources reviewed file, configuration and database bindings for this component / 当前组件已审阅的文件、配置及数据库绑定
     * @param files controlled filesystem access or reviewed file inventory / 受控文件系统访问或已审阅文件清单
     * @param servers server-profile and authenticated-session service / 服务器资料及已认证会话服务
     * @param applications applications / 应用集合
     * @param secrets credential references or scoped secret-access service / 凭据引用或限定作用域的秘密访问服务
     * @return constructed or resolved web backup service / 构造或解析得到的Web备份服务
     */
    @Bean
    public WebBackupService webBackupService(WebResourceRepository resources,
            @Qualifier("backupWorkspace") WebWorkspace files, WebServerService servers,
            WebApplicationInventory applications, WebApplicationSecrets secrets) {
        return new WebBackupService(resources, files, servers, applications, secrets);
    }

    /**
     * Assembles the web application service managed by the Spring application context.
     * <p>装配由 Spring 应用上下文管理的Web应用服务。
     *
     * @param servers server-profile and authenticated-session service / 服务器资料及已认证会话服务
     * @param sources sources / 源码集合
     * @param resources reviewed file, configuration and database bindings for this component / 当前组件已审阅的文件、配置及数据库绑定
     * @param applications applications / 应用集合
     * @param deployment deployment / 部署
     * @param ai the supplied web ai service / 所提供的WebAI服务
     * @param secrets credential references or scoped secret-access service / 凭据引用或限定作用域的秘密访问服务
     * @param backups backups / 备份集合
     * @return constructed or resolved web application service / 构造或解析得到的Web应用服务
     */
    @Bean
    public WebApplicationService webApplicationService(WebServerService servers, WebSourceService sources,
            WebResourceRepository resources, WebApplicationInventory applications, WebDeploymentService deployment,
            WebAiService ai, WebApplicationSecrets secrets, WebBackupService backups) {
        return new WebApplicationService(servers, sources, resources, applications, deployment, ai, secrets, backups);
    }

    /**
     * Assembles the web request context managed by the Spring application context.
     * <p>装配由 Spring 应用上下文管理的Web请求上下文。
     *
     * @param database reviewed database identity or database operation boundary / 已审阅数据库身份或数据库操作边界
     * @param credentials credentials / 凭据
     * @param lease lease / 租约
     * @param sources sources / 源码集合
     * @param backups backups / 备份集合
     * @return constructed or resolved web request context / 构造或解析得到的Web请求上下文
     * @throws Exception if the delegated operation or caller-provided interaction fails / 被委派操作或调用方提供的交互失败时
     */
    @Bean
    public WebRequestContext webRequestContext(WebPersistence database, WebCredentialStore credentials,
            WebInstanceLease lease, WebSourceService sources, WebBackupService backups) throws Exception {
        database.initializeInternalScope();
        credentials.verifyMasterKey(WebPersistence.INTERNAL_SCOPE, !lease.databaseExisted());
        var context = new WebRequestContext(WebPersistence.INTERNAL_SCOPE.workspaceId(),
                WebPersistence.INTERNAL_SCOPE.userId());
        sources.recoverTemporary(context);
        backups.recoverTemporary(context);
        return context;
    }

    /**
     * Assembles the web task scheduler managed by the Spring application context.
     * <p>装配由 Spring 应用上下文管理的Web任务Scheduler。
     *
     * @param repository persistence boundary for the owned records / 所属记录的持久化边界
     * @param properties properties / 属性集合
     * @param context facts and dependencies scoped to the current operation / 限定于当前操作的事实及依赖
     * @return constructed or resolved web task scheduler / 构造或解析得到的Web任务Scheduler
     */
    @Bean(destroyMethod = "close")
    public WebTaskScheduler webTaskScheduler(WebTaskRepository repository, WebRuntimeProperties properties,
            WebRequestContext context) {
        return new WebTaskScheduler(repository, properties.tasks());
    }
}
