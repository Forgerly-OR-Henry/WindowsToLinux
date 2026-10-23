package gold.debug.windowstolinux.mod.deployment.service.agent.recovery;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import gold.debug.windowstolinux.appapi.linux.command.CommandRequest;
import gold.debug.windowstolinux.appapi.linux.command.CommandResult;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout;
import gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayoutTemplate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DeploymentAgentPublicationCorrectionProgramTest {
    private static final ObjectMapper JSON=new ObjectMapper();
    private final DeploymentRemoteLayout layout=DeploymentRemoteLayout.original();
    private final DeploymentAgentPublicationCorrectionProgram program=new DeploymentAgentPublicationCorrectionProgram(layout);
    private DeploymentAgentPublicationCorrectionProgram.Binding binding(){
        var o=new DeploymentAgentPublicationRecoveryDiagnosticProgram.Binding("host",UUID.fromString("072bc074-4bce-4c14-b425-f2ff965c7d40"),UUID.fromString("45e3a9a4-a6b8-403a-89f5-605e11eeeca0"),UUID.fromString("d5ace0b0-25c5-4fa9-a8fa-dc1839cd5b24"),271,"a".repeat(64),"b".repeat(64),"backend","c".repeat(64),"backend-0123456789abcdef","d".repeat(64),layout.digest());
        var c=new DeploymentAgentPublicationContinuationProgram.Binding(o,272,"e".repeat(64),new DeploymentAgentPublicationContinuationProgram.PriorPreflight(UUID.fromString("e3c6caa1-3a02-4a85-ad7a-7552fc73ec6c"),353,361,"f".repeat(64),"1".repeat(64)),new DeploymentAgentPublicationContinuationProgram.CurrentFingerprints("2".repeat(64),"3".repeat(64),"4".repeat(64),"5".repeat(64),"6".repeat(64),"7".repeat(64)));
        var d=new DeploymentAgentPublicationContractDiagnosticProgram.Binding(c,new DeploymentAgentPublicationContractDiagnosticProgram.PriorContinuation(UUID.fromString("cd6fee42-b595-433b-8b13-b79b5ed3ad24"),385,391,"8".repeat(64),"9".repeat(64),new DeploymentAgentPublicationContinuationProgram(layout).sourceVersion()),426,"0".repeat(64));
        // 480/530 and the followup action are local fixture values, not live-ledger claims.
        var f=new DeploymentAgentPublicationContractDiagnosticFollowupProgram.Binding(d,new DeploymentAgentPublicationContractDiagnosticFollowupProgram.PriorDiagnostic(UUID.fromString("dce2e4ed-de76-4c01-894b-99ee1b980cb7"),449,456,"a".repeat(64),"b".repeat(64),new DeploymentAgentPublicationContractDiagnosticProgram(layout).sourceVersion()),480,"c".repeat(64));
        return new DeploymentAgentPublicationCorrectionProgram.Binding(f,new DeploymentAgentPublicationCorrectionProgram.PriorFollowup(UUID.fromString("11111111-2222-4333-8444-555555555555"),491,498,"d".repeat(64),"e".repeat(64),new DeploymentAgentPublicationContractDiagnosticFollowupProgram(layout).sourceVersion()),530,"f".repeat(64));
    }
    @Test void correctionAssemblesCompleteSourceAndReachesTheFixedEntry()throws Exception{
        assertEquals("ee44f8cce327f38b8b6c519829ef8581301261eee7fbbdc77fb47ce14053f113",new DeploymentAgentPublicationContinuationProgram(layout).sourceVersion());
        assertEquals("c9c21e6e00a04dec8fd64767cee299eae47d684d88c2f5036efa9b0923cbbc36",new DeploymentAgentPublicationContractDiagnosticProgram(layout).sourceVersion());
        assertEquals("4bce53b129711ad351344107e8f298b2588c20f0ec82f9856d9b5e3b19eb2021",new DeploymentAgentPublicationRecoveryProgram(layout).version());
        try(var r=program.request(binding(),UUID.randomUUID());var decoded=program.decode(r.command())){
            assertEquals(r.binding(),decoded.binding());assertArrayEquals(program.source().getBytes(StandardCharsets.UTF_8),r.command().stdin());
            assertEquals(Duration.ofSeconds(180),r.command().timeout());assertEquals(16384,r.command().outputLimitBytes());assertEquals(9,r.resourceTargets().size());
            assertEquals(r.resourceTargets(),DeploymentAgentPublicationCorrectionProgram.reviewedResourceTargets(r.command(),layout,new ServerId("host")));
            assertEquals("commandEvidence.completeExecutableInput",DeploymentAgentPublicationCorrectionProgram.reviewFacts(r.command(),layout).get("executableInputReference"));
            try(var changed=new CommandRequest(r.command().script()+"\n",r.command().stdin(),r.command().timeout(),r.command().outputLimitBytes())){assertThrows(IllegalArgumentException.class,()->program.decode(changed));}
            try(var changed=new CommandRequest(r.command().script(),(program.source()+"\n").getBytes(StandardCharsets.UTF_8),r.command().timeout(),r.command().outputLimitBytes())){assertThrows(IllegalArgumentException.class,()->program.decode(changed));}
        }
        String body=assembledBash();
        Result syntax=bash(body,true);assertEquals(0,syntax.exit(),syntax.output());
        // Execute the real complete assembly with no args: the actual fixed entry must
        // reject before require_root or any filesystem/lifecycle operation is reached.
        Result entry=bash(body,false);assertEquals(74,entry.exit(),entry.output());assertEquals("CONTINUATION_REFUSED=ARGUMENTS\n",entry.output());
    }
    @Test void correctionRejectsChangedPriorAndMalformedReceipts()throws Exception{
        try(var r=program.request(binding(),UUID.randomUUID())){
            var n=envelope(r);n.put("status","ROLLED_BACK").put("check","COMPLETE").put("mutationStarted",true);for(String k:goals())n.put(k,true);
            assertEquals(n.toString(),program.validateReceipt(r,n.toString()).canonicalJson());
            String frame=DeploymentAgentPublicationCorrectionProgram.FRAME+n+"\n";assertEquals(n.toString(),program.parse(r,actual(frame,"",0,false)).canonicalJson());
            for(var bad:List.of(actual(frame,"private-value",0,false),actual(frame,"",1,false),actual(frame,"",0,true),actual(frame+frame,"",0,false)))assertThrows(IllegalArgumentException.class,()->program.parse(r,bad));
            for(String k:List.of("format","sourceVersion","action","binding","status","check","mutationStarted","currentAbsent","unitAbsent","releaseAbsent","maintenanceAbsent","candidateRetained")){var bad=n.deepCopy();bad.remove(k);assertThrows(IllegalArgumentException.class,()->program.validateReceipt(r,bad.toString()));}
            for(String k:goals()){var bad=n.deepCopy().put(k,false);assertThrows(IllegalArgumentException.class,()->program.validateReceipt(r,bad.toString()));}
            for(String k:List.of("requestDetailSha256","receiptSha256","sourceVersion")){var bad=n.deepCopy();((ObjectNode)bad.path("binding").path("prior")).put(k,"9".repeat(64));assertThrows(IllegalArgumentException.class,()->program.validateReceipt(r,bad.toString()));}
            var numeric=n.deepCopy();((ObjectNode)numeric.path("binding").path("followup").path("diagnostic").path("continuation")).put("pendingResultEvent",272.5);assertThrows(IllegalArgumentException.class,()->program.validateReceipt(r,numeric.toString()));
            assertThrows(IllegalArgumentException.class,()->program.validateReceipt(r,n.toString().replace("\"status\":","\"foreign\":true,\"status\":")));
            assertThrows(IllegalArgumentException.class,()->program.validateReceipt(r,n.toString().replace("\"status\":","\"status\":\"UNKNOWN\",\"status\":")));
            var unknown=n.deepCopy().put("status","UNKNOWN").put("check","INTERNAL");for(String k:goals())unknown.putNull(k);assertEquals(unknown.toString(),program.validateReceipt(r,unknown.toString()).canonicalJson());
            var notStarted=unknown.deepCopy().put("mutationStarted",false);assertThrows(IllegalArgumentException.class,()->program.validateReceipt(r,notStarted.toString()));
            var refused=unknown.deepCopy().put("status","REFUSED").put("check","FINGERPRINTS").put("mutationStarted",false);assertEquals(refused.toString(),program.validateReceipt(r,refused.toString()).canonicalJson());
            try(var other=program.request(binding(),UUID.randomUUID())){assertThrows(IllegalArgumentException.class,()->program.validateReceipt(other,n.toString()));}
            // Python validates the same fixed data shape before touching native IO.
            var input=JSON.createObjectNode();input.set("binding",JSON.valueToTree(r.binding()));input.put("action",r.action().toString()).put("sourceVersion",program.sourceVersion());
            assertEquals(0,pythonValidation(input).exit());
            var extra=input.deepCopy();((ObjectNode)extra.path("binding").path("followup")).put("foreign",true);assertNotEquals(0,pythonValidation(extra).exit());
            var booleanEvent=input.deepCopy();((ObjectNode)booleanEvent.path("binding").path("prior")).put("requestEvent",true);assertNotEquals(0,pythonValidation(booleanEvent).exit());
        }
        var b=binding();var wrong=new DeploymentAgentPublicationCorrectionProgram.PriorFollowup(b.prior().action(),491,498,b.prior().requestDetailSha256(),b.prior().receiptSha256(),"0".repeat(64));
        assertThrows(IllegalArgumentException.class,()->program.request(new DeploymentAgentPublicationCorrectionProgram.Binding(b.followup(),wrong,530,"f".repeat(64)),UUID.randomUUID()));
        var c=b.followup().diagnostic().continuation();for(UUID old:List.of(c.original().pendingAction(),c.preflight().action(),b.followup().diagnostic().prior().action(),b.followup().prior().action(),b.prior().action()))assertThrows(IllegalArgumentException.class,()->program.request(b,old));
        assertThrows(IllegalArgumentException.class,()->new DeploymentAgentPublicationCorrectionProgram.Binding(b.followup(),b.prior(),498,"f".repeat(64)));
    }
    @Test void correctionGuardRejectsDriftAndPerformsOneOwnedRollback(@TempDir Path temp)throws Exception{
        String full=assembledBash();
        for(String scenario:List.of("candidate-owner","task","maintenance","release-owner","parameters","unit","current","full-parameters","data-links","unit-render","fragment","dropins","job","expected")){
            Path f=temp.resolve(scenario);prepare(f);String args=arguments(f),release="b".repeat(64);
            var paths=Map.of("candidate-owner","state/work/backend-0123456789abcdef/.deployer","task","state/work/backend-0123456789abcdef/.agent-task","maintenance","state/application-locks/backend/maintenance","release-owner","apps/backend/releases/"+release+"/.windowstolinux-owner","parameters","apps/backend/releases/"+release+"/.windowstolinux-deployment-parameters","unit","units/backend.service","current","apps/backend/current");
            if(paths.containsKey(scenario))Files.writeString(f.resolve(paths.get(scenario)),"foreign");if(scenario.equals("job"))Files.writeString(f.resolve("state/application-locks/backend/job"),"foreign");
            Result result=bash(guardScript(f,scenario,args,full),false);
            if(!scenario.equals("expected")){
                assertEquals(74,result.exit(),result.output());assertTrue(result.output().contains("CONTINUATION_REFUSED="));assertFalse(result.output().contains("CONTINUATION_MUTATION_STARTED"));assertEquals("",Files.readString(f.resolve("effects")));
                assertTrue(Files.exists(f.resolve("apps/backend/releases/"+release)));assertTrue(Files.exists(f.resolve("state/application-locks/backend/maintenance")));
            }else{
                assertEquals(0,result.exit(),result.output());assertEquals("CONTINUATION_MUTATION_STARTED=1\nROLLED_BACK=1\nCONTINUATION_COMPLETED=1\n",result.output());
                var lines=Files.readAllLines(f.resolve("effects"));assertEquals(1,lines.stream().filter(v->v.equals("LOCK_HELD_AT_STOP=1")).count());assertEquals(4,lines.stream().filter(v->v.equals("LOCK_HELD_AT_DELETE=1")).count());
                for(String path:List.of("apps/backend/current","units/backend.service","apps/backend/releases/"+release,"state/application-locks/backend/maintenance"))assertFalse(Files.exists(f.resolve(path)));
            }
            assertEquals("admission-sentinel",Files.readString(f.resolve("state/application-locks/backend/admission")));assertEquals("run-sentinel",Files.readString(f.resolve("state/application-locks/backend/run")));
            assertEquals("foreign-marker",Files.readString(f.resolve("state/application-locks/backend/foreign-maintenance")));assertEquals("retained-data",Files.readString(f.resolve("apps/backend/persistent-data")));assertTrue(Files.exists(f.resolve("state/work/backend-0123456789abcdef/.agent-task")));
        }
    }
    private static List<String> goals(){return List.of("currentAbsent","unitAbsent","releaseAbsent","maintenanceAbsent","candidateRetained");}
    private ObjectNode envelope(DeploymentAgentPublicationCorrectionProgram.Request r){var n=JSON.createObjectNode().put("format",DeploymentAgentPublicationCorrectionProgram.FORMAT).put("sourceVersion",program.sourceVersion()).put("action",r.action().toString());n.set("binding",JSON.valueToTree(r.binding()));return n;}
    private static OperationResult<CommandResult> actual(String out,String err,int exit,boolean truncated){return new OperationResult<>(TaskResult.SUCCEEDED,Optional.of(new CommandResult(Optional.of(exit),out,err,truncated)),Optional.empty());}
    private String assembledBash()throws Exception{
        String fixture="import json,sys\nv=json.load(sys.stdin)\nn={'__name__':'fixture'}\nexec(compile(v['source'],'fixed-correction','exec'),n)\nsys.stdout.write(n['BASH_SOURCE'])\n";
        Result result=execute(List.of(System.getProperty("managed.test.python","python"),"-I","-S","-c",fixture),JSON.writeValueAsBytes(Map.of("source",program.source())),524288);assertEquals(0,result.exit(),result.output());return result.output();
    }
    private Result pythonValidation(JsonNode value)throws Exception{
        String fixture="import json,sys\nv=json.load(sys.stdin)\nn={'__name__':'fixture'}\nexec(compile(v['source'],'fixed-correction','exec'),n)\na=v['value']\nn['validate_binding'](a['binding'],a['action'],a['sourceVersion'])\n";
        return execute(List.of(System.getProperty("managed.test.python","python"),"-I","-S","-c",fixture),JSON.writeValueAsBytes(Map.of("source",program.source(),"value",value)),16384);
    }
    private record Result(int exit,String output){}
    private static Result bash(String source,boolean syntax)throws Exception{
        String path=System.getProperty("managed.test.bash","/bin/bash");org.junit.jupiter.api.Assumptions.assumeTrue(Files.isExecutable(Path.of(path)));var args=new ArrayList<>(List.of(path,"--noprofile","--norc","-p"));if(syntax)args.add("-n");args.add("-s");return execute(args,source.getBytes(StandardCharsets.UTF_8),32768);
    }
    private static Result execute(List<String> command,byte[] input,int limit)throws Exception{
        var p=new ProcessBuilder(command).redirectErrorStream(true).start();var pool=Executors.newFixedThreadPool(2);var drain=pool.submit(()->p.getInputStream().readNBytes(limit+1));var writer=pool.submit(()->{try(var out=p.getOutputStream()){out.write(input);}return null;});
        try{assertTrue(p.waitFor(20,TimeUnit.SECONDS));writer.get(5,TimeUnit.SECONDS);byte[] output=drain.get(5,TimeUnit.SECONDS);assertTrue(output.length<=limit);return new Result(p.exitValue(),new String(output,StandardCharsets.UTF_8).replace("\r\n","\n"));}
        finally{if(p.isAlive()){p.destroyForcibly();assertTrue(p.waitFor(5,TimeUnit.SECONDS));}p.getInputStream().close();p.getOutputStream().close();drain.cancel(true);writer.cancel(true);pool.shutdownNow();assertTrue(pool.awaitTermination(5,TimeUnit.SECONDS));}
    }
    // Fixture helpers below reuse the old narrow Windows boundary: actual fixed guard,
    // rollback function and self-owned file changes; root/ownership/systemd/flock and
    // three native-contract inputs are controlled substitutes, not Linux validation.
 private static String sha(byte[] b)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));}
 private static String arguments(Path f)throws Exception{String release="b".repeat(64);var a=new ArrayList<String>(List.of("072bc074-4bce-4c14-b425-f2ff965c7d40","aaaaaaaabbbb4ccc8dddeeeeeeeeeeee","45e3a9a4-a6b8-403a-89f5-605e11eeeca0","backend","a".repeat(64),"backend-0123456789abcdef",release,"c".repeat(64)));for(String file:List.of("state/work/backend-0123456789abcdef/.deployer","state/work/backend-0123456789abcdef/.agent-task","state/application-locks/backend/maintenance","apps/backend/releases/"+release+"/.windowstolinux-owner","apps/backend/releases/"+release+"/.windowstolinux-deployment-parameters","units/backend.service"))a.add(sha(Files.readAllBytes(f.resolve(file))));return String.join(" ",a);}
    private static void prepare(Path fixture) throws Exception {
        String task = "072bc074-4bce-4c14-b425-f2ff965c7d40", release = "b".repeat(64);
        for (String directory : java.util.List.of("apps/backend/releases/" + release, "state/work/backend-0123456789abcdef",
            "state/application-locks/backend", "state/snapshots", "units"))
            java.nio.file.Files.createDirectories(fixture.resolve(directory));
        String root = fixture.toString().replace('\\', '/');
        java.nio.file.Files.writeString(fixture.resolve("apps/backend/current"), root + "/apps/backend/releases/" + (release));
        java.nio.file.Files.writeString(fixture.resolve("apps/backend/releases/" + release + "/.windowstolinux-owner"), "a".repeat(64));
        java.nio.file.Files.writeString(fixture.resolve("apps/backend/releases/" + release + "/.windowstolinux-deployment-parameters"), "fixture-native-daemon\n");
        java.nio.file.Files.writeString(fixture.resolve("state/work/backend-0123456789abcdef/.agent-task"), task);
        java.nio.file.Files.writeString(fixture.resolve("state/work/backend-0123456789abcdef/.deployer"), "root");
        java.nio.file.Files.writeString(fixture.resolve("state/application-locks/backend/admission"), "admission-sentinel");
        java.nio.file.Files.writeString(fixture.resolve("state/application-locks/backend/run"), "run-sentinel");
        java.nio.file.Files.writeString(fixture.resolve("state/application-locks/backend/maintenance"), "deployment-" + release);
        java.nio.file.Files.writeString(fixture.resolve("state/application-locks/backend/foreign-maintenance"), "foreign-marker");
        java.nio.file.Files.writeString(fixture.resolve("apps/backend/persistent-data"), "retained-data");
        java.nio.file.Files.writeString(fixture.resolve("units/backend.service"), "expected-unit\n");
        java.nio.file.Files.writeString(fixture.resolve("expected-unit"), "expected-unit\n");
        java.nio.file.Files.writeString(fixture.resolve("effects"), "");
    }

    private static String guardScript(Path fixture,String scenario,String arguments,String full) throws Exception {
        String guard;
        try(var input=DeploymentAgentPublicationCorrectionProgramTest.class.getResourceAsStream("/gold/debug/windowstolinux/mod/deployment/service/agent/recovery/publication-continuation-guard.sh")){
            guard=DeploymentRemoteLayoutTemplate.render(new String(Objects.requireNonNull(input).readAllBytes(),StandardCharsets.UTF_8).replace("\r\n","\n"),DeploymentRemoteLayout.original());
        }
        String suffix="\n"+guard;assertTrue(full.endsWith(suffix));assertEquals(full.length()-suffix.length(),full.indexOf(suffix));
        // Execute the actual complete assembled definitions, not the old four-fragment subset.
        StringBuilder script=new StringBuilder(full.substring(0,full.length()-suffix.length())).append('\n');
        script.append("""
            fixture='@FIXTURE@'
            scenario='@SCENARIO@'
            base_root="$fixture/state"
            work_root="$base_root/work"
            snapshots_root="$base_root/snapshots"
            applications_root="$fixture/apps"
            lock_directory="$base_root/application-locks/backend"
            lock_calls=0
            require_root() { :; }
            require_deployer() { deployer=root; }
            assert_storage_parent() { :; }
            assert_root_owned_directory() { [[ -d "$1" && ! -L "$1" ]] || reject fixture-directory; }
            assert_root_owned_regular() { [[ -f "$1" && ! -L "$1" ]] || reject fixture-regular; }
            unit_path() { printf '%s/units/backend.service' "$fixture"; }
            load_deployment_parameters() { [[ "$scenario" != full-parameters ]] || reject fixture-parameters; assert_root_owned_regular "$1"; application_mode=DAEMON; deployment_runtime_parameters=(fixture-native-daemon); }
            expected_deployment_unit_digest() { [[ "$scenario" != unit-render ]] || { printf x; return; }; command sha256sum -- "$fixture/expected-unit" | command awk '{print $1}'; }
            assert_managed_data_links() { [[ "$scenario" != data-links ]] || reject fixture-data; }
            # The Windows runner needs no symlink privilege: only CURRENT's representation is virtualized.
            function [() {
                if [[ "$#" = 3 && "$1" = -L && "$2" = "$applications_root/backend/current" ]]; then
                    builtin [ -f "$2" ]; return
                fi
                builtin [ "$@"
            }
            readlink() {
                local target="${!#}"
                [[ "$target" = "$applications_root/backend/current" ]] || return 91
                command cat -- "$target"
            }
            stat() {
                case "$2" in
                    %h) printf '1' ;;
                    %a) if [[ "${!#}" = "$lock_directory" ]]; then printf '700'; else printf '600'; fi ;;
                    *) command stat "$@" ;;
                esac
            }
            install() {
                [[ "$*" = "-d -o root -g root -m 700 -- $lock_directory" && -d "$lock_directory" ]] || return 92
            }
            flock() {
                [[ "$1" = -w && "$3" = -x ]] || return 93
                lock_calls=$((lock_calls + 1))
            }
            fixture_locked() {
                [[ "$lock_calls" = 2 ]] || exit 94
                : <&"$application_admission_fd"
                : <&"$application_run_fd"
            }
            systemctl() {
                case "$*" in
                    'show --value --property FragmentPath windowstolinux-backend.service') if [[ "$scenario" = fragment ]]; then printf /foreign; else unit_path backend; fi ;;
                    'show --value --property DropInPaths windowstolinux-backend.service') if [[ "$scenario" = dropins ]]; then printf /foreign; fi ;;
                    'show --value --property LoadState windowstolinux-backend.service') printf 'loaded' ;;
                    'show --value --property ActiveState '* ) printf 'inactive' ;;
                    'show --value --property MainPID '* ) printf '0' ;;
                    'show --value --property ControlGroup '* ) : ;;
                    'is-active --quiet windowstolinux-backend.service') return 1 ;;
                    'stop windowstolinux-backend.service') fixture_locked; printf 'LOCK_HELD_AT_STOP=1\n' >>"$fixture/effects" ;;
                    daemon-reload) fixture_locked; printf 'RELOAD=1\n' >>"$fixture/effects" ;;
                    *) printf 'UNEXPECTED_SYSTEMCTL=%s\n' "$*" >&2; return 95 ;;
                esac
            }
            rm() {
                local target="${!#}"
                case "$target" in
                    "$fixture/units/backend.service" | "$fixture/apps/backend/current" | "$fixture/apps/backend/releases/@RELEASE@" | "$lock_directory/maintenance") ;;
                    *) printf 'UNEXPECTED_DELETE=%s\n' "$target" >&2; exit 96 ;;
                esac
                fixture_locked
                printf 'LOCK_HELD_AT_DELETE=1\nDELETE=%s\n' "$target" >>"$fixture/effects"
                command rm "$@"
            }
            exec 9>"$fixture/trace"
            BASH_XTRACEFD=9
            PS4='+ '
            set -x
            set -- @ARGUMENTS@
            """.replace("@FIXTURE@", fixture.toString().replace('\\', '/').replace("'", "'\\''"))
            .replace("@SCENARIO@",scenario).replace("@ARGUMENTS@",arguments).replace("@OWNER@", "a".repeat(64)).replace("@RELEASE@", "b".repeat(64)).replace("@PLAN@", "c".repeat(64)));
        script.append(guard);
        return script.toString();
    }



}
