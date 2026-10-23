package gold.debug.windowstolinux.mod.deployment.service.agent.recovery;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import gold.debug.windowstolinux.appapi.linux.command.CommandRequest;
import gold.debug.windowstolinux.appapi.linux.command.CommandResult;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
class DeploymentAgentPublicationContinuationProgramTest {
 private static final ObjectMapper JSON=new ObjectMapper();
 private final DeploymentRemoteLayout layout=DeploymentRemoteLayout.original();
 private final DeploymentAgentPublicationContinuationProgram program=new DeploymentAgentPublicationContinuationProgram(layout);
 private DeploymentAgentPublicationContinuationProgram.Binding binding(){
  var o=new DeploymentAgentPublicationRecoveryDiagnosticProgram.Binding("host",UUID.fromString("072bc074-4bce-4c14-b425-f2ff965c7d40"),UUID.fromString("45e3a9a4-a6b8-403a-89f5-605e11eeeca0"),UUID.fromString("d5ace0b0-25c5-4fa9-a8fa-dc1839cd5b24"),271,"a".repeat(64),"b".repeat(64),"backend","c".repeat(64),"backend-0123456789abcdef","d".repeat(64),layout.digest());
  return new DeploymentAgentPublicationContinuationProgram.Binding(o,272,"e".repeat(64),new DeploymentAgentPublicationContinuationProgram.PriorPreflight(UUID.fromString("e3c6caa1-3a02-4a85-ad7a-7552fc73ec6c"),353,361,"f".repeat(64),"1".repeat(64)),new DeploymentAgentPublicationContinuationProgram.CurrentFingerprints("2".repeat(64),"3".repeat(64),"4".repeat(64),"5".repeat(64),"6".repeat(64),"7".repeat(64)));
 }
 @Test void continuationPreservesInstalledProgramAndReconstructsCurrentBoundRequest()throws Exception{
  var old=new DeploymentAgentPublicationRecoveryProgram(layout);
  assertEquals("4bce53b129711ad351344107e8f298b2588c20f0ec82f9856d9b5e3b19eb2021",old.version());
  assertEquals("477ca1395417bbf1ec024c7e427066775da8d7861f2d2ee759896d598cd59ca2",sha(old.source().getBytes(StandardCharsets.UTF_8)));
  assertEquals("46dc35ea0c0c9b2eb5d2f1d4903891d9a62a391d8c80182a01efc9efdc7d0151",sha(old.loader().getBytes(StandardCharsets.UTF_8)));
  assertEquals("0a16f4fccb224865bbcb42a4472070f54ae7f04a786f18e1bc73bda7bdb94d22",sha(old.installer().getBytes(StandardCharsets.UTF_8)));
  try(var r=program.request(binding(),UUID.randomUUID())){
   assertEquals(program.source(),new String(r.command().stdin(),StandardCharsets.UTF_8));
   assertTrue(program.source().contains("BASH_SOURCE = r"+"'".repeat(3)));
   assertEquals(r.resourceTargets(),DeploymentAgentPublicationContinuationProgram.reviewedResourceTargets(r.command(),layout,new ServerId("host")));
   assertEquals("commandEvidence.completeExecutableInput",DeploymentAgentPublicationContinuationProgram.reviewFacts(r.command(),layout).get("executableInputReference"));
   try(var bad=new CommandRequest(r.command().script(),(program.source()+"\n").getBytes(StandardCharsets.UTF_8),r.command().timeout(),r.command().outputLimitBytes())){assertThrows(IllegalArgumentException.class,()->DeploymentAgentPublicationContinuationProgram.reviewFacts(bad,layout));}
   try(var bad=new CommandRequest(r.command().script()+"\n",r.command().stdin(),r.command().timeout(),r.command().outputLimitBytes())){assertThrows(IllegalArgumentException.class,()->DeploymentAgentPublicationContinuationProgram.reviewFacts(bad,layout));}
  }
 }
 @Test void continuationReceiptRejectsReplayTamperAndUncertainSuccess()throws Exception{
  try(var r=program.request(binding(),UUID.randomUUID())){
   ObjectNode n=JSON.createObjectNode().put("format",DeploymentAgentPublicationContinuationProgram.FORMAT).put("sourceVersion",program.sourceVersion()).put("action",r.action().toString());n.set("binding",JSON.valueToTree(r.binding()));n.put("status","ROLLED_BACK").put("check","COMPLETE").put("mutationStarted",true);
   var goals=List.of("currentAbsent","unitAbsent","releaseAbsent","maintenanceAbsent","candidateRetained");for(String k:goals)n.put(k,true);assertEquals(n.toString(),program.validateReceipt(r,n.toString()).canonicalJson());
   String frame="PUBLICATION_CONTINUATION="+n+"\n";
   assertEquals(n.toString(),program.parse(r,new OperationResult<>(TaskResult.SUCCEEDED,Optional.of(new CommandResult(Optional.of(0),frame,"",false)),Optional.empty())).canonicalJson());
   for(CommandResult bad:List.of(new CommandResult(Optional.of(0),frame,"unsafe",false),new CommandResult(Optional.of(0),frame,"",true),new CommandResult(Optional.of(1),frame,"",false),new CommandResult(Optional.of(0),frame+frame,"",false)))assertThrows(IllegalArgumentException.class,()->program.parse(r,new OperationResult<>(TaskResult.SUCCEEDED,Optional.of(bad),Optional.empty())));
   for(String k:List.of("candidateOwnerSha256","candidateTaskSha256","maintenanceSha256","releaseOwnerSha256","parametersSha256","unitSha256")){var bad=n.deepCopy();((ObjectNode)bad.path("binding").path("current")).put(k,"9".repeat(64));assertThrows(IllegalArgumentException.class,()->program.validateReceipt(r,bad.toString()));}
   for(String k:List.of("requestDetailSha256","receiptSha256")){var bad=n.deepCopy();((ObjectNode)bad.path("binding").path("preflight")).put(k,"9".repeat(64));assertThrows(IllegalArgumentException.class,()->program.validateReceipt(r,bad.toString()));}
   for(String k:goals){var bad=n.deepCopy();bad.put(k,false);assertThrows(IllegalArgumentException.class,()->program.validateReceipt(r,bad.toString()));}
   assertThrows(IllegalArgumentException.class,()->program.validateReceipt(r,n.toString().replace("\"status\":","\"foreign\":true,\"status\":")));
   assertThrows(IllegalArgumentException.class,()->program.validateReceipt(r,n.toString().replace("\"status\":","\"status\":\"UNKNOWN\",\"status\":")));
   var unknown=n.deepCopy();unknown.put("status","UNKNOWN").put("check","ROLLBACK");for(String k:goals)unknown.putNull(k);assertEquals("UNKNOWN",JSON.readTree(program.validateReceipt(r,unknown.toString()).canonicalJson()).path("status").asText());
   try(var replay=program.request(binding(),UUID.randomUUID())){assertThrows(IllegalArgumentException.class,()->program.validateReceipt(replay,n.toString()));}
  }
 }
 @Test void continuationGuardRejectsFingerprintsAndFullNativeDriftBeforeAnyMutation(@TempDir Path temp)throws Exception{
  for(String scenario:List.of("candidate-owner","task","maintenance","release-owner","parameters","unit","current","full-parameters","data-links","unit-render","fragment","dropins","job")){
   Path f=temp.resolve(scenario);prepare(f);String args=arguments(f),release="b".repeat(64);
   var paths=Map.of("candidate-owner","state/work/backend-0123456789abcdef/.deployer","task","state/work/backend-0123456789abcdef/.agent-task","maintenance","state/application-locks/backend/maintenance","release-owner","apps/backend/releases/"+release+"/.windowstolinux-owner","parameters","apps/backend/releases/"+release+"/.windowstolinux-deployment-parameters","unit","units/backend.service","current","apps/backend/current");
   if(paths.containsKey(scenario))Files.writeString(f.resolve(paths.get(scenario)),"foreign");if(scenario.equals("job"))Files.writeString(f.resolve("state/application-locks/backend/job"),"foreign");
   var result=run(guardScript(f,scenario,args));assertNotEquals(0,result.exit(),result.output());assertTrue(result.output().contains("CONTINUATION_REFUSED="),result.output());assertFalse(result.output().contains("CONTINUATION_MUTATION_STARTED"));assertEquals("",Files.readString(f.resolve("effects")));
   assertEquals("admission-sentinel",Files.readString(f.resolve("state/application-locks/backend/admission")));assertEquals("run-sentinel",Files.readString(f.resolve("state/application-locks/backend/run")));assertTrue(Files.exists(f.resolve("apps/backend/releases/"+release)));assertTrue(Files.exists(f.resolve("state/application-locks/backend/maintenance")));
  }
 }
 @Test void continuationGuardRunsOneRollbackRetainsCandidateAndDeletesOnlyOwnedMarker(@TempDir Path f)throws Exception{
  prepare(f);var result=run(guardScript(f,"expected",arguments(f)));assertEquals(0,result.exit(),result.output());assertEquals("CONTINUATION_MUTATION_STARTED=1\nROLLED_BACK=1\nCONTINUATION_COMPLETED=1\n",result.output());var lines=Files.readAllLines(f.resolve("effects"));assertEquals(1,lines.stream().filter(l->l.equals("LOCK_HELD_AT_STOP=1")).count());assertEquals(4,lines.stream().filter(l->l.equals("LOCK_HELD_AT_DELETE=1")).count());
  for(String file:List.of("apps/backend/current","units/backend.service","apps/backend/releases/"+"b".repeat(64),"state/application-locks/backend/maintenance"))assertFalse(Files.exists(f.resolve(file)));assertEquals("foreign-marker",Files.readString(f.resolve("state/application-locks/backend/foreign-maintenance")));assertEquals("retained-data",Files.readString(f.resolve("apps/backend/persistent-data")));assertTrue(Files.exists(f.resolve("state/work/backend-0123456789abcdef/.agent-task")));assertEquals("admission-sentinel",Files.readString(f.resolve("state/application-locks/backend/admission")));assertEquals("run-sentinel",Files.readString(f.resolve("state/application-locks/backend/run")));
 }
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

    private static String guardScript(Path fixture,String scenario,String arguments) throws Exception {
        String resources = "/gold/debug/windowstolinux/mod/deployment/linux/helper/";
        StringBuilder script = new StringBuilder();
        for (String name : java.util.List.of("00-protocol-foundation.sh", "runtime/41-application-admission.sh",
            "runtime/40-typed-runtime.sh", "runtime/systemd/61-service-identity.sh")) {
            try (var input = DeploymentAgentPublicationContinuationProgramTest.class.getResourceAsStream(resources + name)) {
                script.append(new String(java.util.Objects.requireNonNull(input).readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n")).append('\n');
            }
        }
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
        try (var input = DeploymentAgentPublicationContinuationProgramTest.class.getResourceAsStream(
            "/gold/debug/windowstolinux/mod/deployment/service/agent/recovery/publication-continuation-guard.sh")) {
            script.append(new String(java.util.Objects.requireNonNull(input).readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n"));
        }
        return script.toString();
    }


 private record Result(int exit,String output){}
 private static Result run(String script)throws Exception{
  String bash=System.getProperty("managed.test.bash","/bin/bash");org.junit.jupiter.api.Assumptions.assumeTrue(Files.isExecutable(Path.of(bash)));var p=new ProcessBuilder(bash,"--noprofile","--norc","-s").redirectErrorStream(true).start();var e=Executors.newFixedThreadPool(2);var out=e.submit(()->p.getInputStream().readNBytes(16385));var writer=e.submit(()->{try(var input=p.getOutputStream()){input.write(script.getBytes(StandardCharsets.UTF_8));}return null;});
  try{assertTrue(p.waitFor(20,TimeUnit.SECONDS));writer.get(5,TimeUnit.SECONDS);byte[] b=out.get(5,TimeUnit.SECONDS);assertTrue(b.length<=16384);return new Result(p.exitValue(),new String(b,StandardCharsets.UTF_8).replace("\r\n","\n"));}
  finally{if(p.isAlive()){p.destroyForcibly();assertTrue(p.waitFor(5,TimeUnit.SECONDS));}p.getInputStream().close();p.getOutputStream().close();out.cancel(true);writer.cancel(true);e.shutdownNow();assertTrue(e.awaitTermination(5,TimeUnit.SECONDS));}
 }
}
