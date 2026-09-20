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
import gold.debug.windowstolinux.web.service.server.WebServerService;
import gold.debug.windowstolinux.web.service.source.WebSourceService;
import gold.debug.windowstolinux.web.task.scheduler.WebTaskScheduler;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.*;
import tools.jackson.databind.json.JsonMapper;

@Configuration(proxyBeanMethods = false)
public class WebServiceConfiguration {
    @Bean public JsonMapper webJsonMapper() { return WebJson.mapper(); }
    @Bean public WebHttpPolicy webHttpPolicy(WebRuntimeProperties properties) { return properties.http(); }
    @Bean @ConditionalOnMissingBean(DeploymentLinuxGateway.class)
    public DeploymentLinuxGateway deploymentLinuxGateway() { return new SshdLinuxGateway(); }
    @Bean public WebCredentialStore webCredentialStore(WebSecretRepository repository, WebMasterKey key) {
        return new WebCredentialStore(repository, new WebSecretCipher(key));
    }
    @Bean public WebWorkspace sourceWorkspace(WebStorageLocation location, WebRuntimeProperties properties, WebInstanceLease lease) throws Exception {
        return new WebWorkspace(location.files(), properties.files(), properties.storage().minimumFreeBytes());
    }
    @Bean public WebWorkspace backupWorkspace(WebStorageLocation location, WebRuntimeProperties properties, WebInstanceLease lease) throws Exception {
        return new WebWorkspace(location.backups(), properties.backups(), properties.storage().minimumFreeBytes());
    }
    @Bean public WebServerService webServerService(WebResourceRepository resources, WebCredentialStore credentials, DeploymentLinuxGateway gateway) {
        return new WebServerService(resources, credentials, gateway);
    }
    @Bean public WebSourceService webSourceService(WebResourceRepository resources, @Qualifier("sourceWorkspace") WebWorkspace files, WebRuntimeProperties properties) {
        return new WebSourceService(resources, files, properties.maintenance().sourceRetention());
    }
    @Bean public WebApplicationSecrets webApplicationSecrets(WebCredentialStore credentials) { return new WebApplicationSecrets(credentials); }
    @Bean public WebApplicationInventory webApplicationInventory(WebResourceRepository resources, WebTaskRepository tasks, WebServerService servers, WebRuntimeProperties properties) {
        return new WebApplicationInventory(resources, tasks, servers, properties.maintenance().scanValidity());
    }
    @Bean public WebAiService webAiService(WebResourceRepository resources, WebCredentialStore credentials) { return new WebAiService(resources, credentials); }
    @Bean public WebDeploymentService webDeploymentService(WebServerService servers, WebSourceService sources, WebApplicationInventory applications,
                                                           WebApplicationSecrets secrets, WebAiService ai) {
        return new WebDeploymentService(servers, sources, applications, secrets, ai);
    }
    @Bean public WebBackupService webBackupService(WebResourceRepository resources, @Qualifier("backupWorkspace") WebWorkspace files,
                                                   WebServerService servers, WebApplicationInventory applications, WebApplicationSecrets secrets) {
        return new WebBackupService(resources, files, servers, applications, secrets);
    }
    @Bean public WebApplicationService webApplicationService(WebServerService servers, WebSourceService sources, WebResourceRepository resources,
            WebApplicationInventory applications, WebDeploymentService deployment, WebAiService ai, WebApplicationSecrets secrets, WebBackupService backups) {
        return new WebApplicationService(servers, sources, resources, applications, deployment, ai, secrets, backups);
    }
    @Bean public WebRequestContext webRequestContext(WebPersistence database, WebCredentialStore credentials, WebInstanceLease lease,
                                                     WebSourceService sources, WebBackupService backups) throws Exception {
        database.initializeInternalScope(); credentials.verifyMasterKey(WebPersistence.INTERNAL_SCOPE, !lease.databaseExisted());
        var context = new WebRequestContext(WebPersistence.INTERNAL_SCOPE.workspaceId(), WebPersistence.INTERNAL_SCOPE.userId());
        sources.recoverTemporary(context); backups.recoverTemporary(context);
        return context;
    }
    @Bean(destroyMethod = "close")
    public WebTaskScheduler webTaskScheduler(WebTaskRepository repository, WebRuntimeProperties properties, WebRequestContext context) {
        return new WebTaskScheduler(repository, properties.tasks());
    }
}
