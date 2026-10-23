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
import gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentToolchainCatalog;
import gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentToolchainType;

/** A fixed current-state action. It grants nothing and neither replays nor replaces an old result. */
public final class DeploymentAgentPublicationContinuationProgram {
    public static final String OPERATION="deployment.agent.publication-continuation.v1";
    public static final String FORMAT="WTL_AGENT_PUBLICATION_CONTINUATION_1";
    private static final String ROOT="/gold/debug/windowstolinux/mod/deployment/",FRAME="PUBLICATION_CONTINUATION=";
    private static final Duration TIMEOUT=Duration.ofSeconds(180);
    private static final long OUTPUT_LIMIT=16384;
    private static final Set<String> CHECKS=Set.of("ENVIRONMENT","ARGUMENTS","ROOT_IDENTITY","LOCKS","JOB","CANDIDATE","FINGERPRINTS","BUILD","SNAPSHOTS","MAINTENANCE","CURRENT","NATIVE_CONTRACT","FULL_PARAMETER_VALIDATION","MANAGED_DATA_LINKS","UNIT_RENDER_MATCH","ROLLBACK","POSTCONDITIONS","COMPLETE","INTERNAL","LIMIT");
    private static final ObjectMapper JSON=com.fasterxml.jackson.databind.json.JsonMapper.builder().enable(com.fasterxml.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION).enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private final DeploymentRemoteLayout layout;
    private final String source;
    public record PriorPreflight(UUID action,long requestEvent,long endEvent,String requestDetailSha256,String receiptSha256){
        public PriorPreflight{Objects.requireNonNull(action);if(requestEvent<=0||endEvent<=requestEvent)throw invalid();digest(requestDetailSha256);digest(receiptSha256);}}
    public record CurrentFingerprints(String candidateOwnerSha256,String candidateTaskSha256,String maintenanceSha256,String releaseOwnerSha256,String parametersSha256,String unitSha256){
        public CurrentFingerprints{for(String v:List.of(candidateOwnerSha256,candidateTaskSha256,maintenanceSha256,releaseOwnerSha256,parametersSha256,unitSha256))digest(v);}}
    public record Binding(DeploymentAgentPublicationRecoveryDiagnosticProgram.Binding original,long pendingResultEvent,String pendingResultSha256,PriorPreflight preflight,CurrentFingerprints current){
        public Binding{Objects.requireNonNull(original);Objects.requireNonNull(preflight);Objects.requireNonNull(current);digest(pendingResultSha256);if(pendingResultEvent<=original.pendingIntentEvent()||preflight.requestEvent()<=pendingResultEvent||preflight.action().equals(original.pendingAction()))throw invalid();}}
    public record Request(CommandRequest command,Set<ActionTarget> resourceTargets,Binding binding,UUID action)implements AutoCloseable{
        public Request{Objects.requireNonNull(command);resourceTargets=Set.copyOf(resourceTargets);Objects.requireNonNull(binding);Objects.requireNonNull(action);}
        @Override public void close(){command.close();}}
    public record Receipt(String canonicalJson){public Receipt{Objects.requireNonNull(canonicalJson);}}
    public DeploymentAgentPublicationContinuationProgram(DeploymentRemoteLayout layout){
        this.layout=Objects.requireNonNull(layout);
        for(String root:List.of(layout.paths().helperDirectory(),layout.paths().applicationsRoot(),layout.paths().stateRoot(),layout.paths().configurationRoot(),layout.paths().dataRoot()))if(!root.matches("/(?:[A-Za-z0-9._-]+/)*[A-Za-z0-9._-]+")||root.contains(".."))throw invalid();
        String base=assembleBase(layout),oldGuard=DeploymentRemoteLayoutTemplate.render(resource("service/agent/recovery/publication-recovery-guard.sh"),layout);
        // Compare only: never slice, execute or include the old immediate entry.
        if(!DeploymentManagedHelperBundle.renderPublicationRecovery(layout).equals(base+"\n"+oldGuard))throw invalid();
        String bash=base+"\n"+DeploymentRemoteLayoutTemplate.render(resource("service/agent/recovery/publication-continuation-guard.sh"),layout);
        if(bash.contains("'''"))throw invalid();
        String template=resource("service/agent/recovery/publication-continuation.py"),marker="# @WTL_CONTINUATION_BASH@";
        if(template.indexOf(marker)<0||template.indexOf(marker)!=template.lastIndexOf(marker))throw invalid();
        source=template.replace(marker,"BASH_SOURCE = r'''"+bash+"'''");if(source.getBytes(StandardCharsets.UTF_8).length>524288)throw invalid();
    }
    public String source(){return source;}
    /** Product-bound expected stdin identity, not an independent measurement of executed remote code. */
    public String sourceVersion(){return sha(source.getBytes(StandardCharsets.UTF_8));}
    public Request request(Binding binding,UUID action){
        Objects.requireNonNull(binding);Objects.requireNonNull(action);if(!binding.original().layoutSha256().equals(layout.digest())||action.equals(binding.original().pendingAction())||action.equals(binding.preflight().action()))throw invalid();
        var node=JSON.createObjectNode();node.set("binding",bindingNode(binding));node.put("action",action.toString()).put("sourceVersion",sourceVersion());String argument=node.toString();if(argument.getBytes(StandardCharsets.UTF_8).length>12000)throw invalid();
        return new Request(new CommandRequest(script(argument,node),source.getBytes(StandardCharsets.UTF_8),TIMEOUT,OUTPUT_LIMIT),targets(binding),binding,action);
    }
    private String script(String argument,JsonNode envelope){
        var refused=JSON.createObjectNode().put("format",FORMAT).put("sourceVersion",sourceVersion()).put("action",text(envelope,"action"));refused.set("binding",envelope.path("binding"));refused.put("status","REFUSED").put("check","ENVIRONMENT").put("mutationStarted",false);for(String k:List.of("currentAbsent","unitAbsent","releaseAbsent","maintenanceAbsent","candidateRetained"))refused.putNull(k);
        String fail="builtin printf '%s\\n' "+quote(FRAME+refused)+"; exit 0";
        return "wtl_argument="+quote(argument)+"\nset -fu\nLC_ALL=C\nbuiltin export LC_ALL\nif [[ ${BASH_ENV+x} != x || ${ENV+x} != x || \"$BASH_ENV\" != /dev/null || \"$ENV\" != /dev/null || ${LD_PRELOAD+x} || ${LD_LIBRARY_PATH+x} ]]; then "+fail+"; fi\nbuiltin unset BASH_ENV ENV || { "+fail+"; }\nexec /usr/bin/python3 -I -S - \"$wtl_argument\" 'SDK_DEV_NULL_SENTINELS_REMOVED_CONTINUATION_V1'\n";
    }
    private Set<ActionTarget> targets(Binding b){var o=b.original();String s=o.server(),u=o.unit();return Set.of(new ActionTarget(s,"task",o.task().toString()),new ActionTarget(s,"agent-publication-continuation",sourceVersion()),new ActionTarget(s,"application",u),new ActionTarget(s,"deployment-release",layout.applicationRoot(u)+"/releases/"+o.releaseSha256()),new ActionTarget(s,"deployment-build",layout.candidateRoot(o.candidate())),new ActionTarget(s,"deployment-build",layout.applicationLockRoot(u)),new ActionTarget(s,"deployment-snapshot",layout.snapshotsRoot()),new ActionTarget(s,"systemd-unit","windowstolinux-"+u+".service"),new ActionTarget(s,"systemd-unit","windowstolinux-build-"+o.candidate()+".service"));}
    public static Map<String,Object> reviewFacts(CommandRequest command,DeploymentRemoteLayout layout){var p=new DeploymentAgentPublicationContinuationProgram(layout);try(var r=p.decode(command)){return Map.of("operation",OPERATION,"binding",bindingNode(r.binding()),"action",r.action().toString(),"expectedSourceVersion",p.sourceVersion(),"executableInputReference","commandEvidence.completeExecutableInput","scope","One new current-state first-native rollback and exact owned maintenance removal. Both existing locks opened read-only; all six fingerprints and full original parameter/data/unit guards precede mutation. Candidate retained, independent cleanup still required. No installation, snapshot creation, retry, old result replacement or settlement permission. Full fixed Python/Bash stdin reviewed once. Exact SDK DEV_NULL sentinels and LD presence checked before Python; only those two sentinels removed. Complete bounded BASH_FUNC/environment classification occurs after Python starts, before native IO. sourceVersion is product-bound expected stdin SHA, not independent remote measurement.");}}
    public static Set<ActionTarget> reviewedResourceTargets(CommandRequest command,DeploymentRemoteLayout layout,ServerId server){var p=new DeploymentAgentPublicationContinuationProgram(layout);try(var r=p.decode(command)){if(!r.binding().original().server().equals(server.id()))throw invalid();return r.resourceTargets();}}
    private Request decode(CommandRequest command){
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
    private static Binding readBinding(JsonNode n)throws IOException{fields(n,Set.of("original","pendingResultEvent","pendingResultSha256","preflight","current"));fields(n.path("original"),Set.of("server","task","execution","pendingAction","pendingIntentEvent","pendingIntentSha256","planSha256","unit","ownerSha256","candidate","releaseSha256","layoutSha256"));fields(n.path("preflight"),Set.of("action","requestEvent","endEvent","requestDetailSha256","receiptSha256"));fields(n.path("current"),Set.of("candidateOwnerSha256","candidateTaskSha256","maintenanceSha256","releaseOwnerSha256","parametersSha256","unitSha256"));for(String k:List.of("pendingResultEvent"))positive(n,k);positive(n.path("original"),"pendingIntentEvent");positive(n.path("preflight"),"requestEvent");positive(n.path("preflight"),"endEvent");for(String k:List.of("task","execution","pendingAction"))uuid(text(n.path("original"),k));uuid(text(n.path("preflight"),"action"));var b=JSON.treeToValue(n,Binding.class);if(!JSON.readTree(JSON.writeValueAsBytes(b)).equals(n))throw invalid();return b;}

    private static final List<String> FRAGMENTS = List.of("00-protocol-foundation.sh", "input/15-deployment-input.sh",
            "input/16-application-input.sh", "input/17-managed-content.sh", "ecosystem/35-ecosystem-dispatch.sh",
            "toolchain/10-release-metadata.py", "toolchain/20-installation-boundaries.py",
            "toolchain/30-managed-installation.py", "toolchain/40-binding-protocol.py",
            "runtime/40-typed-runtime.sh", "runtime/41-application-admission.sh", "runtime/41-application-runtime.sh",
            "runtime/systemd/61-service-identity.sh", "database/10-native-instances.sh");
    private static final Map<String, String> RESOURCE_INSERTS = Map.of("# @compat:agent-source@\n",
        "workspace/source-operations.py", "# @compat:apparmor@\n",
        "workspace/apparmor-namespace.sh", "# @compat:systemd-isolation@\n",
        "runtime/systemd/systemd-manager-isolation.sh", "# @compat:selinux-entry@\n",
        "runtime/systemd/selinux-command-entry.sh", "# @compat:centos-repositories@\n",
        "toolchain/centos-source-repositories.py", "# @compat:toolchain-archives@\n", "toolchain/archive-boundaries.py");

    private static String assembleBase(DeploymentRemoteLayout layout){
        var output=new StringBuilder();var catalog=DeploymentToolchainCatalog.defaults();
        for(String fragment:FRAGMENTS){
            String content=resource("linux/helper/"+fragment);
            for(var insert:RESOURCE_INSERTS.entrySet())if(content.contains(insert.getKey()))content=content.replaceAll("(?m)^[ \t]*"+java.util.regex.Pattern.quote(insert.getKey()),java.util.regex.Matcher.quoteReplacement(resource("linux/helper/"+insert.getValue())));
            content=DeploymentRemoteLayoutTemplate.render(content,layout);
            if(fragment.startsWith("toolchain/")){
                if(fragment.endsWith("10-release-metadata.py"))output.append("prepare_official_toolchains() {\n  /usr/bin/python3 -I - \"$@\" <<'WTL_OFFICIAL_TOOLCHAINS'\n").append(catalogLiteral(catalog));
                output.append(content).append('\n');if(fragment.endsWith("40-binding-protocol.py"))output.append("WTL_OFFICIAL_TOOLCHAINS\n}\n");continue;
            }
            if(fragment.endsWith("00-protocol-foundation.sh"))content+="build_entry_sha256="+DeploymentManagedHelperBundle.buildEntrySha256(layout)+"\n";
            boolean literal=fragment.endsWith("27-agent-workspace.sh")||fragment.endsWith("10-native-instances.sh")||fragment.endsWith("20-native-targets.sh")||fragment.endsWith("21-native-operations.sh")||fragment.endsWith("23-container-builder.sh")||fragment.endsWith("12-container-image-input.sh")||fragment.endsWith("18-container-storage.sh")||fragment.contains("application-");
            output.append(literal?content:content.replace("\\\\","\\"));
        }
        return output.toString();
    }

    private static String catalogLiteral(DeploymentToolchainCatalog catalog) {
        StringBuilder data = new StringBuilder("SHIPPED_CATALOG = {\n");
        for (var ecosystem : DeploymentToolchainType.values()) {
            data.append("    '").append(ecosystem.name()).append("': [");
            data.append(catalog.branches(ecosystem).stream().map(b -> "'" + b.version() + "'")
                .collect(java.util.stream.Collectors.joining(", "))).append("],\n");
        }
        return data.append("}\n").toString();
    }

    private static String resource(String name){try(var input=DeploymentAgentPublicationContinuationProgram.class.getResourceAsStream(ROOT+name)){if(input==null)throw invalid();byte[] bytes=input.readNBytes(1048577);if(bytes.length>1048576)throw invalid();return StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString().replace("\r\n","\n");}catch(IOException e){throw new IllegalStateException("Fixed continuation resource unavailable",e);}}
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
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("Fixed current-state continuation evidence invalid"); }
}
