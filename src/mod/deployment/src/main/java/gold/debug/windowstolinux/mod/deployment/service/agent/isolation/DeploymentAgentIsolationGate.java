package gold.debug.windowstolinux.mod.deployment.service.agent.isolation;

import static gold.debug.windowstolinux.mod.deployment.service.agent.isolation.DeploymentAgentIsolationPolicy.*;
import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;
import gold.debug.windowstolinux.appapi.linux.command.CommandResult;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;
import gold.debug.windowstolinux.mod.deployment.service.agent.tools.DeploymentAgentHelperReadiness;

/** One real new Task's accepted-plan proof; no authorization, host grant, reservation or old-task recovery. */
public final class DeploymentAgentIsolationGate {
    private final DeploymentAgentIsolationPolicy policy;
    private UUID task;
    private PlannedScope admitted;
    private String scopeSha256,helperEvidenceSha256,receipt;
    private JsonNode originalProtectedMetadata;
    public DeploymentAgentIsolationGate(DeploymentAgentIsolationPolicy policy){this.policy=Objects.requireNonNull(policy);}

    public synchronized void accept(DeploymentAgentIsolationProbe.Request request,OperationResult<CommandResult> actual,
        DeploymentAgentHelperReadiness.Observation helper,String helperEvidenceSha256){
        // A failed replacement must never leave an old proof usable for the requested scope.
        admitted=null;scopeSha256=null;receipt=null;this.helperEvidenceSha256=null;
        if(task!=null&&!task.equals(request.scope().task()))throw new SecurityException("An isolation gate cannot change its real new Task owner");
        digest(helperEvidenceSha256);
        if(request.mode()!=DeploymentAgentIsolationProbe.Mode.ADMISSION||!request.queryTask().equals(request.scope().task())
            ||!policy.document().equals(request.policy().document())||helper.status()!=DeploymentAgentHelperReadiness.Status.READY
            ||helper.reason()!=DeploymentAgentHelperReadiness.Reason.CURRENT_TASK_READY)throw new SecurityException("Current packaged helper readiness or isolation scope is unconfirmed");
        policy.validate(request.scope());
        var safe=new DeploymentAgentIsolationProbe(policy).parse(request,actual);var node=read(safe.canonicalJson());
        if(!text(node,"status").equals("READY"))throw new SecurityException("Agent isolation admission is not READY: "+text(node,"reason"));
        var metadata=node.path("protectedMetadata");
        if(originalProtectedMetadata!=null&&!originalProtectedMetadata.equals(metadata))throw new SecurityException("Protected original metadata changed since first admission");
        if(originalProtectedMetadata==null)originalProtectedMetadata=metadata.deepCopy();
        if(task==null)task=request.scope().task();
        admitted=request.scope();scopeSha256=sha(scope(admitted).toString());this.helperEvidenceSha256=helperEvidenceSha256;receipt=safe.canonicalJson();
    }
    public synchronized boolean matches(PlannedScope actual){
        if(admitted==null||actual==null)return false;
        try{policy.validate(actual);return actual.task().equals(admitted.task())&&scopeSha256.equals(sha(scope(actual).toString()));}catch(RuntimeException invalid){return false;}
    }
    public synchronized void requireCurrent(PlannedScope actual){if(!matches(actual))throw new SecurityException("Agent isolation proof is missing or accepted plan/target/source/config/layout/ports/quota changed");}

    /** A separately approved real query receipt compares the original baseline; it can never create admission. */
    public synchronized boolean protectedUnchanged(DeploymentAgentIsolationProbe.Receipt actual){
        if(admitted==null||originalProtectedMetadata==null||actual==null)return false;
        try{
            var node=read(actual.canonicalJson());UUID query=UUID.fromString(text(node,"queryTask")),action=UUID.fromString(text(node,"action"));
            var program=new DeploymentAgentIsolationProbe(policy);
            try(var expected=program.requestProtectedRecheck(admitted,query,action)){
                program.validateReceipt(expected,actual.canonicalJson());
            }
            return text(node,"mode").equals(DeploymentAgentIsolationProbe.Mode.PROTECTED_RECHECK.name())&&text(node,"status").equals("OBSERVED")
                &&node.path("stable").booleanValue()&&originalProtectedMetadata.equals(node.path("protectedMetadata"));
        }catch(RuntimeException invalid){return false;}
    }
    public synchronized Map<String,?> facts(){
        var result=new LinkedHashMap<String,Object>();result.putAll(policy.facts());result.put("admitted",admitted!=null);
        result.put("meaning","Point-in-time new-task admission only. Every actual effect still requires its own independent APP decision and fresh exact grant; original ownership, capacity and send guards remain. No permission to modify shared toolchain versions, protected tasks or old resources.");
        if(admitted!=null){result.put("acceptedScope",scope(admitted));result.put("scopeSha256",scopeSha256);result.put("helperEvidenceSha256",helperEvidenceSha256);result.put("helperEvidenceMeaning","Product-bound digest of actual reviewed readiness request/result, not a separate executed-code measurement");result.put("probeReceipt",read(receipt));result.put("originalProtectedMetadata",originalProtectedMetadata.deepCopy());}
        return Collections.unmodifiableMap(result);
    }
}
