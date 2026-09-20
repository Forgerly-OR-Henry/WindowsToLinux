package gold.debug.windowstolinux.web.service.backup;

import tools.jackson.databind.JsonNode;
import gold.debug.windowstolinux.shared.backup.extension.adapter.*;
import gold.debug.windowstolinux.shared.backup.extension.registry.DatabaseAdapterRegistry;
import gold.debug.windowstolinux.shared.backup.restore.*;
import gold.debug.windowstolinux.shared.config.revision.DeploymentInputManifest;
import gold.debug.windowstolinux.shared.deploy.execution.transaction.*;
import gold.debug.windowstolinux.shared.model.managed.ManagedApplication;
import gold.debug.windowstolinux.web.service.config.WebApplicationSecrets;
import gold.debug.windowstolinux.web.service.contract.*;
import gold.debug.windowstolinux.web.service.execution.lifecycle.WebApplicationInventory;
import gold.debug.windowstolinux.web.service.server.WebServerService;
import java.nio.file.*;
import java.util.*;

/** Target preflight, isolated shared restore, then exact local control-plane adoption. */
public final class WebBackupRestore {
    private final WebServerService servers;private final WebApplicationInventory applications;private final WebApplicationSecrets secrets;
    public WebBackupRestore(WebServerService servers,WebApplicationInventory applications,WebApplicationSecrets secrets) { this.servers=servers;this.applications=applications;this.secrets=secrets; }
    public JsonNode execute(WebRequestContext context,String serverId,WebRestoreMaterial material,TaskInteraction interaction,boolean preflightOnly) throws Exception {
        return servers.withSession(context,serverId,interaction,session -> {
            var manifest=material.validation().manifest();var targetIdentity=servers.identity(context,serverId);
            var current=applications.managed(context,serverId,manifest.applicationId());
            boolean owned=current.isPresent() && applications.graph(current.orElseThrow()).components().size()==manifest.inventory().components().size()
                    && applications.graph(current.orElseThrow()).components().stream().allMatch(value -> manifest.inventory().components().stream().anyMatch(component -> component.componentId().equals(value.id())
                    && component.managedApplicationId().equals(value.application().id()) && component.ownershipManifestSha256().equals(value.application().ownershipManifestSha256()) && value.application().server().equals(targetIdentity)));
            if(current.isPresent() && !owned) throw new IllegalStateException("Target ownership differs from backup");
            var evidence=session.restoreActivation().inspectRestoreActivation(manifest.applicationId(),material.validation().verifiedBytes());
            var target=new RestoreTargetEvaluator().evaluate(manifest,material.database().isPresent(),serverId,session.collectCapabilities(),session.collectDeploymentCapabilities(),evidence,owned);
            var plan=new BackupRestorePlan(material.validation(),material.candidate(),material.candidate().root().getParent(),material.candidateId(),RestoreMaterialKind.BINARY_RELEASE,target,material.database());
            new BackupRestorePreflight().verify(plan);
            if(preflightOnly) return WebJson.object().put("status","PREFLIGHT_PASSED").put("availableBytes",target.availableBytes());
            var documents=material.configurations();var components=new ArrayList<ManagedWebComponent>();
            for(var component:manifest.inventory().components()) {
                var document=documents.get(component.componentId());
                components.add(new ManagedWebComponent(component.componentId(),ManagedApplication.forManaged(component.managedApplicationId(),targetIdentity,component.ownershipManifestSha256()),
                        Map.of("type",component.runtime().projectType().name()),Base64.getEncoder().encodeToString(new gold.debug.windowstolinux.shared.backup.format.BackupConfigurationCodec().writeActivation(document)),
                        component.dependsOn(),component.releaseSha256().orElseThrow(),component.secretReferences().orElseThrow(),Base64.getEncoder().encodeToString(material.runtimeDocuments().get(component.componentId()))));
            }
            var graph=new ManagedWebGraph(manifest.applicationId(),manifest.inventory().applicationHealthComponentId(),components);
            // Resolve collisions before any remote secret transfer; immutable revisions are never overwritten.
            var credentialPort=secrets.databaseCredentials(context);
            for(var revision:material.secrets()) {
                var existing=credentialPort.latest(revision.reference().identifier());
                if(existing.isPresent() && existing.orElseThrow().revision()>=revision.reference().revision()) {
                    char[] stored=credentialPort.load(revision.reference()), incoming=revision.copyCharacters();
                    try { if(!Arrays.equals(stored,incoming)) throw new IllegalStateException("Backup secret revision conflicts with existing material"); }
                    finally { Arrays.fill(stored,'\0');Arrays.fill(incoming,'\0'); }
                } else credentialPort.save(revision.reference(),revision.copyCharacters());
            }
            var record=applications.stage(context,serverId,manifest.applicationId(),graph);
            try {
            Map<String,DeploymentInputManifest> staged=new LinkedHashMap<>();
            for(var component:components) staged.put(component.id(),DeploymentInputMapper.stage(session,component.application(),documents.get(component.id()).configuration(),material.secrets().stream().filter(value -> component.secrets().contains(value.reference())).toList()));
            var databaseOperations=new LinuxDatabaseOperationPort(session.databaseOperations());boolean databaseStaged=false;
            BackupRestoreResult result;
            try {
                if(material.database().isPresent()) {
                    try(var input=Files.newInputStream(material.databaseFile().orElseThrow())) { databaseOperations.stageArtifact(material.database().orElseThrow().artifact(),input); }
                    databaseStaged=true;
                }
                interaction.progress("RESTORE_STARTED",WebJson.object());
                var candidates=new LinuxRestoreCandidateAdapter(session,new ManagedRestoreDeploymentPort(session.restoreActivation()),Map.copyOf(staged));
                result=new BackupRestoreCoordinator(new BackupRestorePreflight(),candidates,DatabaseAdapterRegistry.defaults(databaseOperations)).restore(plan);
            } finally {
                if(databaseStaged) try { databaseOperations.discardArtifact(material.database().orElseThrow().artifact()); }
                catch(java.io.IOException cleanup) { interaction.progress("REMOTE_CLEANUP_REQUIRED",WebJson.object()); }
            }
            if(result.status()!=BackupRestoreStatus.SUCCEEDED) interaction.completion(result.status()==BackupRestoreStatus.MANUAL_RECOVERY_REQUIRED?OperationCompletionState.REVALIDATION_REQUIRED:OperationCompletionState.FAILED);
            applications.finish(context,record.id(),result.status().name(),result.status()==BackupRestoreStatus.SUCCEEDED?graph:null,null);
            return WebJson.object().put("applicationId",record.id()).put("status",result.status().name());
            } catch(Exception failure) {
                interaction.completion(OperationCompletionState.REVALIDATION_REQUIRED);
                applications.finish(context,record.id(),"REVALIDATION_REQUIRED",null,null);throw failure;
            }
        });
    }
}
