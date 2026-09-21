package gold.debug.windowstolinux.app.service.deployment.automatic;
import gold.debug.windowstolinux.app.service.contract.ManagedApplicationFacade;
import gold.debug.windowstolinux.app.service.server.ServerProfile;
import gold.debug.windowstolinux.shared.model.agent.*;
import gold.debug.windowstolinux.shared.model.lifecycle.LifecycleAction;
import java.util.*;

/** Registers only previously owned services in this exact prepared application scope. / 仅登记当前已准备应用范围内先前受管的服务。 */
final class AgentManagedToolCatalog {
    /** Prevents construction. / 禁止实例化。 */
    private AgentManagedToolCatalog(){}
    /** Offers verified lifecycle capabilities without raw SSH or model-supplied resource names. / 提供已验证生命周期能力，不开放原始 SSH 或模型提供的资源名。
     * @param boundary exact action coordinator / 精确动作协调器
     * @param lifecycle existing ownership-enforcing lifecycle facade / 强制归属的既有生命周期门面
     * @param server frozen target / 冻结目标
     * @param applicationIds prepared application identities / 已准备应用身份
     * @param master task unlock buffer / 任务解锁缓冲区
     * @throws java.sql.SQLException when inventory cannot be read / 无法读取清单时
     */
    static void register(AutomaticAgentBoundary boundary,ManagedApplicationFacade lifecycle,ServerProfile server,Set<String> applicationIds,char[] master)throws java.sql.SQLException{
        for(var saved:lifecycle.listManagedApplicationSummaries()){
            var app=saved.application();
            if(!applicationIds.contains(app.id())||!app.server().id().equals(server.id())||!app.server().host().equals(server.host())||app.server().sshPort()!=server.sshPort())continue;
            for(var type:List.of(AgentToolType.SERVICE_STATUS,AgentToolType.SERVICE_LOGS,AgentToolType.RESTART_SERVICE)){
                var action=type==AgentToolType.RESTART_SERVICE?LifecycleAction.RESTART:LifecycleAction.REFRESH_STATUS;
                boundary.register(type+":"+app.id(),type,Map.of("application",app.id(),"service",app.systemdUnit(),"releaseRoot",app.releaseRoot(),
                    "ownership",app.ownershipManifestSha256(),"currentRelease",saved.currentReleaseSha256().orElse("none"),"effect",type==AgentToolType.SERVICE_LOGS?"Read bounded managed runtime diagnostic evidence, including available state and exit-code details; no arbitrary log path":type.name()),
                    ()->{try{return lifecycle.listManagedApplicationSummaries().stream().anyMatch(now->now.application().equals(app)&&now.currentReleaseSha256().equals(saved.currentReleaseSha256()));}
                        catch(java.sql.SQLException failure){return false;}},
                    ()->{var result=lifecycle.executePersistedLifecycleWithStoredPassword(app.id(),action,master.clone());
                        if(result.observation().isEmpty())return new AgentObservation(false,false,Map.of("service",app.systemdUnit(),"result","no-verified-observation"));
                        var observed=result.observation().orElseThrow();
                        return new AgentObservation(true,result.accepted()&&observed.ownershipVerified(),Map.of("service",app.systemdUnit(),
                            "state",observed.runtimeState().name(),"autostart",observed.autostartState().name(),"ownershipVerified",Boolean.toString(observed.ownershipVerified()),"diagnostics",type==AgentToolType.SERVICE_LOGS?gold.debug.windowstolinux.shared.ai.redaction.AgentEvidenceText.redact(observed.evidence()):"not-requested"));});
            }
        }
    }
}
