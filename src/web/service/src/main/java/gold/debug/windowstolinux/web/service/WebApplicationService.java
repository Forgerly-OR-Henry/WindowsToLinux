package gold.debug.windowstolinux.web.service;

import tools.jackson.databind.JsonNode;
import gold.debug.windowstolinux.web.db.entity.ResourceScope;
import gold.debug.windowstolinux.web.db.persistence.repository.WebResourceRepository;
import gold.debug.windowstolinux.web.service.contract.*;
import gold.debug.windowstolinux.web.service.server.WebServerService;
import gold.debug.windowstolinux.web.service.source.WebSourceService;
import gold.debug.windowstolinux.web.service.execution.lifecycle.WebApplicationInventory;
import gold.debug.windowstolinux.web.service.deployment.WebDeploymentService;
import gold.debug.windowstolinux.web.service.ai.WebAiService;
import gold.debug.windowstolinux.web.service.config.WebApplicationSecrets;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.NoSuchElementException;

/** Web use-case boundary; controllers never access storage, credentials or SSH directly. */
public final class WebApplicationService {
    private final WebServerService servers;
    private final WebSourceService sources;
    private final WebResourceRepository repository;
    private final WebApplicationInventory applications;
    private final WebDeploymentService deployment;
    private final WebAiService ai;
    private final WebApplicationSecrets secrets;
    private final gold.debug.windowstolinux.web.service.backup.WebBackupService backups;
    public WebApplicationService(WebServerService servers, WebSourceService sources, WebResourceRepository repository,
            WebApplicationInventory applications,WebDeploymentService deployment,WebAiService ai,WebApplicationSecrets secrets,gold.debug.windowstolinux.web.service.backup.WebBackupService backups) {
        this.servers = servers; this.sources = sources; this.repository = repository;
        this.applications=applications;this.deployment=deployment;this.ai=ai;this.secrets=secrets;
        this.backups=backups;
    }
    public JsonNode listBackups(WebRequestContext context) throws Exception { return backups.list(context); }
    public JsonNode uploadBackup(WebRequestContext context,String name,InputStream input) throws Exception { return backups.upload(context,name,input); }
    public java.nio.file.Path downloadBackup(WebRequestContext context,String id) throws Exception { return backups.download(context,id); }
    public JsonNode listApplications(WebRequestContext context) throws Exception { return applications.list(context); }
    public JsonNode editApplication(WebRequestContext context,String id,JsonNode input) throws Exception { return applications.presentation(context,id,input); }
    public JsonNode saveSecret(WebRequestContext context,JsonNode input) throws Exception { return secrets.save(context,input); }
    public JsonNode listAi(WebRequestContext context) throws Exception { return ai.list(context); }
    public PreparedWebOperation saveAi(WebRequestContext context,String id,JsonNode input) throws Exception { return ai.save(context,id,input); }
    public JsonNode enableAi(WebRequestContext context,String id,JsonNode input) throws Exception { return ai.enabled(context,id,input); }
    public JsonNode reorderAi(WebRequestContext context,JsonNode input) throws Exception { return ai.reorder(context,input); }
    public void deleteAi(WebRequestContext context,String id,long version) throws Exception { ai.delete(context,id,version); }
    public JsonNode listServers(WebRequestContext context) throws Exception { return servers.list(context); }
    public JsonNode saveServer(WebRequestContext context, String id, JsonNode body) throws Exception { return servers.save(context, id, body); }
    public void deleteServer(WebRequestContext context, String id, long version) throws Exception { servers.delete(context, id, version); }
    public JsonNode listSources(WebRequestContext context) throws Exception { return sources.list(context); }
    public JsonNode beginSource(WebRequestContext context, JsonNode input) throws Exception {
        WebJson.fields(input, "name"); return sources.begin(context, WebJson.text(input, "name", 100));
    }
    public void uploadSource(WebRequestContext context, String id, String path, InputStream input) throws Exception { sources.upload(context, id, path, input); }
    public void uploadArchive(WebRequestContext context, String id, String format, InputStream input) throws Exception { sources.archive(context, id, format, input); }
    public JsonNode finishSource(WebRequestContext context, String id) throws Exception { return sources.finish(context, id); }
    public JsonNode preferences(WebRequestContext context) throws Exception { return WebJson.tree(repository.preferences(scope(context))); }
    public JsonNode savePreferences(WebRequestContext context, JsonNode body) throws Exception {
        WebJson.fields(body, "theme", "language", "navigationCollapsed");
        var values = new LinkedHashMap<String, String>();
        body.properties().forEach(entry -> {
            if (!entry.getValue().isTextual()) throw new IllegalArgumentException("Invalid preference");
            String value = entry.getValue().asText();
            boolean valid = switch (entry.getKey()) {
                case "theme" -> java.util.Set.of("light", "dark", "system").contains(value);
                case "language" -> java.util.Set.of("zh-CN", "en").contains(value);
                case "navigationCollapsed" -> java.util.Set.of("true", "false").contains(value);
                default -> false;
            };
            if (!valid) throw new IllegalArgumentException("Invalid preference"); values.put(entry.getKey(), value);
        });
        repository.savePreferences(scope(context), values); return preferences(context);
    }
    public PreparedWebOperation prepare(WebRequestContext context, String kind, JsonNode input) throws Exception {
        var operation = switch (kind) {
            case "SERVER_PROBE" -> { WebJson.fields(input, "serverId"); yield servers.probe(context, WebJson.text(input, "serverId", 63)); }
            case "GIT_SNAPSHOT" -> sources.git(context, input);
            case "ANALYZE" -> { WebJson.fields(input, "sourceId"); yield sources.analyze(context, WebJson.text(input, "sourceId", 63)); }
            case "DEPLOY" -> deployment.prepare(context,input);
            case "LIFECYCLE" -> { WebJson.fields(input,"applicationId","action");yield applications.lifecycle(context,WebJson.text(input,"applicationId",63),WebJson.text(input,"action",40)); }
            case "APPLICATION_SCAN" -> { WebJson.fields(input,"serverId");yield applications.scan(context,WebJson.text(input,"serverId",63)); }
            case "APPLICATION_ADOPT" -> { WebJson.fields(input,"scanTaskId","candidateKey");yield applications.adopt(context,WebJson.text(input,"scanTaskId",63),WebJson.text(input,"candidateKey",512)); }
            case "BACKUP_CREATE", "RESTORE", "RESTORE_PREFLIGHT", "MIGRATE" -> backups.prepare(context,kind,input);
            default -> throw new NoSuchElementException("Operation not found");
        };
        return pinTargets(context,operation);
    }
    private PreparedWebOperation pinTargets(WebRequestContext context,PreparedWebOperation operation) throws Exception {
        var endpoints=new java.util.LinkedHashMap<String,gold.debug.windowstolinux.shared.linux.connection.SshEndpoint>();
        for(String id:operation.serverIds()) endpoints.put(id,WebServerService.endpoint(servers.require(context,id)));
        var currentLocks=endpoints.keySet().stream().map(id->endpoints.get(id).host().toLowerCase(java.util.Locale.ROOT)+":"+endpoints.get(id).port()).toList();
        if(operation.mutating() && !new java.util.HashSet<>(currentLocks).equals(new java.util.HashSet<>(operation.lockKeys())))throw new IllegalStateException("Server endpoint changed while preparing the task");
        return new PreparedWebOperation(operation.kind(),operation.request(),operation.serverIds(),operation.lockKeys(),operation.mutating(),operation.sourceId(),operation.applicationId(),operation.backupId(),interaction->{
            for(var entry:endpoints.entrySet())if(!entry.getValue().equals(WebServerService.endpoint(servers.require(context,entry.getKey()))))throw new IllegalStateException("Server endpoint changed before task execution");
            return operation.work().execute(interaction);
        });
    }
    private static ResourceScope scope(WebRequestContext context) { return new ResourceScope(context.workspaceId(), context.userId()); }
}
