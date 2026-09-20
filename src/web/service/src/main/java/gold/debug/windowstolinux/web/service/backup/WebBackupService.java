package gold.debug.windowstolinux.web.service.backup;

import tools.jackson.databind.JsonNode;
import gold.debug.windowstolinux.shared.backup.contract.validation.*;
import gold.debug.windowstolinux.shared.config.secretref.ResolvedSecretRevision;
import gold.debug.windowstolinux.web.db.entity.*;
import gold.debug.windowstolinux.web.db.persistence.repository.WebResourceRepository;
import gold.debug.windowstolinux.web.file.workspace.*;
import gold.debug.windowstolinux.web.service.config.WebApplicationSecrets;
import gold.debug.windowstolinux.web.service.contract.*;
import gold.debug.windowstolinux.web.service.execution.lifecycle.WebApplicationInventory;
import gold.debug.windowstolinux.web.service.server.WebServerService;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Scoped backup upload/export and durable tasks using the same portable archive as the App. */
public final class WebBackupService {
    private final WebResourceRepository repository;private final WebWorkspace files;
    private final WebServerService servers;private final WebApplicationInventory applications;private final WebApplicationSecrets secrets;
    public WebBackupService(WebResourceRepository repository,WebWorkspace files,WebServerService servers,WebApplicationInventory applications,WebApplicationSecrets secrets) {
        this.repository=repository;this.files=files;this.servers=servers;this.applications=applications;this.secrets=secrets;
    }
    public JsonNode list(WebRequestContext context) throws Exception { return WebJson.tree(repository.list(scope(context),ResourceType.BACKUP).stream().map(WebBackupService::view).toList()); }
    public void recoverTemporary(WebRequestContext context) throws Exception {
        for(var address:files.owned(context.workspaceId()))
            if(repository.find(scope(context),ResourceType.BACKUP,address.resourceId()).isEmpty())files.discard(address);
    }
    public synchronized JsonNode upload(WebRequestContext context,String name,InputStream input) throws Exception {
        if(name==null || !name.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,99}")) throw new IllegalArgumentException("Invalid backup name");
        String id=UUID.randomUUID().toString();var address=address(context,id);files.create(address);
        try {
            files.upload(address,"backup.zip",input);
            var validation=new BackupArchiveValidator(BackupArchivePolicy.defaults()).validate(files.source(address).resolve("backup.zip"));
            files.complete(address);return view(save(context,id,name,null,validation));
        } catch(Exception failure) { files.discard(address);throw failure; }
    }
    public Path download(WebRequestContext context,String id) throws Exception {
        var record=require(context,id);Path archive=files.source(address(context,id)).resolve("backup.zip");WebWorkspace.safeAncestors(archive);
        if(!Files.isRegularFile(archive,LinkOption.NOFOLLOW_LINKS) || Files.size(archive)!=((Number)record.attributes().get("byte_count")).longValue()) throw new IOException("Backup changed");
        var checked=new BackupArchiveValidator(BackupArchivePolicy.defaults()).validate(archive);
        if(!checked.archiveSha256().equals(record.attributes().get("digest"))) throw new IOException("Backup changed");return archive;
    }
    public PreparedWebOperation prepare(WebRequestContext context,String kind,JsonNode input) throws Exception {
        if(kind.equals("BACKUP_CREATE")) {
            WebJson.fields(input,"applicationId");String id=WebJson.text(input,"applicationId",63);var row=applications.require(context,id);
            applications.graph(row);String serverId=row.attributes().get("server_id").toString();
            return new PreparedWebOperation(kind,input,List.of(serverId),List.of(WebServerService.lockKey(servers.require(context,serverId))),true,null,id,null,interaction -> {
                WebTaskPrompts.approve(interaction,"BACKUP_STOP_WINDOW",WebJson.object().put("applicationId",id));
                char[] password=secrets.decisionSecret(context,interaction,"backup.password");
                try { return create(context,id,password,interaction); } finally { Arrays.fill(password,'\0'); }
            });
        }
        if(kind.equals("RESTORE") || kind.equals("RESTORE_PREFLIGHT")) {
            WebJson.fields(input,"backupId","serverId");String id=WebJson.text(input,"backupId",63),serverId=WebJson.text(input,"serverId",63);require(context,id);
            return new PreparedWebOperation(kind,input,List.of(serverId),List.of(WebServerService.lockKey(servers.require(context,serverId))),true,null,null,id,interaction -> {
                if(kind.equals("RESTORE")) WebTaskPrompts.approve(interaction,"RESTORE_TARGET",WebJson.object().put("backupId",id).put("serverId",serverId));
                char[] password=secrets.decisionSecret(context,interaction,"backup.password");
                try { return restore(context,id,serverId,password,interaction,kind.equals("RESTORE_PREFLIGHT")); } finally { Arrays.fill(password,'\0'); }
            });
        }
        if(kind.equals("MIGRATE")) return new WebOfflineMigration(this,applications,servers,secrets).prepare(context,input);
        throw new NoSuchElementException();
    }
    public synchronized JsonNode create(WebRequestContext context,String applicationId,char[] password,TaskInteraction interaction) throws Exception {
        return create(context,applicationId,password,interaction,null);
    }
    public synchronized JsonNode create(WebRequestContext context,String applicationId,char[] password,TaskInteraction interaction,String heldMaintenance) throws Exception {
        var row=applications.require(context,applicationId);var graph=applications.graph(row);String serverId=row.attributes().get("server_id").toString();
        if(Set.of("RUNNING","REVALIDATION_REQUIRED","MANUAL_RECOVERY_REQUIRED").contains(WebJson.read(row.document()).path("deploymentState").asText()))throw new IllegalStateException("Revalidate publication before backup");
        if(!servers.identity(context,serverId).equals(graph.components().getFirst().application().server())) throw new IllegalStateException("Server identity changed");
        var refs=graph.components().stream().flatMap(component -> component.secrets().stream()).distinct().toList();
        var resolved=secrets.resolve(context,refs);String id=UUID.randomUUID().toString();var address=address(context,id);
        String workId=UUID.randomUUID().toString();var workAddress=address(context,workId);
        boolean saved=false,workCreated=false,destinationCreated=false;
        try {
            Path work=files.create(workAddress);workCreated=true;files.create(address);destinationCreated=true;
            files.checkCapacity(workAddress, files.quota().projectBytes() - WebWorkspace.size(work));
            Path output=files.source(workAddress).resolve("backup.zip");Path material=Files.createDirectory(work.resolve("material"));
            var validation=servers.withSession(context,serverId,interaction,session -> new WebBackupCollection(files.quota(), files.minimumFreeBytes()).create(graph,session,material,output,password,resolved,interaction,heldMaintenance));
            try(var stream=Files.newInputStream(output)) { files.upload(address,"backup.zip",stream); }
            files.complete(address);var record=save(context,id,graph.applicationId(),applicationId,validation);saved=true;return view(record);
        } finally {
            resolved.forEach(ResolvedSecretRevision::close);
            try { if(workCreated)files.discard(workAddress); } finally { if(!saved && destinationCreated)files.discard(address); }
        }
    }
    public synchronized JsonNode restore(WebRequestContext context,String backupId,String serverId,char[] password,TaskInteraction interaction,boolean preflightOnly) throws Exception {
        Path archive=download(context,backupId);var address=address(context,UUID.randomUUID().toString());Path directory=files.create(address);
        try {
            long bytes=new BackupArchiveValidator(BackupArchivePolicy.defaults()).validate(archive).manifest().members().stream().mapToLong(member -> member.size()).sum();
            files.checkCapacity(address,bytes);
            try(var material=WebRestoreMaterial.read(archive,directory,password.clone(),files.quota().fileBytes(),files.minimumFreeBytes())) {
            return new WebBackupRestore(servers,applications,secrets).execute(context,serverId,material,interaction,preflightOnly);
            }
        } finally { files.discard(address); }
    }
    public StoredResource require(WebRequestContext context,String id) throws Exception { return repository.find(scope(context),ResourceType.BACKUP,id).orElseThrow(NoSuchElementException::new); }
    private StoredResource save(WebRequestContext context,String id,String name,String applicationId,BackupArchiveValidation validation) throws Exception {
        var fields=new LinkedHashMap<String,Object>();fields.put("application_id",applicationId);fields.put("digest",validation.archiveSha256());fields.put("byte_count",Files.size(files.source(address(context,id)).resolve("backup.zip")));
        var manifest=validation.manifest();var document=WebJson.object().put("application",manifest.applicationId()).put("schema",manifest.schemaVersion()).put("canRestore",manifest.supportsAutomaticActivation())
                .put("components",manifest.inventory().components().size()).put("database",manifest.inventory().database().type().name()).put("provenance",validation.provenanceStatus().name());
        return repository.save(scope(context),ResourceType.BACKUP,id,name,fields,WebJson.write(document),0);
    }
    private static JsonNode view(StoredResource row) { var result=WebJson.object().put("id",row.id()).put("name",row.name()).put("version",row.version()).put("createdAt",row.createdAt()).put("digest",row.attributes().get("digest").toString()).put("byteCount",((Number)row.attributes().get("byte_count")).longValue());result.set("inspection",WebJson.read(row.document()));return result; }
    private static ResourceScope scope(WebRequestContext context) { return new ResourceScope(context.workspaceId(),context.userId()); }
    private static WorkspaceAddress address(WebRequestContext context,String id) { return new WorkspaceAddress(context.workspaceId(),id); }
}
