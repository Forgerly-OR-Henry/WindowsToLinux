package gold.debug.windowstolinux.mod.deployment.service.agent.recovery;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import gold.debug.windowstolinux.appapi.linux.command.CommandRequest;
import gold.debug.windowstolinux.appapi.linux.command.CommandResult;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.action.ActionTarget;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout;
import gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayoutTemplate;
import gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentManagedHelperBundle;

/** A separately reviewed correction action with a complete fixed source. Historical sources and results stay immutable. */
public final class DeploymentAgentPublicationCorrectionProgram {
    public static final String OPERATION="deployment.agent.publication-correction.v1";
    public static final String FORMAT="WTL_AGENT_PUBLICATION_CORRECTION_1";
    private static final String ROOT="/gold/debug/windowstolinux/mod/deployment/";
    public static final String FRAME="PUBLICATION_CORRECTION=";
    private static final Duration TIMEOUT=Duration.ofSeconds(180);
    private static final long OUTPUT_LIMIT=16384;
    private static final Set<String> CHECKS=Set.of("ENVIRONMENT","ARGUMENTS","ROOT_IDENTITY","LOCKS","JOB","CANDIDATE","FINGERPRINTS","BUILD","SNAPSHOTS","MAINTENANCE","CURRENT","NATIVE_CONTRACT","FULL_PARAMETER_VALIDATION","MANAGED_DATA_LINKS","UNIT_RENDER_MATCH","ROLLBACK","POSTCONDITIONS","COMPLETE","INTERNAL","LIMIT");
    private static final ObjectMapper JSON=com.fasterxml.jackson.databind.json.JsonMapper.builder().enable(com.fasterxml.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION).enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
    private final DeploymentRemoteLayout layout;
    private final String source;
    public record PriorFollowup(UUID action,long requestEvent,long endEvent,String requestDetailSha256,String receiptSha256,String sourceVersion){
        public PriorFollowup{Objects.requireNonNull(action);if(requestEvent<=0||endEvent<=requestEvent)throw invalid();digest(requestDetailSha256);digest(receiptSha256);digest(sourceVersion);}}
    public record Binding(DeploymentAgentPublicationContractDiagnosticFollowupProgram.Binding followup,PriorFollowup prior,long observationEnd,String observationSha256){
        public Binding{Objects.requireNonNull(followup);Objects.requireNonNull(prior);digest(observationSha256);
            var d=followup.diagnostic();var c=d.continuation();
            if(prior.requestEvent()<=followup.observationEnd()||observationEnd<=prior.endEvent()
                ||Set.of(c.original().pendingAction(),c.preflight().action(),d.prior().action(),followup.prior().action()).contains(prior.action()))throw invalid();}}
    public record Request(CommandRequest command,Set<ActionTarget> resourceTargets,Binding binding,UUID action)implements AutoCloseable{
        public Request{Objects.requireNonNull(command);resourceTargets=Set.copyOf(resourceTargets);Objects.requireNonNull(binding);Objects.requireNonNull(action);}
        @Override public void close(){command.close();}}
    public record Receipt(String canonicalJson){public Receipt{Objects.requireNonNull(canonicalJson);}}
    public DeploymentAgentPublicationCorrectionProgram(DeploymentRemoteLayout layout){
        this.layout=Objects.requireNonNull(layout);
        for(String root:List.of(layout.paths().helperDirectory(),layout.paths().applicationsRoot(),layout.paths().stateRoot(),layout.paths().configurationRoot(),layout.paths().dataRoot()))if(!root.matches("/(?:[A-Za-z0-9._-]+/)*[A-Za-z0-9._-]+")||root.contains(".."))throw invalid();
        String original=DeploymentManagedHelperBundle.renderPublicationRecovery(layout);
        String suffix="\n"+DeploymentRemoteLayoutTemplate.render(resource("service/agent/recovery/publication-recovery-guard.sh"),layout);
        int cut=original.length()-suffix.length();
        if(cut<0||!original.endsWith(suffix)||original.indexOf(suffix)!=cut)throw invalid();
        String base=original.substring(0,cut);
        for(String name:List.of("20-native-targets.sh","21-native-operations.sh"))base+=DeploymentRemoteLayoutTemplate.render(resource("linux/helper/database/"+name),layout);
        String bash=base+"\n"+DeploymentRemoteLayoutTemplate.render(resource("service/agent/recovery/publication-continuation-guard.sh"),layout);
        String template=resource("service/agent/recovery/publication-correction.py"),marker="# @WTL_CORRECTION_BASH@";
        if(template.indexOf(marker)<0||template.indexOf(marker)!=template.lastIndexOf(marker))throw invalid();
        source=template.replace(marker,"BASH_SOURCE = "+JSON.valueToTree(bash).toString());
        if(source.getBytes(StandardCharsets.UTF_8).length>524288)throw invalid();
    }
    public String source(){return source;}
    /** Product-bound expected stdin identity, not an independent measurement of executed remote code. */
    public String sourceVersion(){return sha(source.getBytes(StandardCharsets.UTF_8));}
    public Request request(Binding binding,UUID action){
        Objects.requireNonNull(binding);Objects.requireNonNull(action);
        var f=binding.followup();var d=f.diagnostic();var c=d.continuation();var o=c.original();
        var priorProgram=new DeploymentAgentPublicationContractDiagnosticFollowupProgram(layout);
        if(!o.layoutSha256().equals(layout.digest())||!binding.prior().sourceVersion().equals(priorProgram.sourceVersion())
            ||Set.of(o.pendingAction(),c.preflight().action(),d.prior().action(),f.prior().action(),binding.prior().action()).contains(action))throw invalid();
        try(var priorRequest=priorProgram.request(f,binding.prior().action())){ /* Validation only; never sent. */ }
        var node=JSON.createObjectNode();node.set("binding",bindingNode(binding));node.put("action",action.toString()).put("sourceVersion",sourceVersion());String argument=node.toString();if(argument.getBytes(StandardCharsets.UTF_8).length>12000)throw invalid();
        return new Request(new CommandRequest(script(argument,node),source.getBytes(StandardCharsets.UTF_8),TIMEOUT,OUTPUT_LIMIT),targets(binding),binding,action);
    }
    private String script(String argument,JsonNode envelope){
        var refused=JSON.createObjectNode().put("format",FORMAT).put("sourceVersion",sourceVersion()).put("action",text(envelope,"action"));refused.set("binding",envelope.path("binding"));refused.put("status","REFUSED").put("check","ENVIRONMENT").put("mutationStarted",false);for(String k:List.of("currentAbsent","unitAbsent","releaseAbsent","maintenanceAbsent","candidateRetained"))refused.putNull(k);
        String fail="builtin printf '%s\\n' "+quote(FRAME+refused)+"; exit 0";
        return "wtl_argument="+quote(argument)+"\nset -fu\nLC_ALL=C\nbuiltin export LC_ALL\nif [[ ${BASH_ENV+x} != x || ${ENV+x} != x || \"$BASH_ENV\" != /dev/null || \"$ENV\" != /dev/null || ${LD_PRELOAD+x} || ${LD_LIBRARY_PATH+x} ]]; then "+fail+"; fi\nbuiltin unset BASH_ENV ENV || { "+fail+"; }\nexec /usr/bin/python3 -I -S - \"$wtl_argument\" 'SDK_DEV_NULL_SENTINELS_REMOVED_CORRECTION_V1'\n";
    }
    private Set<ActionTarget> targets(Binding b){var o=b.followup().diagnostic().continuation().original();String s=o.server(),u=o.unit();return Set.of(new ActionTarget(s,"task",o.task().toString()),new ActionTarget(s,"agent-publication-correction",sourceVersion()),new ActionTarget(s,"application",u),new ActionTarget(s,"deployment-release",layout.applicationRoot(u)+"/releases/"+o.releaseSha256()),new ActionTarget(s,"deployment-build",layout.candidateRoot(o.candidate())),new ActionTarget(s,"deployment-build",layout.applicationLockRoot(u)),new ActionTarget(s,"deployment-snapshot",layout.snapshotsRoot()),new ActionTarget(s,"systemd-unit","windowstolinux-"+u+".service"),new ActionTarget(s,"systemd-unit","windowstolinux-build-"+o.candidate()+".service"));}
    public static Map<String,Object> reviewFacts(CommandRequest command,DeploymentRemoteLayout layout){var p=new DeploymentAgentPublicationCorrectionProgram(layout);try(var r=p.decode(command)){return Map.of("operation",OPERATION,"binding",bindingNode(r.binding()),"action",r.action().toString(),"expectedSourceVersion",p.sourceVersion(),"executableInputReference","commandEvidence.completeExecutableInput","scope","One separately reviewed correction of the fixed executable source: complete original native definitions, one current-state first-native rollback and exact owned maintenance removal. Both existing locks opened read-only; all six fingerprints and full original parameter/data/unit guards precede mutation. Candidate retained, independent cleanup still required. Prior completed followup and newer wholegraph are mandatory. No helper/controller installation, snapshot creation, retry/replay, old ACK/result replacement or settlement permission. UNKNOWN mutationStarted is conservative and never proof of actual mutation. Full fixed Python/Bash stdin reviewed once. Exact SDK DEV_NULL sentinels and LD presence checked before Python; only those two sentinels removed. Complete bounded BASH_FUNC/environment classification occurs after Python starts, before native IO. sourceVersion is product-bound expected stdin SHA, not independent remote measurement.");}}
    public static Set<ActionTarget> reviewedResourceTargets(CommandRequest command,DeploymentRemoteLayout layout,ServerId server){var p=new DeploymentAgentPublicationCorrectionProgram(layout);try(var r=p.decode(command)){if(!r.binding().followup().diagnostic().continuation().original().server().equals(server.id()))throw invalid();return r.resourceTargets();}}
    public Request decode(CommandRequest command){
        String prefix="wtl_argument='";if(!command.script().startsWith(prefix)||!command.timeout().equals(TIMEOUT)||command.outputLimitBytes()!=OUTPUT_LIMIT||!Arrays.equals(command.stdin(),source.getBytes(StandardCharsets.UTF_8)))throw invalid();int end=command.script().indexOf("'\n",prefix.length());if(end<0)throw invalid();String argument=command.script().substring(prefix.length(),end);
        try{var n=JSON.readTree(argument);fields(n,Set.of("binding","action","sourceVersion"));if(!n.toString().equals(argument)||!text(n,"sourceVersion").equals(sourceVersion()))throw invalid();var r=request(readBinding(n.path("binding")),uuid(text(n,"action")));if(!r.command().script().equals(command.script())){r.close();throw invalid();}return r;}catch(IOException e){throw invalid();}
    }
    public Receipt parse(Request request,OperationResult<CommandResult> actual){if(actual.outcome()!=TaskResult.SUCCEEDED||actual.value().isEmpty())throw invalid();var r=actual.value().orElseThrow();String out=r.stdout();if(r.truncated()||r.exitCode().isEmpty()||r.exitCode().orElseThrow()!=0||!r.stderr().isEmpty()||!out.startsWith(FRAME)||!out.endsWith("\n")||out.indexOf('\n')!=out.length()-1||out.indexOf('\r')>=0||out.getBytes(StandardCharsets.UTF_8).length>OUTPUT_LIMIT)throw invalid();return validateReceipt(request,out.substring(FRAME.length(),out.length()-1));}
    public Receipt validateReceipt(Request request,String canonicalJson){try(var r=decode(request.command())){
        if(!r.binding().equals(request.binding())||!r.action().equals(request.action())||!r.resourceTargets().equals(request.resourceTargets())||canonicalJson==null||canonicalJson.getBytes(StandardCharsets.UTF_8).length+FRAME.length()+1>OUTPUT_LIMIT)throw invalid();var n=JSON.readTree(canonicalJson);fields(n,Set.of("format","sourceVersion","action","binding","status","check","mutationStarted","currentAbsent","unitAbsent","releaseAbsent","maintenanceAbsent","candidateRetained"));
        if(!n.toString().equals(canonicalJson)||!text(n,"format").equals(FORMAT)||!text(n,"sourceVersion").equals(sourceVersion())||!uuid(text(n,"action")).equals(request.action())||!readBinding(n.path("binding")).equals(request.binding())||!n.path("mutationStarted").isBoolean()||!CHECKS.contains(text(n,"check")))throw invalid();
        String status=text(n,"status");boolean started=n.path("mutationStarted").booleanValue(),ok=status.equals("ROLLED_BACK");if(!Set.of("ROLLED_BACK","REFUSED","UNKNOWN").contains(status)||ok&&(!started||!text(n,"check").equals("COMPLETE"))||status.equals("REFUSED")&&started||status.equals("UNKNOWN")&&!started||!ok&&text(n,"check").equals("COMPLETE"))throw invalid();
        for(String k:List.of("currentAbsent","unitAbsent","releaseAbsent","maintenanceAbsent","candidateRetained"))if(ok?(!n.path(k).isBoolean()||!n.path(k).booleanValue()):!n.path(k).isNull())throw invalid();return new Receipt(n.toString());}catch(IOException e){throw invalid();}}
    private static ObjectNode bindingNode(Binding b){return JSON.valueToTree(b);}
    private static Binding readBinding(JsonNode n)throws IOException{
        fields(n,Set.of("followup","prior","observationEnd","observationSha256"));
        fields(n.path("prior"),Set.of("action","requestEvent","endEvent","requestDetailSha256","receiptSha256","sourceVersion"));
        positive(n,"observationEnd");positive(n.path("prior"),"requestEvent");positive(n.path("prior"),"endEvent");uuid(text(n.path("prior"),"action"));
        var b=JSON.treeToValue(n,Binding.class);
        // Exact normalized record tree rejects coercions/missing/foreign fields without LongNode/IntNode mismatch.
        if(!JSON.readTree(JSON.writeValueAsBytes(b)).equals(n))throw invalid();return b;
    }

    private static String resource(String name){try(var input=DeploymentAgentPublicationCorrectionProgram.class.getResourceAsStream(ROOT+name)){if(input==null)throw invalid();byte[] bytes=input.readNBytes(1048577);if(bytes.length>1048576)throw invalid();return StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString().replace("\r\n","\n");}catch(IOException e){throw new IllegalStateException("Fixed correction resource unavailable",e);}}
    private static long positive(JsonNode node,String key){if(!node.path(key).isIntegralNumber()||!node.path(key).canConvertToLong()||node.path(key).longValue()<=0)throw invalid();return node.path(key).longValue();}
    private static void fields(JsonNode node, Set<String> expected) {
        if (node == null || !node.isObject()) throw invalid();
        var actual = new HashSet<String>(); node.fieldNames().forEachRemaining(actual::add); if (!actual.equals(expected)) throw invalid();
    }
    private static String text(JsonNode node, String key) { if (!node.path(key).isTextual()) throw invalid(); return node.path(key).textValue(); }
    private static UUID uuid(String value) { var id = UUID.fromString(value); if (!id.toString().equals(value)) throw invalid(); return id; }
    private static void digest(String value) { if (value == null || !value.matches("[a-f0-9]{64}")) throw invalid(); }
    private static String quote(String value) { return "'" + value.replace("'", "'\\''") + "'"; }
    private static String sha(byte[] bytes) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); } }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("Fixed correction evidence invalid"); }
}
