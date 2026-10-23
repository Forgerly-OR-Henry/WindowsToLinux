package gold.debug.windowstolinux.mod.deployment.service.agent.recovery;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import gold.debug.windowstolinux.appapi.linux.command.CommandRequest;
import gold.debug.windowstolinux.appapi.linux.command.CommandResult;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout;
import org.junit.jupiter.api.Test;

class DeploymentAgentPublicationRecoveryDiagnosticProgramTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final DeploymentRemoteLayout layout = DeploymentRemoteLayout.original();
    private final DeploymentAgentPublicationRecoveryDiagnosticProgram program = new DeploymentAgentPublicationRecoveryDiagnosticProgram(layout);
    private final DeploymentAgentPublicationRecoveryDiagnosticProgram.Binding binding = new DeploymentAgentPublicationRecoveryDiagnosticProgram.Binding(
        "original-host", UUID.fromString("072bc074-4bce-4c14-b425-f2ff965c7d40"), UUID.fromString("45e3a9a4-a6b8-403a-89f5-605e11eeeca0"),
        UUID.fromString("d5ace0b0-25c5-4fa9-a8fa-dc1839cd5b24"), 271, "a".repeat(64), "b".repeat(64), "backend", "c".repeat(64),
        "backend-0123456789abcdef", "d".repeat(64), layout.digest());

    @Test
    void fixedReadonlyRequestReconstructsEveryBindingAndRejectsChangedExecutable() throws Exception {
        try (var request = program.request(binding, UUID.randomUUID())) {
            assertEquals(program.source(), new String(request.command().stdin(), StandardCharsets.UTF_8));
            assertEquals(program.sourceVersion(), HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(request.command().stdin())));
            assertEquals(request.resourceTargets(), DeploymentAgentPublicationRecoveryDiagnosticProgram.reviewedResourceTargets(request.command(), layout, new ServerId(binding.server())));
            var facts = JSON.valueToTree(DeploymentAgentPublicationRecoveryDiagnosticProgram.reviewFacts(request.command(), layout));
            assertEquals(program.sourceVersion(), facts.path("expectedSourceVersion").textValue());
            assertEquals("commandEvidence.completeExecutableInput", facts.path("executableInputReference").textValue());
            assertFalse(facts.has("completeReadonlySource"));
            assertEquals(binding.pendingAction().toString(), facts.at("/binding/pendingAction").textValue());
            assertTrue(facts.path("scope").asText().contains("no create/truncate/write"));
            assertThrows(IllegalArgumentException.class, () -> DeploymentAgentPublicationRecoveryDiagnosticProgram.reviewedResourceTargets(request.command(), layout, new ServerId("foreign")));
            for (int drift = 0; drift < 4; drift++) {
                try (var changed = new CommandRequest(request.command().script() + (drift == 0 ? " extra" : ""),
                    (program.source() + (drift == 1 ? "\n" : "")).getBytes(StandardCharsets.UTF_8),
                    drift == 2 ? Duration.ofSeconds(31) : request.command().timeout(), drift == 3 ? 16385 : 16384)) {
                    assertThrows(IllegalArgumentException.class, () -> DeploymentAgentPublicationRecoveryDiagnosticProgram.reviewFacts(changed, layout));
                }
            }
        }
    }

    @Test
    void boundedDiagnosticReceiptRejectsReplayAndNeverCompletesUnknownChecks() throws Exception {
        try (var request = program.request(binding, UUID.randomUUID())) {
            var body = receipt(request);
            assertEquals(body.toString(), program.parse(request, successful("PUBLICATION_RECOVERY_DIAGNOSTIC=" + body + "\n", "", false)).canonicalJson());
            assertEquals(body.toString(), program.validateReceipt(request, body.toString()).canonicalJson());
            for (String drift : List.of("extra", "missing", "task", "intent", "layout", "action", "source", "check", "status", "reason", "type", "unknown", "order", "stable")) {
                var changed = body.deepCopy();
                switch (drift) {
                    case "extra" -> changed.put("success", true);
                    case "missing" -> changed.remove("stable");
                    case "task" -> ((ObjectNode) changed.path("binding")).put("task", UUID.randomUUID().toString());
                    case "intent" -> ((ObjectNode) changed.path("binding")).put("pendingIntentEvent", 272);
                    case "layout" -> ((ObjectNode) changed.path("binding")).put("layoutSha256", "a".repeat(64));
                    case "action" -> changed.put("action", UUID.randomUUID().toString());
                    case "source" -> changed.put("sourceVersion", "a".repeat(64));
                    case "check" -> ((ObjectNode) changed.path("checks").get(0)).put("id", "ARBITRARY_COMMAND");
                    case "status" -> ((ObjectNode) changed.path("checks").get(0)).put("status", "SAFE_TO_RETRY");
                    case "reason" -> ((ObjectNode) changed.path("checks").get(0)).put("reason", "raw secret");
                    case "type" -> ((ObjectNode) changed.path("checks").get(0)).put("number", true);
                    case "unknown" -> ((ObjectNode) changed.path("checks").get(23)).put("status", "PASS").put("reason", "MATCH").put("flag", true);
                    case "order" -> ((ObjectNode) changed.path("checks").get(1)).put("id", "ENVIRONMENT");
                    case "stable" -> changed.put("stable", false);
                    default -> throw new AssertionError(drift);
                }
                assertThrows(IllegalArgumentException.class, () -> program.validateReceipt(request, changed.toString()), drift);
            }
            assertThrows(IllegalArgumentException.class, () -> program.validateReceipt(request, body.toString().replaceFirst("\\{", "{\"stable\":true,")));
            String frame = "PUBLICATION_RECOVERY_DIAGNOSTIC=" + body + "\n";
            for (String output : List.of(frame + frame, "untrusted\n" + frame, frame + " ", frame.replace("\n", "\r\n")))
                assertThrows(IllegalArgumentException.class, () -> program.parse(request, successful(output, "", false)));
            assertThrows(IllegalArgumentException.class, () -> program.parse(request, successful(frame, "unconfirmed", false)));
            assertThrows(IllegalArgumentException.class, () -> program.parse(request, successful(frame, "", true)));
            try (var fresh = program.request(binding, UUID.randomUUID())) {
                assertThrows(IllegalArgumentException.class, () -> program.validateReceipt(fresh, body.toString()));
            }
        }
    }

    @Test
    void originalPythonDiagnosisStopsOnBusyAndMarksCurrentDriftWithoutMutationCalls() throws Exception {
        // Executes the original diagnose function with immutable boundary stubs only; not Linux syscall/systemd proof.
        String python = System.getProperty("managed.test.python", "python");
        var process = new ProcessBuilder(python, "-I", "-S", "-c", PYTHON_FIXTURE).redirectErrorStream(true).start();
        try {
            try (var input = process.getOutputStream()) { input.write(program.source().getBytes(StandardCharsets.UTF_8)); }
            assertTrue(process.waitFor(20, TimeUnit.SECONDS), "Owned pure Python fixture must terminate");
            String output = new String(process.getInputStream().readNBytes(16385), StandardCharsets.UTF_8);
            assertEquals(0, process.exitValue(), output);
            assertEquals("READONLY_DIAGNOSTIC_FIXTURE=PASS\n", output.replace("\r\n", "\n"));
        } finally { if (process.isAlive()) { process.destroyForcibly(); assertTrue(process.waitFor(5, TimeUnit.SECONDS)); } }
    }

    private ObjectNode receipt(DeploymentAgentPublicationRecoveryDiagnosticProgram.Request request) {
        var facts = JSON.valueToTree(DeploymentAgentPublicationRecoveryDiagnosticProgram.reviewFacts(request.command(), layout));
        var node = JSON.createObjectNode().put("format", DeploymentAgentPublicationRecoveryDiagnosticProgram.FORMAT)
            .put("sourceVersion", program.sourceVersion()).put("action", request.action().toString());
        node.set("binding", facts.path("binding"));
        var checks = node.putArray("checks");
        for (String id : DeploymentAgentPublicationRecoveryDiagnosticProgram.CHECK_IDS) {
            boolean stability = id.equals("STABILITY"), unsupported = Set.of("FULL_PARAMETER_VALIDATION", "UNIT_RENDER_MATCH", "MANAGED_DATA_LINKS").contains(id);
            var check = checks.addObject().put("id", id).put("status", stability ? "PASS" : "UNKNOWN")
                .put("reason", stability ? "MATCH" : unsupported ? "NOT_IMPLEMENTED" : "NOT_CHECKED");
            check.putNull("number"); if (stability) check.put("flag", true); else check.putNull("flag"); check.putNull("sha256");
        }
        return node.put("stable", true);
    }

    private static OperationResult<CommandResult> successful(String stdout, String stderr, boolean truncated) {
        return new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(new CommandResult(Optional.of(0), stdout, stderr, truncated)), Optional.empty());
    }

    private static final String PYTHON_FIXTURE = """
        import ast,base64,json,os,stat,sys,types
        source=sys.stdin.read()
        tree=ast.parse(source)
        forbidden={'write','pwrite','truncate','ftruncate','mkdir','makedirs','unlink','remove','rmdir','rename','replace','chmod','chown','symlink'}
        for n in ast.walk(tree):
            if isinstance(n,ast.Call) and isinstance(n.func,ast.Attribute) and isinstance(n.func.value,ast.Name) and n.func.value.id=='os':
                assert n.func.attr not in forbidden
                if n.func.attr=='open':
                    names={x.attr for x in ast.walk(n.args[1]) if isinstance(x,ast.Attribute)}
                    assert 'O_RDONLY' in names and 'O_NOFOLLOW' in names and 'O_NOATIME' in names
                    assert not names & {'O_CREAT','O_TRUNC','O_WRONLY','O_RDWR'}
        fake=types.ModuleType('fcntl');fake.LOCK_EX=2;fake.LOCK_NB=4
        mode={'busy':False,'drift':False,'owner':False,'current':False,'closed':0,'queries':0,'locks':0}
        def flock(fd,flags):
            assert flags==6;mode['locks']+=1
            if mode['busy']:raise BlockingIOError()
        fake.flock=flock;sys.modules['fcntl']=fake
        ns={'__name__':'readonly_fixture'};exec(compile(source,'original-diagnostic','exec'),ns)
        original_fstat=os.fstat;original_uid=getattr(os,'getuid',None);original_gid=getattr(os,'getgid',None)
        os.getuid=lambda:0;os.getgid=lambda:0
        os.fstat=lambda fd:types.SimpleNamespace(st_mode=stat.S_IFDIR|0o700 if fd.endswith('/backend') else stat.S_IFREG|0o600)
        release='d'*64;owner='c'*64;task='072bc074-4bce-4c14-b425-f2ff965c7d40'
        class Probe:
            def directory(self,path):return path
            def read(self,path,maximum=131072):
                if path.endswith('/.deployer'):body=b'root\\n'
                elif path.endswith('/.agent-task'):body=task.encode()+b'\\n'
                elif path.endswith('/maintenance'):body=('deployment-'+release).encode()+b'\\n'
                elif path.endswith('/.windowstolinux-owner'):body=(('e'*64 if mode['owner'] else owner)+'\\n').encode()
                elif path.endswith('/.windowstolinux-deployment-parameters'):body=b'identity-v2\\nSYSTEMD_STATIC\\n'+base64.b64encode(b'application-v2\\0DAEMON\\0')+b'\\n'
                else:body=b''
                return body,path
            def absent(self,path):return True
            def names_in(self,path):return [release] if path.endswith('/releases') else []
            def raw_link(self,path):return '/apps/backend/releases/'+('e'*64 if mode['current'] else release)
            def stable(self):return not mode['drift']
            def close(self):mode['closed']+=1
        ns['Probe']=Probe
        def show(unit,properties):
            mode['queries']+=1
            return dict(ActiveState='inactive',MainPID='0',ControlGroup='') if 'MainPID' in properties else dict(FragmentPath='/etc/systemd/system/windowstolinux-backend.service',DropInPaths='')
        ns['show']=show
        envelope={'binding':{'unit':'backend','candidate':'backend-0123456789abcdef','releaseSha256':release,'ownerSha256':owner,'task':task},'layout':{'remote':{'applicationsRoot':'/apps','stateRoot':'/state'}}}
        try:
            checks,stable=ns['diagnose'](envelope);assert stable and checks['CURRENT_RAW_LINK']['status']=='PASS'
            assert checks['UNIT_RENDER_MATCH']['status']=='UNKNOWN' and checks['FULL_PARAMETER_VALIDATION']['status']=='UNKNOWN'
            mode['busy']=True;queries=mode['queries'];checks,stable=ns['diagnose'](envelope)
            assert not stable and checks['LOCK_ACQUIRED']['reason']=='BUSY' and mode['queries']==queries
            mode['busy']=False;mode['current']=True;checks,stable=ns['diagnose'](envelope)
            assert stable and checks['CURRENT_RAW_LINK']['status']=='FAIL'
            mode['current']=False;mode['owner']=True;checks,stable=ns['diagnose'](envelope)
            assert stable and checks['RELEASE_OWNER']['status']=='FAIL'
            mode['owner']=False;mode['drift']=True;checks,stable=ns['diagnose'](envelope)
            assert not stable and all(x['status']!='PASS' for x in checks.values()) and mode['closed']==5
        finally:
            os.fstat=original_fstat
            if original_uid is not None:os.getuid=original_uid
            if original_gid is not None:os.getgid=original_gid
        print('READONLY_DIAGNOSTIC_FIXTURE=PASS')
        """;
}
