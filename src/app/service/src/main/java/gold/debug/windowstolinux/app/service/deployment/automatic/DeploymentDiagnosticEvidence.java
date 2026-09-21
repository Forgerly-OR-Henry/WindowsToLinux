package gold.debug.windowstolinux.app.service.deployment.automatic;
import gold.debug.windowstolinux.shared.deploy.contract.result.deployment.*;
import gold.debug.windowstolinux.app.service.deployment.single.DeploymentOutcome;
import gold.debug.windowstolinux.shared.ai.redaction.AgentEvidenceText;
import java.util.*;
/** Supplies actual failed-stage evidence to the fixed advisory node and serial Agent. / 为固定辅助节点及串行 Agent 提供实际失败阶段证据。 */
final class DeploymentDiagnosticEvidence {
    /** Prevents construction. / 禁止实例化。 */
    private DeploymentDiagnosticEvidence(){}
    /** Extracts bounded failed events without source, configuration or credential payloads. / 提取有界失败事件，不包含源码、配置或凭据载荷。
     * @param result actual existing transaction result / 实际既有事务结果
     * @return status and selected diagnostic excerpts / 状态及选取的诊断片段
     */
    static Map<String,String> from(Object result){
        var evidence=new LinkedHashMap<String,String>();var events=new ArrayList<DeploymentEvent>();
        if(result instanceof DeploymentOutcome single){evidence.put("deploymentStatus",single.status().name());events.addAll(single.events());}
        else if(result instanceof MultiComponentDeploymentResult multiple){evidence.put("deploymentStatus",multiple.status().name());events.addAll(multiple.applicationEvents());multiple.componentResults().forEach(c->events.addAll(c.events()));}
        int index=0;for(var event:events)if(!event.succeeded()&&index<3){evidence.put("failedStage/"+(++index),event.step().name()+":"+AgentEvidenceText.redact(event.evidence()));}
        return Map.copyOf(evidence);
    }
}
