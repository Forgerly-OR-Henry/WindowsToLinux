package gold.debug.windowstolinux.web.service.backup;

import gold.debug.windowstolinux.shared.backup.contract.spi.*;
import gold.debug.windowstolinux.shared.backup.contract.validation.*;
import gold.debug.windowstolinux.shared.backup.crypto.BackupSecretCryptoService;
import gold.debug.windowstolinux.shared.backup.extension.adapter.LinuxDatabaseOperationPort;
import gold.debug.windowstolinux.shared.backup.extension.registry.DatabaseAdapterRegistry;
import gold.debug.windowstolinux.shared.backup.format.*;
import gold.debug.windowstolinux.shared.backup.manifest.*;
import gold.debug.windowstolinux.shared.config.resource.*;
import gold.debug.windowstolinux.shared.config.secretref.*;
import gold.debug.windowstolinux.shared.linux.protocol.backup.*;
import gold.debug.windowstolinux.shared.linux.session.DeploymentRemoteSession;
import gold.debug.windowstolinux.shared.model.lifecycle.*;
import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSpecification;
import gold.debug.windowstolinux.shared.model.server.ManagedHelperProtocolVersion;
import gold.debug.windowstolinux.web.service.contract.*;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

/** Collects exact shared-format remote artifacts and restores the original running set before returning. */
public final class WebBackupCollection {
    private final gold.debug.windowstolinux.web.file.quota.UploadQuota quota;
    private final long minimumFreeBytes;
    public WebBackupCollection(gold.debug.windowstolinux.web.file.quota.UploadQuota quota, long minimumFreeBytes) {
        this.quota = quota; this.minimumFreeBytes = minimumFreeBytes;
    }
    private final BackupArchivePolicy policy=BackupArchivePolicy.defaults();
    private long written;
    public BackupArchiveValidation create(ManagedWebGraph graph,DeploymentRemoteSession session,Path directory,Path output,
            char[] password,List<ResolvedSecretRevision> secrets,TaskInteraction interaction) throws Exception {
        return create(graph,session,directory,output,password,secrets,interaction,null);
    }
    public BackupArchiveValidation create(ManagedWebGraph graph,DeploymentRemoteSession session,Path directory,Path output,
            char[] password,List<ResolvedSecretRevision> secrets,TaskInteraction interaction,String heldMaintenance) throws Exception {
        var components=new LinkedHashMap<String,ManagedWebComponent>();graph.components().forEach(component -> components.put(component.id(),component));
        var bindings=databaseBindings(graph);
        var server=session.collectCapabilities();var linux=session.collectDeploymentCapabilities();
        if(server.managedHelperProtocolVersion()!=ManagedHelperProtocolVersion.CURRENT || !server.tarAvailable() || !server.nonInteractiveSudoAvailable()) throw new IllegalStateException("Managed backup capability is unavailable");
        var running=new LinkedHashSet<String>();
        for(var component:graph.components()) {
            var observation=session.observeDeployment(component.application(),component.runtime());
            if(!observation.ownershipVerified() || !Set.of(RuntimeState.RUNNING,RuntimeState.STOPPED,RuntimeState.INSTALLED).contains(observation.runtimeState())) throw new IllegalStateException("Application state is not authoritative");
            if(observation.runtimeState()==RuntimeState.RUNNING) running.add(component.id());
        }
        String operation=heldMaintenance==null ? "backup-"+UUID.randomUUID().toString().replace("-","") : heldMaintenance;
        var material=new LinkedHashMap<BackupMember,Path>();
        var databaseOperations=new LinuxDatabaseOperationPort(session.databaseOperations());
        DatabaseBackupArtifact databaseArtifact=null; Exception failure=null;
        var paused = new ArrayList<gold.debug.windowstolinux.shared.model.managed.ManagedApplication>();
        try {
            for (var component : graph.components()) {
                session.backupArtifacts().beginMaintenance(component.application(), operation);
                paused.add(component.application());
            }
            for(String id:graph.plan().stopOrder()) if(running.contains(id)) {
                interaction.checkCancelled();var component=components.get(id);
                var stopped=session.executeDeploymentLifecycle(component.application(),component.runtime(),LifecycleAction.STOP);
                if(!stopped.ownershipVerified() || stopped.runtimeState()!=RuntimeState.STOPPED) throw new IllegalStateException("Application did not stop for backup");
            }
            collectComponents(graph,session,directory,material,operation,interaction);
            if(!bindings.isEmpty()) {
                var owner=bindings.entrySet().iterator().next();
                databaseArtifact=DatabaseAdapterRegistry.defaults(databaseOperations).require(profile(owner.getValue()).type())
                        .backup(new DatabaseBackupRequest(owner.getKey().application().id(),profile(owner.getValue()),true,true));
                String name="database/"+owner.getValue().databaseId()+".dump";Path target=member(directory,name);
                try(var stream=bounded(target)) { databaseOperations.copyArtifact(databaseArtifact,stream); }
                var item=evidence(name,target,BackupMemberKind.DATABASE);
                if(item.size()!=databaseArtifact.byteCount() || !item.sha256().equals(databaseArtifact.sha256())) throw new IOException("Database artifact changed");
                material.put(item,target);
            }
        } catch(Exception problem) { failure=problem; }
        // Restore the original running set even when collection was cancelled or failed.
        boolean interrupted=Thread.interrupted(), recovered=true;
        try {
            recoverRunning(graph, session, running, components);
        } catch(Exception recovery) {
            recovered=false;
            interaction.completion(OperationCompletionState.REVALIDATION_REQUIRED);
            if(failure!=null) recovery.addSuppressed(failure); failure=recovery;
        }
        try {
            if(databaseArtifact!=null) databaseOperations.discardArtifact(databaseArtifact);
            if (heldMaintenance==null && recovered) for (var app : paused.reversed()) session.backupArtifacts().endMaintenance(app, operation);
            if(!session.backupArtifacts().discardBackupOperation(operation).succeeded()) throw new IOException("Remote backup cleanup failed");
        } catch(Exception cleanup) { if(failure==null) failure=cleanup;else failure.addSuppressed(cleanup); }
        finally { if(interrupted) Thread.currentThread().interrupt(); }
        if(failure!=null) throw failure;
        return packageArchive(graph,session,directory,output,password,secrets,material,databaseArtifact);
    }
    private static void recoverRunning(ManagedWebGraph graph, DeploymentRemoteSession session, Set<String> running,
            Map<String,ManagedWebComponent> components) throws Exception {
            for(String id:graph.plan().startOrder()) if(running.contains(id)) {
                var component=components.get(id);var restored=session.executeDeploymentLifecycle(component.application(),component.runtime(),LifecycleAction.START);
                if(!restored.ownershipVerified() || restored.runtimeState()!=RuntimeState.RUNNING || !session.checkDeploymentHealth(component.application(),component.runtime(),component.runtime().healthCheck()).healthy()) throw new IllegalStateException("Backup recovery requires remote verification");
            }
            if(running.contains(graph.healthOwner())) {
                var owner=components.get(graph.healthOwner());if(!session.checkDeploymentHealth(owner.application(),owner.runtime(),owner.runtime().healthCheck()).healthy()) throw new IllegalStateException("Application health did not recover");
            }
    }

    private Map<ManagedWebComponent,ManagedDatabaseBinding> databaseBindings(ManagedWebGraph graph) throws Exception {
        var bindings=new LinkedHashMap<ManagedWebComponent,ManagedDatabaseBinding>();
        for(var component:graph.components()) {
            if(!component.releaseIdentity().matches("[a-f0-9]{64}")) throw new IllegalStateException("Exact successful release is required");
            for(var binding:component.configuration().resourceBindings().databaseBindings().orElseThrow()) {
                if(!bindings.isEmpty()) throw new IllegalArgumentException("Complete backup supports at most one database");
                profile(binding);bindings.put(component,binding);
            }
        }
        return bindings;
    }
    private void collectComponents(ManagedWebGraph graph,DeploymentRemoteSession session,Path directory,Map<BackupMember,Path> material,String operation,TaskInteraction interaction) throws Exception {
        var components=new LinkedHashMap<String,ManagedWebComponent>();graph.components().forEach(component -> components.put(component.id(),component));
            for(String id:graph.plan().startOrder()) {
                interaction.progress("BACKUP_COLLECTING",WebJson.object().put("component",id));var component=components.get(id);
                collect(graph,component,session,directory,material,operation,RemoteBackupArtifactKind.RELEASE_TREE,"release","releases/"+id+".pax",BackupMemberKind.RELEASE);
                for(var file:component.configuration().resourceBindings().fileBindings()) collect(graph,component,session,directory,material,operation,RemoteBackupArtifactKind.FILE_TREE,file.bindingId(),"data/"+id+"/files/"+file.bindingId()+".pax",BackupMemberKind.PERSISTENT_CONTENT);
                if(component.runtime() instanceof DeploymentRuntimeSpecification.Container container) {
                    collect(graph,component,session,directory,material,operation,RemoteBackupArtifactKind.OCI_IMAGE,"image","runtime/"+id+".oci",BackupMemberKind.RUNTIME);
                }
            }
    }
    private BackupArchiveValidation packageArchive(ManagedWebGraph graph,DeploymentRemoteSession session,Path directory,Path output,
            char[] password,List<ResolvedSecretRevision> secrets,Map<BackupMember,Path> material,DatabaseBackupArtifact databaseArtifact) throws Exception {
        var components=new LinkedHashMap<String,ManagedWebComponent>();graph.components().forEach(component -> components.put(component.id(),component));
        var server=session.collectCapabilities();var linux=session.collectDeploymentCapabilities();
        for(var component:graph.components()) {
            write(directory,material,"config/"+component.id()+".bin",BackupMemberKind.CONFIGURATION,Base64.getDecoder().decode(component.activation()));
            write(directory,material,"runtime/"+component.id()+".bin",BackupMemberKind.RUNTIME,Base64.getDecoder().decode(component.runtimeDefinition()));
        }
        if(!secrets.isEmpty()) write(directory,material,"secrets.enc",BackupMemberKind.ENCRYPTED_SECRETS,new BackupSecretCryptoService().encryptRevisions(password,secrets));
        var entries=graph.components().stream().map(component -> new BackupComponent(component.id(),component.application().id(),component.application().ownershipManifestSha256(),
                "releases/"+component.id()+".pax","config/"+component.id()+".bin","runtime/"+component.id()+".bin",component.dependencies(),BackupComponentRuntime.from(component.runtime()),component.releaseIdentity(),component.secrets())).toList();
        var references=entries.stream().flatMap(component -> component.secretReferences().orElseThrow().stream()).distinct().sorted(Comparator.comparing(SecretReference::identifier).thenComparingLong(SecretReference::revision)).toList();
        var capabilities=new TreeSet<String>();capabilities.add("managed-helper-v"+server.managedHelperProtocolVersion());if(linux.systemdAvailable()) capabilities.add("systemd");if(linux.dockerOperational())capabilities.add("docker");if(linux.podmanOperational())capabilities.add("podman");
        long containerCount=graph.components().stream().filter(c -> c.runtime() instanceof DeploymentRuntimeSpecification.Container).count();
        var runtime=new BackupRuntime(linux.distro().name().toLowerCase(Locale.ROOT),linux.version(),containerCount==0?"systemd":containerCount==graph.components().size()?"container":"mixed","managed-helper-"+server.managedHelperProtocolVersion(),linux.architecture(),List.copyOf(capabilities));
        var inventory=new BackupInventory(entries.stream().map(BackupComponent::releaseManifestPath).toList(),entries.stream().map(BackupComponent::configurationSnapshotPath).toList(),references,
                material.keySet().stream().filter(item -> item.path().contains("/files/")).map(BackupMember::path).toList(),material.keySet().stream().filter(item -> item.path().contains("/volumes/")).map(BackupMember::path).toList(),
                databaseArtifact==null?BackupDatabase.none():databaseArtifact.database(),new BackupIdentity(graph.applicationId(),graph.components().getFirst().application().server().id(),"/opt/windowstolinux/apps/"+graph.applicationId(),BackupInventory.computeReleaseSetSha256(entries)),
                entries.stream().map(BackupComponent::serviceDefinitionPath).toList(),entries,graph.healthOwner(),BackupHealthCheck.from(components.get(graph.healthOwner()).runtime().healthCheck()),runtime,List.of("restore requires managed helper protocol "+ManagedHelperProtocolVersion.CURRENT));
        if(material.keySet().stream().mapToLong(BackupMember::size).sum()>quota.fileBytes())throw new IOException("Restore material quota exceeded");
        var manifest=BackupManifest.create(Instant.now(),graph.applicationId(),inventory,List.copyOf(material.keySet()));
        try(var stream=bounded(output)) { new BackupArchiveWriter(policy).write(manifest,material.entrySet().stream().map(item -> new BackupArchiveContent(item.getKey(),() -> Files.newInputStream(item.getValue()))).toList(),stream); }
        return new BackupArchiveValidator(policy).validate(output);
    }
    private void collect(ManagedWebGraph graph,ManagedWebComponent component,DeploymentRemoteSession session,Path directory,Map<BackupMember,Path> material,
            String operation,RemoteBackupArtifactKind kind,String resource,String path,BackupMemberKind memberKind) throws Exception {
        var remote=session.backupArtifacts().createBackupArtifact(new RemoteBackupArtifactRequest(operation,graph.applicationId(),component.id(),component.application(),component.releaseIdentity(),kind,resource,quota.fileBytes()));
        Path target=member(directory,path);try(var output=bounded(target)) { session.backupArtifacts().copyBackupArtifact(remote,output); }
        var validator=new ManagedArtifactValidator(policy);var verified=kind==RemoteBackupArtifactKind.OCI_IMAGE?validator.validateOci(target):validator.validatePax(target);
        if(verified.byteCount()!=remote.byteCount() || !verified.sha256().equals(remote.sha256())) throw new IOException("Artifact integrity failed");
        material.put(new BackupMember(path,verified.byteCount(),verified.sha256(),memberKind),target);
    }
    public static DatabaseConnectionProfile profile(ManagedDatabaseBinding binding) {
        var connection = binding.connection();
        if(connection instanceof ManagedDatabaseConnection.Sqlite sqlite) return new DatabaseConnectionProfile.Sqlite(binding.databaseId(),sqlite.location(),sqlite.fileName());
        if(!(connection instanceof ManagedDatabaseConnection.Server server) || server.engine()==ManagedDatabaseEngineType.REDIS) throw new IllegalArgumentException("Database backup requires PostgreSQL, MySQL or MariaDB");
        return new DatabaseConnectionProfile.Server(BackupDatabaseType.valueOf(server.engine().name()),server.host(),server.port(),server.database(),server.username(),server.passwordReference(),server.tlsRequired());
    }
    private static Path member(Path directory,String name) throws IOException { Path target=directory.resolve(name).normalize();if(!target.startsWith(directory))throw new IOException("Backup path escaped");Files.createDirectories(target.getParent());return target; }
    private void write(Path directory,Map<BackupMember,Path> material,String name,BackupMemberKind kind,byte[] bytes) throws Exception {
        try { Path target=member(directory,name);try(var output=bounded(target)){output.write(bytes);}material.put(evidence(name,target,kind),target); } finally { Arrays.fill(bytes,(byte)0); }
    }
    private static BackupMember evidence(String name,Path path,BackupMemberKind kind) throws Exception {
        MessageDigest digest=MessageDigest.getInstance("SHA-256");try(var input=Files.newInputStream(path)) { byte[] buffer=new byte[65536];int count;while((count=input.read(buffer))!=-1)digest.update(buffer,0,count); }
        return new BackupMember(name,Files.size(path),HexFormat.of().formatHex(digest.digest()),kind);
    }
    private OutputStream bounded(Path target) throws IOException {
        return new FilterOutputStream(Files.newOutputStream(target,StandardOpenOption.CREATE_NEW)) {
            private long count;
            @Override public void write(int value) throws IOException { check(1);out.write(value); }
            @Override public void write(byte[] bytes,int offset,int length) throws IOException { check(length);out.write(bytes,offset,length); }
            private void check(int length) throws IOException {
                count=Math.addExact(count,length);written=Math.addExact(written,length);
                if(count>quota.fileBytes() || written>quota.projectBytes())throw new IOException("Backup quota exceeded");
                if(count==length || count/(8L<<20)!=(count-length)/(8L<<20))
                    if(Files.getFileStore(target).getUsableSpace()<minimumFreeBytes)throw new IOException("Insufficient backup space");
            }
        };
    }
}
