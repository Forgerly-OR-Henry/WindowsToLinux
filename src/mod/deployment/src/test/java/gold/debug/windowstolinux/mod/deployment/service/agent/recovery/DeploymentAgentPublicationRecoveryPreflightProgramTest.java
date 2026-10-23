package gold.debug.windowstolinux.mod.deployment.service.agent.recovery;

import static org.junit.jupiter.api.Assertions.*;

import java.io.DataInputStream;
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

class DeploymentAgentPublicationRecoveryPreflightProgramTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String EXEC = "exec /usr/bin/python3 -I -S - \"$wtl_argument\" 'SDK_DEV_NULL_SENTINELS_REMOVED_V2'\n";
    private final DeploymentRemoteLayout layout = DeploymentRemoteLayout.original();
    private final DeploymentAgentPublicationRecoveryPreflightProgram program = new DeploymentAgentPublicationRecoveryPreflightProgram(layout);
    private final DeploymentAgentPublicationRecoveryDiagnosticProgram.Binding original = new DeploymentAgentPublicationRecoveryDiagnosticProgram.Binding(
        "original-host", UUID.fromString("072bc074-4bce-4c14-b425-f2ff965c7d40"), UUID.fromString("45e3a9a4-a6b8-403a-89f5-605e11eeeca0"),
        UUID.fromString("d5ace0b0-25c5-4fa9-a8fa-dc1839cd5b24"), 271, "a".repeat(64), "b".repeat(64), "backend", "c".repeat(64),
        "backend-0123456789abcdef", "d".repeat(64), layout.digest());
    private final DeploymentAgentPublicationRecoveryPreflightProgram.PriorDiagnostic prior = new DeploymentAgentPublicationRecoveryPreflightProgram.PriorDiagnostic(
        UUID.fromString("9fdc458a-dd49-436d-b606-ec1a1978f180"), 321, 328, "e".repeat(64), "f".repeat(64), 272, "1".repeat(64));
    private final DeploymentAgentPublicationRecoveryPreflightProgram.Binding binding = new DeploymentAgentPublicationRecoveryPreflightProgram.Binding(original, prior);

    @Test
    void independentV2BindsPriorEvidenceAndPreservesV1Bytes() throws Exception {
        var v1 = new DeploymentAgentPublicationRecoveryDiagnosticProgram(layout);
        assertEquals("8732e0dfffd89f150d545f311435cd45a92f75e54d880120b94d14f8c7a3d4b9", v1.sourceVersion());
        try (var old = v1.request(original, UUID.randomUUID()); var request = program.request(binding, UUID.randomUUID())) {
            assertTrue(old.command().script().startsWith("/usr/bin/python3 -I -S - "));
            assertEquals(v1.source(), new String(old.command().stdin(), StandardCharsets.UTF_8));
            assertEquals(program.source(), new String(request.command().stdin(), StandardCharsets.UTF_8));
            assertFalse(request.command().script().contains("/proc/self/environ"));
            var facts = JSON.valueToTree(DeploymentAgentPublicationRecoveryPreflightProgram.reviewFacts(request.command(), layout));
            assertEquals("commandEvidence.completeExecutableInput", facts.path("executableInputReference").asText());
            assertFalse(facts.has("completeReadonlySource"));
            assertEquals(321, facts.at("/binding/prior/requestEvent").intValue());
            assertEquals(request.resourceTargets(), DeploymentAgentPublicationRecoveryPreflightProgram.reviewedResourceTargets(request.command(), layout, new ServerId(original.server())));
            assertThrows(IllegalArgumentException.class, () -> DeploymentAgentPublicationRecoveryPreflightProgram.reviewedResourceTargets(request.command(), layout, new ServerId("foreign")));
            for (int drift = 0; drift < 4; drift++) {
                try (var changed = new CommandRequest(request.command().script() + (drift == 0 ? "\ntrue" : ""),
                    (program.source() + (drift == 1 ? "\n" : "")).getBytes(StandardCharsets.UTF_8),
                    drift == 2 ? Duration.ofSeconds(31) : request.command().timeout(), drift == 3 ? 16385 : 16384)) {
                    assertThrows(IllegalArgumentException.class, () -> DeploymentAgentPublicationRecoveryPreflightProgram.reviewFacts(changed, layout));
                }
            }
        }
        assertThrows(IllegalArgumentException.class, () -> new DeploymentAgentPublicationRecoveryPreflightProgram.PriorDiagnostic(prior.action(), 321, 320, "e".repeat(64), "f".repeat(64), 272, "1".repeat(64)));
    }

    @Test
    void strictV2ReceiptRetainsIntrinsicFactsWithoutInventingDriftOrPermission() throws Exception {
        try (var request = program.request(binding, UUID.randomUUID())) {
            var body = safeReceipt(request, false);
            assertEquals(body.toString(), program.validateReceipt(request, body.toString()).canonicalJson());
            for (String change : List.of("prior", "action", "source", "extra", "missing", "environment", "started", "check", "unknown", "stable", "type")) {
                var changed = body.deepCopy();
                switch (change) {
                    case "prior" -> ((ObjectNode) changed.at("/binding/prior")).put("receiptSha256", "2".repeat(64));
                    case "action" -> changed.put("action", UUID.randomUUID().toString());
                    case "source" -> changed.put("sourceVersion", "2".repeat(64));
                    case "extra" -> changed.put("permission", "RETRY");
                    case "missing" -> changed.remove("environment");
                    case "environment" -> ((ObjectNode) changed.path("environment")).put("bashEnv", "SECRET_VALUE");
                    case "started" -> ((ObjectNode) changed.path("environment")).put("pythonStarted", false);
                    case "check" -> ((ObjectNode) changed.path("checks").get(2)).put("status", "PASS").put("reason", "MATCH").put("flag", true);
                    case "unknown" -> ((ObjectNode) changed.path("checks").get(23)).put("status", "PASS").put("reason", "MATCH").put("flag", true);
                    case "stable" -> changed.put("stable", true);
                    case "type" -> ((ObjectNode) changed.path("checks").get(0)).put("number", true);
                    default -> throw new AssertionError(change);
                }
                assertThrows(IllegalArgumentException.class, () -> program.validateReceipt(request, changed.toString()), change);
            }
            assertThrows(IllegalArgumentException.class, () -> program.validateReceipt(request, body.toString().replaceFirst("\\{", "{\"stable\":false,")));
            String frame = "PUBLICATION_RECOVERY_PREFLIGHT=" + body + "\n";
            assertEquals(body.toString(), program.parse(request, successful(frame, "", false)).canonicalJson());
            for (String output : List.of(frame + frame, "untrusted\n" + frame, frame.replace("\n", "\r\n")))
                assertThrows(IllegalArgumentException.class, () -> program.parse(request, successful(output, "", false)));
            assertThrows(IllegalArgumentException.class, () -> program.parse(request, successful(frame, "unconfirmed", false)));
            assertThrows(IllegalArgumentException.class, () -> program.parse(request, successful(frame, "", true)));
            try (var fresh = program.request(binding, UUID.randomUUID())) { assertThrows(IllegalArgumentException.class, () -> program.validateReceipt(fresh, body.toString())); }
        }
    }

    @Test
    void actualSdkSentinelsReachV2BoundaryAndUnsafeClassesNeverEnterGuard() throws Exception {
        String sdk = actualSdkCommandConstant();
        var scenarios = new LinkedHashMap<String, String>();
        scenarios.put("SDK", "");
        scenarios.put("BASH_ABSENT", "builtin unset BASH_ENV\n");
        scenarios.put("BASH_EMPTY", "BASH_ENV=''; builtin export BASH_ENV\n");
        scenarios.put("BASH_OTHER", "BASH_ENV='TEST_SECRET_SENTINEL'; builtin export BASH_ENV\n");
        scenarios.put("ENV_ABSENT", "builtin unset ENV\n");
        scenarios.put("ENV_EMPTY", "ENV=''; builtin export ENV\n");
        scenarios.put("ENV_OTHER", "ENV='TEST_SECRET_SENTINEL'; builtin export ENV\n");
        scenarios.put("LD_PRELOAD", "LD_PRELOAD=''; builtin export LD_PRELOAD\n");
        scenarios.put("LD_LIBRARY_PATH", "LD_LIBRARY_PATH='TEST_SECRET_SENTINEL'; builtin export LD_LIBRARY_PATH\n");
        scenarios.put("FUNCTION", "BASH_FUNC_TEST='TEST_SECRET_SENTINEL'; builtin export BASH_FUNC_TEST\n");
        scenarios.put("LIMIT", "WTL_PREFLIGHT_LARGE='" + "x".repeat(9000) + "'; builtin export WTL_PREFLIGHT_LARGE\n");
        for (var scenario : scenarios.entrySet()) try (var request = program.request(binding, UUID.randomUUID())) {
            String output = throughSdkWithExplicitLocalPythonBridge(sdk, request, scenario.getValue(), !Set.of("SDK", "FUNCTION", "LIMIT").contains(scenario.getKey()));
            var receipt = JSON.readTree(program.parse(request, successful(output, "", false)).canonicalJson());
            assertFalse(output.contains("TEST_SECRET_SENTINEL"));
            var environment = receipt.path("environment");
            if (scenario.getKey().equals("SDK")) {
                assertEquals("GUARD_CHECKS", receipt.path("stage").asText());
                assertTrue(environment.path("pythonStarted").booleanValue());
                assertEquals("DEV_NULL", environment.path("bashEnv").asText());
                assertTrue(environment.path("sanitizedSentinels").booleanValue());
            } else if (Set.of("FUNCTION", "LIMIT").contains(scenario.getKey())) {
                assertEquals("PYTHON_ENVIRONMENT_REJECTED", receipt.path("stage").asText());
                assertTrue(environment.path("pythonStarted").booleanValue());
                assertEquals("UNKNOWN", receipt.path("checks").get(1).path("status").asText());
            } else {
                assertEquals("SHELL_ENVIRONMENT_REJECTED", receipt.path("stage").asText());
                assertFalse(environment.path("pythonStarted").booleanValue());
                if (scenario.getKey().endsWith("ABSENT")) assertEquals("ABSENT", environment.path(scenario.getKey().startsWith("BASH") ? "bashEnv" : "env").asText());
                if (scenario.getKey().endsWith("EMPTY")) assertEquals("EMPTY", environment.path(scenario.getKey().startsWith("BASH") ? "bashEnv" : "env").asText());
                if (scenario.getKey().endsWith("OTHER")) assertEquals("OTHER", environment.path(scenario.getKey().startsWith("BASH") ? "bashEnv" : "env").asText());
            }
        }
    }

    @Test
    void unperformedOriginalGuardPreservesMeasuredEnvironmentAndRootFacts() throws Exception {
        String fixture = """
            import sys,types,os
            source=sys.stdin.read();sys.modules['fcntl']=types.ModuleType('fcntl')
            ns={'__name__':'preflight_fixture'};exec(compile(source,'original-preflight','exec'),ns)
            os.environ={};os.getuid=lambda:0;os.getgid=lambda:0
            closed=[]
            class Probe:
                def directory(self,path):raise ns['Uncertain']('MISSING')
                def close(self):closed.append(True)
            ns['Probe']=Probe
            envelope={'binding':{'original':{'unit':'backend','candidate':'backend-0123456789abcdef','releaseSha256':'d'*64}},'layout':{'remote':{'applicationsRoot':'/apps','stateRoot':'/state'}}}
            checks,stable=ns['diagnose'](envelope)
            assert not stable and checks['ENVIRONMENT']['status']=='PASS' and checks['ROOT_IDENTITY']['status']=='PASS'
            assert checks['STABILITY']['reason']=='NOT_CHECKED' and closed==[True]
            assert all(v['reason']!='DRIFT' for v in checks.values())
            print('INTRINSIC_FACTS_PRESERVED')
            """;
        assertEquals("INTRINSIC_FACTS_PRESERVED\n", runPython(fixture, program.source()));
    }

    private ObjectNode safeReceipt(DeploymentAgentPublicationRecoveryPreflightProgram.Request request, boolean stable) {
        var facts = JSON.valueToTree(DeploymentAgentPublicationRecoveryPreflightProgram.reviewFacts(request.command(), layout));
        var node = JSON.createObjectNode().put("format", DeploymentAgentPublicationRecoveryPreflightProgram.FORMAT)
            .put("sourceVersion", program.sourceVersion()).put("action", request.action().toString()); node.set("binding", facts.path("binding"));
        node.put("stage", "GUARD_CHECKS");
        node.set("environment", JSON.createObjectNode().put("bashEnv", "DEV_NULL").put("env", "DEV_NULL").put("ldPreload", "ABSENT")
            .put("ldLibraryPath", "ABSENT").put("exportedFunctions", "ABSENT").put("snapshot", "COMPLETE").put("sanitizedSentinels", true).put("pythonStarted", true));
        var checks = node.putArray("checks");
        for (String id : DeploymentAgentPublicationRecoveryPreflightProgram.CHECK_IDS) {
            boolean intrinsic = Set.of("ENVIRONMENT", "ROOT_IDENTITY").contains(id), pass = intrinsic || stable && id.equals("STABILITY");
            var item = checks.addObject().put("id", id).put("status", pass ? "PASS" : "UNKNOWN")
                .put("reason", pass ? "MATCH" : Set.of("FULL_PARAMETER_VALIDATION", "UNIT_RENDER_MATCH", "MANAGED_DATA_LINKS").contains(id) ? "NOT_IMPLEMENTED" : "NOT_CHECKED");
            item.putNull("number"); if (pass) item.put("flag", true); else item.putNull("flag"); item.putNull("sha256");
        }
        return node.put("stable", stable);
    }

    private static OperationResult<CommandResult> successful(String stdout, String stderr, boolean truncated) {
        return new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(new CommandResult(Optional.of(0), stdout, stderr, truncated)), Optional.empty());
    }

    private String throughSdkWithExplicitLocalPythonBridge(String sdk, DeploymentAgentPublicationRecoveryPreflightProgram.Request request, String scenario, boolean expectedShellRejection) throws Exception {
        // Only the final Python path/import/guard bridge is local. Real SDK command, frame reader, two -p shells and prelude are used.
        String python = System.getProperty("managed.test.python", "python");
        String bridge = "exec " + quote(python) + " -I -S -c " + quote(PYTHON_BOUNDARY_BRIDGE) + " \"$wtl_argument\" 'SDK_DEV_NULL_SENTINELS_REMOVED_V2'\n";
        assertEquals(1, request.command().script().split(java.util.regex.Pattern.quote(EXEC), -1).length - 1);
        String script = scenario + request.command().script().replace(EXEC, bridge);
        byte[] bytes = script.getBytes(StandardCharsets.UTF_8);
        var frame = new java.io.ByteArrayOutputStream();
        frame.write((bytes.length + "\n").getBytes(StandardCharsets.US_ASCII)); frame.write(bytes); frame.write(request.command().stdin());
        String bash = System.getProperty("managed.test.bash", "/bin/bash");
        // Pass the unchanged SDK command through an owned file: Windows argv conversion loses nested shell quotes.
        var sdkFile = java.nio.file.Files.createTempFile("wtl-sdk-preflight-263-", ".sh");
        try {
            java.nio.file.Files.writeString(sdkFile, sdk + "\n", StandardCharsets.UTF_8);
            var builder = new ProcessBuilder(bash, "--noprofile", "--norc", "-p", sdkFile.toString().replace('\\', '/')).redirectErrorStream(true);
            builder.environment().keySet().removeIf(k -> Set.of("BASH_ENV", "ENV", "LD_PRELOAD", "LD_LIBRARY_PATH").contains(k) || k.startsWith("BASH_FUNC_"));
            var process = builder.start();
            try {
                var observed = exchange(process, frame.toByteArray());
                if (observed.inputFailure() != null) {
                    assertTrue(expectedShellRejection, "Unexpected incomplete input delivery");
                    var receipt = JSON.readTree(program.parse(request, successful(observed.output(), "", false)).canonicalJson());
                    assertEquals("SHELL_ENVIRONMENT_REJECTED", receipt.path("stage").asText(), "Broken pipe only allowed after an actual validated early shell receipt");
                }
                return observed.output();
            } finally { if (process.isAlive()) { process.destroyForcibly(); assertTrue(process.waitFor(5, TimeUnit.SECONDS)); } }
        } finally { java.nio.file.Files.deleteIfExists(sdkFile); }
    }

    private static String actualSdkCommandConstant() throws Exception {
        String path = "/gold/debug/windowstolinux/app/linux/ssh/connect/SshCommandFrame.class";
        var values = new ArrayList<String>(); var bootstraps = new ArrayList<String>();
        try (var raw = DeploymentAgentPublicationRecoveryPreflightProgramTest.class.getResourceAsStream(path)) {
            assertNotNull(raw, "Actual SDK class required; do not silently substitute a copied bootstrap");
            var input = new DataInputStream(raw); assertEquals(0xcafebabe, input.readInt()); input.readUnsignedShort(); input.readUnsignedShort();
            int count = input.readUnsignedShort();
            for (int i = 1; i < count; i++) switch (input.readUnsignedByte()) {
                case 1 -> {
                    String value = input.readUTF();
                    if (value.startsWith("exec /usr/bin/env BASH_ENV=/dev/null ENV=/dev/null LC_ALL=C /bin/bash ")) values.add(value);
                    if (value.startsWith("IFS= builtin read -r length") && value.contains("--noprofile --norc -p /dev/fd/3")) bootstraps.add(value);
                }
                case 3, 4, 9, 10, 11, 12, 17, 18 -> input.skipNBytes(4);
                case 5, 6 -> { input.skipNBytes(8); i++; }
                case 7, 8, 16, 19, 20 -> input.skipNBytes(2);
                case 15 -> input.skipNBytes(3);
                default -> throw new AssertionError("Unexpected SDK constant-pool tag");
            }
        }
        assertEquals(1, values.size()); String command = values.getFirst();
        // COMMAND initializes with BOOTSTRAP.replace: the actual class contains a concat recipe, not necessarily a folded String.
        if (command.indexOf('\u0001') >= 0) {
            assertEquals(command.indexOf('\u0001'), command.lastIndexOf('\u0001')); assertEquals(1, bootstraps.size());
            command = command.replace("\u0001", bootstraps.getFirst().replace("'", "'\"'\"'"));
        }
        assertTrue(command.contains("--noprofile --norc -p -c"));
        assertTrue(command.contains("--noprofile --norc -p /dev/fd/3")); return command;
    }

    private static String runPython(String fixture, String source) throws Exception {
        var process = new ProcessBuilder(System.getProperty("managed.test.python", "python"), "-I", "-S", "-c", fixture).redirectErrorStream(true).start();
        try {
            var observed = exchange(process, source.getBytes(StandardCharsets.UTF_8));
            assertNull(observed.inputFailure(), "Pure Python fixture must consume its source"); return observed.output();
        } finally { if (process.isAlive()) { process.destroyForcibly(); assertTrue(process.waitFor(5, TimeUnit.SECONDS)); } }
    }

    private record Exchange(String output, java.io.IOException inputFailure) { }

    private static Exchange exchange(Process process, byte[] inputBytes) throws Exception {
        // Both directions start before any wait: shell rejection need not consume the Python stdin.
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        var output = executor.submit(() -> process.getInputStream().readNBytes(16385));
        java.util.concurrent.Future<java.io.IOException> writer = executor.submit(() -> {
            try (var input = process.getOutputStream()) { input.write(inputBytes); return null; }
            catch (java.io.IOException stopped) { return stopped; }
        });
        try {
            assertTrue(process.waitFor(20, TimeUnit.SECONDS));
            String text = new String(output.get(5, TimeUnit.SECONDS), StandardCharsets.UTF_8).replace("\r\n", "\n");
            var stopped = writer.get(5, TimeUnit.SECONDS);
            assertEquals(0, process.exitValue(), text); return new Exchange(text, stopped);
        } finally {
            if (process.isAlive()) { process.destroyForcibly(); assertTrue(process.waitFor(5, TimeUnit.SECONDS)); }
            process.getInputStream().close(); process.getOutputStream().close(); output.cancel(true); writer.cancel(true); executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
            Arrays.fill(inputBytes, (byte) 0);
        }
    }

    private static String quote(String value) { return "'" + value.replace("'", "'\\''") + "'"; }

    private static final String PYTHON_BOUNDARY_BRIDGE = """
        import sys,types
        source=sys.stdin.read();sys.modules['fcntl']=types.ModuleType('fcntl')
        ns={'__name__':'explicit_local_bridge'};exec(compile(source,'original-preflight','exec'),ns)
        ns['signal'].SIGALRM=getattr(ns['signal'],'SIGALRM',14)
        ns['signal'].signal=lambda *args:None
        ns['signal'].alarm=lambda *args:0
        def guard(envelope):
            checks={}
            for key in ns['CHECKS']:
                intrinsic=key in ('ENVIRONMENT','STABILITY')
                checks[key]=dict(id=key,status='PASS' if intrinsic else 'UNKNOWN',reason='MATCH' if intrinsic else 'NOT_IMPLEMENTED' if key in ('FULL_PARAMETER_VALIDATION','UNIT_RENDER_MATCH','MANAGED_DATA_LINKS') else 'NOT_CHECKED',number=None,flag=True if intrinsic else None,sha256=None)
            return checks,True
        ns['diagnose']=guard
        ns['main']()
        """;
}
