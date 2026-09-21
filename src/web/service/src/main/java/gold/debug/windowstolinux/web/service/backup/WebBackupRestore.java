package gold.debug.windowstolinux.web.service.backup;

import gold.debug.windowstolinux.web.service.persistence.serialization.WebJsonCodec;

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

/**
 * Composes target preflight, isolated shared restore and exact local control-plane adoption.
 * <p>组合目标预检、隔离共享恢复及精确本地控制平面接管。
 */
public final class WebBackupRestore {
    /**
     * Bound web server service collaborator for server-profile and authenticated-session service.
     * <p>处理服务器资料及已认证会话服务的Web服务器服务协作对象。
     */
    private final WebServerService servers;
    /**
     * Applications.
     * <p>应用集合。
     */
    private final WebApplicationInventory applications;
    /**
     * Credential references or scoped secret-access service.
     * <p>凭据引用或限定作用域的秘密访问服务。
     */
    private final WebApplicationSecrets secrets;
    /**
     * Binds the supplied dependencies and state for web backup restore.
     * <p>为Web备份恢复绑定传入的依赖及状态。
     *
     * @param servers server-profile and authenticated-session service / 服务器资料及已认证会话服务
     * @param applications applications / 应用集合
     * @param secrets credential references or scoped secret-access service / 凭据引用或限定作用域的秘密访问服务
     */
    public WebBackupRestore(WebServerService servers,WebApplicationInventory applications,WebApplicationSecrets secrets) { this.servers=servers;this.applications=applications;this.secrets=secrets; }
    /**
     * Runs target preflight and, when requested, isolated restore with shared activation and exact local inventory adoption.
     * <p>运行目标预检，并在请求时执行共享激活及精确本地清单接管的隔离恢复。
     *
     * @param context facts and dependencies scoped to the current operation / 限定于当前操作的事实及依赖
     * @param serverId persisted server identifier / 持久化服务器标识
     * @param material material / 素材
     * @param interaction caller-owned progress, confirmation and input callbacks / 调用方持有的进度、确认及输入回调
     * @param preflightOnly preflight only / 预检仅
     * @return constructed or resolved json node / 构造或解析得到的JSON节点
     * @throws Exception if the delegated operation or caller-provided interaction fails / 被委派操作或调用方提供的交互失败时
     * @throws IllegalStateException if the required state or runtime facility is unavailable / 所需状态或运行设施不可用时
     */
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
            if(preflightOnly) return WebJsonCodec.object().put("status","PREFLIGHT_PASSED").put("availableBytes",target.availableBytes());
            var documents=material.configurations();var components=new ArrayList<ManagedWebComponent>();
            for(var component:manifest.inventory().components()) {
                var document=documents.get(component.componentId());
                components.add(new ManagedWebComponent(component.componentId(),ManagedApplication.forManaged(component.managedApplicationId(),targetIdentity,component.ownershipManifestSha256()),
                        Map.of("type",component.runtime().projectType().name()),Base64.getEncoder().encodeToString(new gold.debug.windowstolinux.shared.backup.format.BackupConfigurationCodec().writeActivation(document)),
                        component.dependsOn(),component.releaseSha256().orElseThrow(),component.secretReferences().orElseThrow(),Base64.getEncoder().encodeToString(material.runtimeDocuments().get(component.componentId()))));
            }
            var graph=new ManagedWebGraph(manifest.applicationId(),manifest.inventory().applicationHealthComponentId(),components);
            // Resolve collisions before any remote secret transfer; immutable revisions are never overwritten. / 在任何远端秘密传输前解决冲突；绝不覆盖不可变修订。
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
                interaction.progress("RESTORE_STARTED",WebJsonCodec.object());
                var candidates=new LinuxRestoreCandidateAdapter(session,new ManagedRestoreDeploymentPort(session.restoreActivation()),Map.copyOf(staged));
                result=new BackupRestoreCoordinator(new BackupRestorePreflight(),candidates,DatabaseAdapterRegistry.defaults(databaseOperations)).restore(plan);
            } finally {
                if(databaseStaged) try { databaseOperations.discardArtifact(material.database().orElseThrow().artifact()); }
                catch(java.io.IOException cleanup) { interaction.progress("REMOTE_CLEANUP_REQUIRED",WebJsonCodec.object()); }
            }
            if(result.status()!=BackupRestoreStatus.SUCCEEDED) interaction.completion(result.status()==BackupRestoreStatus.MANUAL_RECOVERY_REQUIRED?OperationCompletionState.REVALIDATION_REQUIRED:OperationCompletionState.FAILED);
            applications.finish(context,record.id(),result.status().name(),result.status()==BackupRestoreStatus.SUCCEEDED?graph:null,null);
            return WebJsonCodec.object().put("applicationId",record.id()).put("status",result.status().name());
            } catch(Exception failure) {
                interaction.completion(OperationCompletionState.REVALIDATION_REQUIRED);
                applications.finish(context,record.id(),"REVALIDATION_REQUIRED",null,null);throw failure;
            }
        });
    }
}
