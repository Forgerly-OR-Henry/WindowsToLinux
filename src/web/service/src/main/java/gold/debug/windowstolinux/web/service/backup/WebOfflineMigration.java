package gold.debug.windowstolinux.web.service.backup;

import tools.jackson.databind.JsonNode;
import gold.debug.windowstolinux.shared.backup.contract.spi.*;
import gold.debug.windowstolinux.shared.backup.contract.validation.*;
import gold.debug.windowstolinux.shared.backup.execution.migration.*;
import gold.debug.windowstolinux.web.service.config.WebApplicationSecrets;
import gold.debug.windowstolinux.web.service.contract.*;
import gold.debug.windowstolinux.web.service.execution.lifecycle.WebApplicationInventory;
import gold.debug.windowstolinux.web.service.server.WebServerService;
import java.util.*;

/** Locks both hosts and delegates offline migration ordering and recovery to the shared coordinator. */
public final class WebOfflineMigration {
    private final WebBackupService backups;private final WebApplicationInventory applications;private final WebServerService servers;private final WebApplicationSecrets secrets;
    public WebOfflineMigration(WebBackupService backups,WebApplicationInventory applications,WebServerService servers,WebApplicationSecrets secrets) { this.backups=backups;this.applications=applications;this.servers=servers;this.secrets=secrets; }
    public PreparedWebOperation prepare(WebRequestContext context,JsonNode input) throws Exception {
        WebJson.fields(input,"applicationId","targetServerId");String id=WebJson.text(input,"applicationId",63),target=WebJson.text(input,"targetServerId",63);
        var application=applications.require(context,id);var graph=applications.graph(application);String source=application.attributes().get("server_id").toString();
        var sourceServer=servers.require(context,source);var targetServer=servers.require(context,target);
        if(WebServerService.lockKey(sourceServer).equals(WebServerService.lockKey(targetServer)))throw new IllegalArgumentException("Migration requires distinct hosts");
        return new PreparedWebOperation("MIGRATE",input,List.of(source,target),List.of(WebServerService.lockKey(sourceServer),WebServerService.lockKey(targetServer)),true,null,id,null,interaction -> {
            WebTaskPrompts.approve(interaction,"MIGRATION_STOP_WINDOW",WebJson.object().put("applicationId",id).put("sourceServerId",source).put("targetServerId",target));
            var initialState=applications.lifecycle(context,id,"REFRESH_STATUS").work().execute(interaction);
            if(!Set.of("RUNNING","INSTALLED").contains(initialState.path("state").asText()))throw new IllegalStateException("Migration requires all source components running before the stop window");
            char[] password=secrets.decisionSecret(context,interaction,"backup.password");
            try {
                var initial=backups.create(context,id,password,interaction);
                String admission="backup-"+UUID.randomUUID().toString().replace("-","");
                boolean daemon=graph.components().stream().anyMatch(component -> component.runtime().workload().supportsLifecycle());
                var port=new OfflineMigrationPort() {
                    private JsonNode finalArchive;private JsonNode restored;private boolean restoreEntered;
                    @Override public TargetPreflightEvidence preflightTarget(OfflineMigrationRequest request) throws BackupException {
                        return checked(BackupFailureType.MIGRATION_PREFLIGHT_FAILED,() -> {
                            var result=backups.restore(context,initial.path("id").asText(),target,password,interaction,true);
                            return new TargetPreflightEvidence(true,true,true,result.path("availableBytes").asLong(),List.of("Exact backup and live target passed shared restore preflight"));
                        });
                    }
                    @Override public SyncEvidence initialSync(OfflineMigrationRequest request) { return sync(initial,false); }
                    @Override public SourceQuiesceEvidence stopSourceWrites(OfflineMigrationRequest request) throws BackupException {
                        try {
                            pauseSource(context, source, graph, admission, interaction);
                            var result=applications.lifecycle(context,id,daemon?"STOP":"REFRESH_STATUS").work().execute(interaction);
                            if(!Set.of("STOPPED","INSTALLED").contains(result.path("state").asText()))throw new IllegalStateException("Source stop is unverified");
                            return new SourceQuiesceEvidence(true,true,admission,List.of("Every managed source component is authoritatively stopped"));
                        } catch(Exception failure) {
                            return new SourceQuiesceEvidence(false,false,admission,List.of("Source quiesce was not verified; recover daemon state and task admission before retrying"));
                        }
                    }
                    @Override public SyncEvidence finalSync(OfflineMigrationRequest request,SyncEvidence baseline,SourceQuiesceEvidence stopped) throws BackupException {
                        return checked(BackupFailureType.MIGRATION_SYNC_FAILED,() -> { finalArchive=backups.create(context,id,password,interaction,admission);return sync(finalArchive,true); });
                    }
                    @Override public TargetCandidateEvidence restoreAndVerifyTarget(OfflineMigrationRequest request,SyncEvidence finalSync) throws BackupException {
                        return checked(BackupFailureType.MIGRATION_TARGET_FAILED,() -> {
                            restoreEntered=true;restored=backups.restore(context,finalArchive.path("id").asText(),target,password,interaction,false);
                            if(!restored.path("status").asText().equals("SUCCEEDED"))throw new IllegalStateException("Target activation failed");
                            return new TargetCandidateEvidence(graph.applicationId()+"-"+finalSync.contentSha256().substring(0,16),true,true,true,List.of("Target application health passed and external traffic remains unchanged"));
                        });
                    }
                    @Override public RecoveryEvidence discardTargetCandidate(OfflineMigrationRequest request) {
                        boolean verified=!restoreEntered || restored!=null && restored.path("status").asText().equals("FAILED_EXISTING_PRESERVED");
                        return new RecoveryEvidence(verified,verified,List.of(verified?"Shared restore verified target preservation":"Target recovery requires fresh verification"));
                    }
                    @Override public RecoveryEvidence recoverSource(OfflineMigrationRequest request,SourceQuiesceEvidence stopped) throws BackupException {
                        return checked(BackupFailureType.MIGRATION_RECOVERY_FAILED,() -> {
                            return recoverSourceState(context, id, source, daemon, graph, admission, interaction);
                        });
                    }
                };
                var result=new OfflineMigrationCoordinator(port).prepare(new OfflineMigrationRequest("migration-"+UUID.randomUUID().toString().replace("-",""),graph.applicationId(),source,target,initial.path("byteCount").asLong(),true));
                interaction.completion(result.status()==OfflineMigrationStatus.READY_FOR_MANUAL_TRAFFIC_SWITCH?OperationCompletionState.SUCCEEDED:result.status()==OfflineMigrationStatus.MANUAL_RECOVERY_REQUIRED?OperationCompletionState.REVALIDATION_REQUIRED:OperationCompletionState.FAILED);
                return WebJson.object().put("status",result.status().name()).put("sourceRetained",true).put("externalTrafficChanged",false).put("sourceTaskAdmission",admission);
            } finally { Arrays.fill(password,'\0'); }
        });
    }
    private void pauseSource(WebRequestContext context, String source, ManagedWebGraph graph, String admission,
            TaskInteraction interaction) throws Exception {
                            servers.withSession(context,source,interaction,session -> {
                                var paused=new ArrayList<gold.debug.windowstolinux.shared.model.managed.ManagedApplication>();
                                try { for(var component:graph.components()) { session.backupArtifacts().beginMaintenance(component.application(),admission);paused.add(component.application()); } }
                                catch(Exception failure) { for(var app:paused.reversed()) try { session.backupArtifacts().endMaintenance(app,admission); } catch(Exception recovery) { failure.addSuppressed(recovery); } throw failure; }
                                return null;
                            });
    }

    private OfflineMigrationPort.RecoveryEvidence recoverSourceState(WebRequestContext context, String id, String source,
            boolean daemon, ManagedWebGraph graph, String admission, TaskInteraction interaction) throws Exception {
                            boolean interrupted=Thread.interrupted();
                            try {
                                var result=applications.lifecycle(context,id,daemon?"START":"REFRESH_STATUS").work().execute(recoveryInteraction(interaction));boolean verified=Set.of("RUNNING","INSTALLED").contains(result.path("state").asText());
                                if(verified)servers.withSession(context,source,recoveryInteraction(interaction),session -> { for(var component:graph.components())session.backupArtifacts().endMaintenance(component.application(),admission);return null; });
                                return new OfflineMigrationPort.RecoveryEvidence(verified,verified,List.of("Source recovery was followed by authoritative lifecycle observations"));
                            } finally { if(interrupted)Thread.currentThread().interrupt(); }
    }

    private static OfflineMigrationPort.SyncEvidence sync(JsonNode backup,boolean stopped) { return new OfflineMigrationPort.SyncEvidence(backup.path("byteCount").asLong(),backup.path("digest").asText(),true,stopped,List.of("Complete local archive passed independent shared validation")); }
    private static <T> T checked(BackupFailureType type,CheckedAction<T> action) throws BackupException {
        try { return action.run(); }catch(Exception failure) { throw BackupException.create(type,"Web migration step failed; inspect task status before retrying",failure); }
    }
    private static TaskInteraction recoveryInteraction(TaskInteraction original) {
        return new TaskInteraction() {
            @Override public void progress(String code,JsonNode details) { }
            @Override public JsonNode decide(String kind,JsonNode prompt) { throw new IllegalStateException("Recovery requires previously trusted credentials"); }
            @Override public void checkCancelled() { }
            @Override public void completion(OperationCompletionState state) { if(state!=OperationCompletionState.SUCCEEDED)original.completion(OperationCompletionState.REVALIDATION_REQUIRED); }
        };
    }
    @FunctionalInterface private interface CheckedAction<T> { T run() throws Exception; }
}
