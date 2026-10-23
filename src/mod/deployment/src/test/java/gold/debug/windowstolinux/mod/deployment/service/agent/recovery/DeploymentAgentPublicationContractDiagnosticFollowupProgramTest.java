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

class DeploymentAgentPublicationContractDiagnosticFollowupProgramTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String RES = "/gold/debug/windowstolinux/mod/deployment/service/agent/recovery/";
    private static final String OLD_VERSION = "c9c21e6e00a04dec8fd64767cee299eae47d684d88c2f5036efa9b0923cbbc36";
    private final DeploymentRemoteLayout layout = DeploymentRemoteLayout.original();
    private final DeploymentAgentPublicationContractDiagnosticFollowupProgram program = new DeploymentAgentPublicationContractDiagnosticFollowupProgram(layout);

    private DeploymentAgentPublicationContractDiagnosticFollowupProgram.Binding binding() {
        var original = new DeploymentAgentPublicationRecoveryDiagnosticProgram.Binding("host", UUID.fromString("072bc074-4bce-4c14-b425-f2ff965c7d40"), UUID.fromString("45e3a9a4-a6b8-403a-89f5-605e11eeeca0"), UUID.fromString("d5ace0b0-25c5-4fa9-a8fa-dc1839cd5b24"), 271, "a".repeat(64), "b".repeat(64), "backend", "c".repeat(64), "backend-0123456789abcdef", "d".repeat(64), layout.digest());
        var continuation = new DeploymentAgentPublicationContinuationProgram.Binding(original, 272, "e".repeat(64), new DeploymentAgentPublicationContinuationProgram.PriorPreflight(UUID.fromString("e3c6caa1-3a02-4a85-ad7a-7552fc73ec6c"), 353, 361, "f".repeat(64), "1".repeat(64)), new DeploymentAgentPublicationContinuationProgram.CurrentFingerprints("2".repeat(64), "3".repeat(64), "4".repeat(64), "5".repeat(64), "6".repeat(64), "7".repeat(64)));
        var diagnostic = new DeploymentAgentPublicationContractDiagnosticProgram.Binding(continuation, new DeploymentAgentPublicationContractDiagnosticProgram.PriorContinuation(UUID.fromString("cd6fee42-b595-433b-8b13-b79b5ed3ad24"), 385, 391, "8".repeat(64), "9".repeat(64), new DeploymentAgentPublicationContinuationProgram(layout).sourceVersion()), 426, "0".repeat(64));
        // 500 is a synthetic newer observation, never a claim about the live ledger.
        return new DeploymentAgentPublicationContractDiagnosticFollowupProgram.Binding(diagnostic, new DeploymentAgentPublicationContractDiagnosticFollowupProgram.PriorDiagnostic(UUID.fromString("dce2e4ed-de76-4c01-894b-99ee1b980cb7"), 449, 456, "a".repeat(64), "b".repeat(64), OLD_VERSION), 500, "c".repeat(64));
    }

    @Test void followupPreservesOldSourceIdentityAndRequiresCompletePriorBinding() throws Exception {
        var old = new DeploymentAgentPublicationContractDiagnosticProgram(layout);
        assertEquals(OLD_VERSION, old.sourceVersion());
        assertEquals("e8d2b02340de6bc2eda89063049dd6bde24779ccef5f56b2ade879462b819ed0", sha(raw("publication-contract-diagnostic.py")));
        assertEquals("efe782db9fe87f4d5ca5d108af7795f988533af41f1af00cd11877a154eae27d", sha(raw("publication-contract-diagnostic-guard.sh")));
        String oldBash = bashSource(old.source());
        String suffix = "\n" + DeploymentRemoteLayoutTemplate.render(resource("publication-contract-diagnostic-guard.sh"), layout);
        assertTrue(oldBash.endsWith(suffix)); assertEquals(oldBash.length() - suffix.length(), oldBash.indexOf(suffix));
        String expected = oldBash.substring(0, oldBash.length() - suffix.length());
        for (String fragment : List.of("20-native-targets.sh", "21-native-operations.sh")) expected += DeploymentRemoteLayoutTemplate.render(helperResource(fragment), layout);
        assertEquals(expected + suffix, bashSource(program.source()));
        assertNotEquals(old.sourceVersion(), program.sourceVersion());
        try (var r = program.request(binding(), UUID.randomUUID()); var decoded = program.decode(r.command())) {
            assertEquals(r.binding(), decoded.binding());
            assertEquals(Duration.ofSeconds(90), r.command().timeout()); assertEquals(16384, r.command().outputLimitBytes());
            assertEquals(9, r.resourceTargets().size());
            assertArrayEquals(program.source().getBytes(StandardCharsets.UTF_8), r.command().stdin());
            assertEquals(r.resourceTargets(), DeploymentAgentPublicationContractDiagnosticFollowupProgram.reviewedResourceTargets(r.command(), layout, new ServerId("host")));
            assertEquals("commandEvidence.completeExecutableInput", DeploymentAgentPublicationContractDiagnosticFollowupProgram.reviewFacts(r.command(), layout).get("executableInputReference"));
            assertTrue(JSON.readTree(JSON.writeValueAsBytes(r.binding())).path("observationEnd").isInt());
            try (var bad = new CommandRequest(r.command().script() + "\n", r.command().stdin(), r.command().timeout(), r.command().outputLimitBytes())) { assertThrows(IllegalArgumentException.class, () -> program.decode(bad)); }
            try (var bad = new CommandRequest(r.command().script(), (program.source() + "\n").getBytes(StandardCharsets.UTF_8), r.command().timeout(), r.command().outputLimitBytes())) { assertThrows(IllegalArgumentException.class, () -> program.decode(bad)); }
            var receipt = safe(r, control("", null, ""));
            for (String field : List.of("format", "sourceVersion", "action", "binding", "status", "phase", "reason", "exitCode", "checks")) {
                var bad = receipt.deepCopy(); bad.remove(field); assertThrows(IllegalArgumentException.class, () -> program.validateReceipt(r, bad.toString()));
            }
            for (String field : List.of("requestDetailSha256", "receiptSha256", "sourceVersion")) {
                var bad = receipt.deepCopy(); ((ObjectNode) bad.path("binding").path("prior")).put(field, "e".repeat(64)); assertThrows(IllegalArgumentException.class, () -> program.validateReceipt(r, bad.toString()));
            }
            var bad = receipt.deepCopy(); ((ObjectNode) bad.path("binding").path("prior")).put("requestEvent", 449.5); assertThrows(IllegalArgumentException.class, () -> program.validateReceipt(r, bad.toString()));
            assertThrows(IllegalArgumentException.class, () -> program.validateReceipt(r, receipt.toString().replace("\"status\":", "\"status\":\"UNKNOWN\",\"status\":")));
            var foreign = receipt.deepCopy(); foreign.put("raw", "private-value"); assertThrows(IllegalArgumentException.class, () -> program.validateReceipt(r, foreign.toString()));
            var frame = DeploymentAgentPublicationContractDiagnosticFollowupProgram.FRAME + receipt + "\n";
            assertEquals(receipt.toString(), program.parse(r, actual(frame, "", 0, false)).canonicalJson());
            for (var invalid : List.of(actual(frame, "private-value", 0, false), actual(frame, "", 1, false), actual(frame, "", 0, true), actual(frame + frame, "", 0, false))) assertThrows(IllegalArgumentException.class, () -> program.parse(r, invalid));
            try (var other = program.request(binding(), UUID.randomUUID())) { assertThrows(IllegalArgumentException.class, () -> program.validateReceipt(other, receipt.toString())); }
            try (var prior = old.request(binding().diagnostic(), UUID.randomUUID())) { assertThrows(IllegalArgumentException.class, () -> old.validateReceipt(prior, receipt.toString())); }
        }
        var b = binding(); var c = b.diagnostic().continuation();
        for (UUID prior : List.of(c.original().pendingAction(), c.preflight().action(), b.diagnostic().prior().action(), b.prior().action())) assertThrows(IllegalArgumentException.class, () -> program.request(b, prior));
        var wrong = new DeploymentAgentPublicationContractDiagnosticFollowupProgram.PriorDiagnostic(b.prior().action(), 449, 456, "a".repeat(64), "b".repeat(64), "e".repeat(64));
        assertThrows(IllegalArgumentException.class, () -> program.request(new DeploymentAgentPublicationContractDiagnosticFollowupProgram.Binding(b.diagnostic(), wrong, 500, "c".repeat(64)), UUID.randomUUID()));
        var nested = b.diagnostic().prior();
        var wrongNested = new DeploymentAgentPublicationContractDiagnosticProgram.PriorContinuation(nested.action(), nested.requestEvent(), nested.endEvent(), nested.requestDetailSha256(), nested.receiptSha256(), "e".repeat(64));
        var impossibleDiagnostic = new DeploymentAgentPublicationContractDiagnosticProgram.Binding(c, wrongNested, b.diagnostic().observationEnd(), b.diagnostic().observationSha256());
        var impossible = new DeploymentAgentPublicationContractDiagnosticFollowupProgram.Binding(impossibleDiagnostic, b.prior(), b.observationEnd(), b.observationSha256());
        assertThrows(IllegalArgumentException.class, () -> old.request(impossibleDiagnostic, b.prior().action()));
        assertThrows(IllegalArgumentException.class, () -> program.request(impossible, UUID.randomUUID()));
        assertThrows(IllegalArgumentException.class, () -> new DeploymentAgentPublicationContractDiagnosticFollowupProgram.Binding(b.diagnostic(), b.prior(), 456, "c".repeat(64)));
    }

    @Test void followupClassifiesBoundedControlReasonsWithoutLeakingSentinels() throws Exception {
        String begin = frame("BEGIN", "ARGUMENTS", 0, 2);
        var scenarios = List.of(new ControlCase("", -9, "", "EMPTY_CONTROL"), new ControlCase(begin, 1, "private-value", "STDOUT_PRESENT"),
            new ControlCase("private-value\n", 255, "", "FRAME_SHAPE"), new ControlCase(begin + frame("PHASE", "LOCKS", 0, 2), 1, "", "FRAME_ORDER"),
            new ControlCase(begin, 1, "", "NO_ROOT_EXIT"), new ControlCase(begin + frame("EXIT", "ARGUMENTS", 1, 2), 2, "", "EXIT_MISMATCH"),
            new ControlCase(begin.stripTrailing(), null, "", "INCOMPLETE_CONTROL"), new ControlCase("x".repeat(8193), -64, "", "LIMIT"));
        try (var r = program.request(binding(), UUID.randomUUID())) {
            for (var scenario : scenarios) {
                var tuple = control(scenario.text(), scenario.exit(), scenario.output()); assertEquals(scenario.reason(), tuple.get(2).asText());
                assertFalse(tuple.toString().contains("private-value")); assertNotChecked(tuple);
                var n = safe(r, tuple); assertEquals(n.toString(), program.validateReceipt(r, n.toString()).canonicalJson());
            }
            for (Integer code : Arrays.asList(-64, -1, 0, 255, null)) {
                var n = safe(r, control("", code, "")); if (code == null) assertTrue(n.path("exitCode").isNull()); else assertEquals(code.intValue(), n.path("exitCode").intValue());
                assertEquals(n.toString(), program.validateReceipt(r, n.toString()).canonicalJson());
            }
            var n = safe(r, control("", null, ""));
            for (int code : List.of(-65, 256)) { var bad = n.deepCopy().put("exitCode", code); assertThrows(IllegalArgumentException.class, () -> program.validateReceipt(r, bad.toString())); }
            for (String reason : List.of("UNCONFIRMED", "private-value")) { var bad = n.deepCopy().put("reason", reason); assertThrows(IllegalArgumentException.class, () -> program.validateReceipt(r, bad.toString())); }
            var bad = n.deepCopy().put("phase", "ARGUMENTS").put("reason", "UNCONFIRMED").put("exitCode", -1); assertThrows(IllegalArgumentException.class, () -> program.validateReceipt(r, bad.toString()));
            var pass = n.deepCopy(); ((ObjectNode) pass.path("checks").get(0)).put("status", "PASS").put("reason", "MATCH").put("exitCode", 0); assertThrows(IllegalArgumentException.class, () -> program.validateReceipt(r, pass.toString()));
        }
    }

    @Test void followupPreservesNounsetPhaseAndPriorPassWithActualGuardHeader(@TempDir Path temp) throws Exception {
        String bash = System.getProperty("managed.test.bash", "/bin/bash"); org.junit.jupiter.api.Assumptions.assumeTrue(Files.isExecutable(Path.of(bash)));
        // Define the actual fully assembled Python, extract only its literal Bash source,
        // and parse the whole assembly. Neither Python main/execute nor the Bash guard runs.
        String extract = "import json,sys\nv=json.load(sys.stdin)\nn={'__name__':'fixture'}\nexec(compile(v['source'],'assembled-followup','exec'),n)\nsys.stdout.write(n['BASH_SOURCE'])\n";
        Result assembled = run(List.of(System.getProperty("managed.test.python", "python"), "-I", "-S", "-c", extract), JSON.writeValueAsBytes(Map.of("source", program.source())), 524288);
        assertEquals(0, assembled.exit(), assembled.output());
        Result syntax = run(List.of(bash, "--noprofile", "--norc", "-p", "-n", "-s"), assembled.output().getBytes(StandardCharsets.UTF_8));
        assertEquals(0, syntax.exit(), syntax.output());
        // This exercises real Bash traps/header and production controls(), with fixed phases.
        // It does not run the three native contracts or Linux Python pass_fds/killpg.
        try (var r = program.request(binding(), UUID.randomUUID())) {
            for (String scenario : List.of("nounset-first", "nounset-next", "false", "reject", "pass")) {
                Path f = temp.resolve(scenario); Files.createDirectories(f); Result result = bash(headerFixture(f, scenario)); assertEquals(0, result.exit(), result.output());
                int code = Integer.parseInt(Files.readString(f.resolve("exit")).trim()); String body = Files.readString(f.resolve("control")).replace("\r\n", "\n");
                var tuple = control(body, code, Files.readString(f.resolve("stdout"))); var n = safe(r, tuple);
                assertEquals(n.toString(), program.validateReceipt(r, n.toString()).canonicalJson()); assertFalse(tuple.toString().contains("WTL_PRIVATE_SENTINEL"));
                if (scenario.startsWith("nounset")) {
                    assertEquals(1, code); assertFalse(body.contains("\tERROR\t")); assertEquals("UNKNOWN", tuple.get(0).asText());
                    int index = scenario.equals("nounset-first") ? 0 : 1; assertEquals(DeploymentAgentPublicationContractDiagnosticFollowupProgram.CHECK_IDS.get(index), tuple.get(1).asText());
                    assertEquals("UNCONFIRMED", tuple.get(4).get(index).path("reason").asText()); assertEquals(1, tuple.get(4).get(index).path("exitCode").asInt());
                    if (index == 1) assertEquals("PASS", tuple.get(4).get(0).path("status").asText());
                    var mismatch = n.deepCopy(); ((ObjectNode) mismatch.path("checks").get(index)).put("exitCode", 2); assertThrows(IllegalArgumentException.class, () -> program.validateReceipt(r, mismatch.toString()));
                } else assertEquals(scenario.equals("pass") ? "OBSERVED" : scenario.equals("reject") ? "REFUSED" : "UNKNOWN", tuple.get(0).asText());
                if (scenario.equals("nounset-next")) {
                    String noExit = body.substring(0, body.lastIndexOf("WTL_CONTRACT_CONTROL_1\tEXIT\t"));
                    for (var malformed : List.of(control(noExit, code, ""), control(body + frame("EXIT", "MANAGED_DATA_LINKS", code, 999), code, ""), control(body, code, "WTL_PRIVATE_SENTINEL"))) { assertEquals("CONTROL", malformed.get(1).asText()); assertNotChecked(malformed); program.validateReceipt(r, safe(r, malformed).toString()); }
                }
            }
            String before = through("FULL_PARAMETER_VALIDATION");
            for (String invalid : List.of(before + frame("PASS", "FULL_PARAMETER_VALIDATION", 0, 2) + frame("EXIT", "FULL_PARAMETER_VALIDATION", 1, 2), completeFrames() + frame("EXIT", "COMPLETE", 1, 2))) {
                var tuple = control(invalid, 1, ""); assertEquals("CONTROL", tuple.get(1).asText()); assertEquals("INCOMPLETE_CONTROL", tuple.get(2).asText()); assertNotChecked(tuple); program.validateReceipt(r, safe(r, tuple).toString());
            }
        }
    }

    private record ControlCase(String text, Integer exit, String output, String reason) { }
    private static String frame(String kind, String phase, int code, int pid) { return "WTL_CONTRACT_CONTROL_1\t" + kind + "\t" + phase + "\t" + code + "\t" + pid + "\n"; }
    private static String through(String last) { var s = new StringBuilder(frame("BEGIN", "ARGUMENTS", 0, 2)); for (String phase : List.of("ROOT_IDENTITY", "LOCKS", "CANDIDATE", "FINGERPRINTS", "BUILD", "SNAPSHOTS", "CURRENT", "MAINTENANCE", "FULL_PARAMETER_VALIDATION")) { s.append(frame("PHASE", phase, 0, 2)); if (phase.equals(last)) break; } return s.toString(); }
    private static String completeFrames() { return through("FULL_PARAMETER_VALIDATION") + frame("PASS", "FULL_PARAMETER_VALIDATION", 0, 2) + frame("PHASE", "MANAGED_DATA_LINKS", 0, 2) + frame("PASS", "MANAGED_DATA_LINKS", 0, 2) + frame("PHASE", "UNIT_RENDER_MATCH", 0, 2) + frame("PASS", "UNIT_RENDER_MATCH", 0, 2) + frame("PHASE", "STABILITY", 0, 2) + frame("PHASE", "COMPLETE", 0, 2); }
    private static void assertNotChecked(JsonNode tuple) { for (var check : tuple.get(4)) { assertEquals("UNKNOWN", check.path("status").asText()); assertEquals("NOT_CHECKED", check.path("reason").asText()); assertTrue(check.path("exitCode").isNull()); } }
    private static ObjectNode safe(DeploymentAgentPublicationContractDiagnosticFollowupProgram.Request r, JsonNode tuple) {
        var n = JSON.createObjectNode().put("format", DeploymentAgentPublicationContractDiagnosticFollowupProgram.FORMAT).put("sourceVersion", shaUnchecked(r.command().stdin())).put("action", r.action().toString()); n.set("binding", JSON.valueToTree(r.binding()));
        n.set("status", tuple.get(0)); n.set("phase", tuple.get(1)); n.set("reason", tuple.get(2)); n.set("exitCode", tuple.get(3)); n.set("checks", tuple.get(4)); return n;
    }
    private static OperationResult<CommandResult> actual(String out, String err, int code, boolean truncated) { return new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(new CommandResult(Optional.of(code), out, err, truncated)), Optional.empty()); }
    private static String headerFixture(Path f, String scenario) throws Exception {
        String guard = resource("publication-contract-diagnostic-guard.sh"), marker = "task=\"$1\" action="; int cut = guard.indexOf(marker); assertTrue(cut > 0); assertEquals(cut, guard.lastIndexOf(marker));
        String q = quote(f.toString().replace('\\', '/'));
        var s = new StringBuilder("fixture=" + q + "\nexec {fixture_control_fd}> >(command cat >\"$fixture/control\")\nfixture_reader=$!\n(\nset -- x x x x x x x x x x x x x x \"$fixture_control_fd\"\n").append(guard, 0, cut);
        s.append("\nfor fixture_phase in ROOT_IDENTITY LOCKS CANDIDATE FINGERPRINTS BUILD SNAPSHOTS CURRENT MAINTENANCE; do contract_next \"$fixture_phase\"; done\ncontract_next FULL_PARAMETER_VALIDATION\n");
        if (scenario.equals("nounset-next")) s.append("contract_pass\ncontract_next MANAGED_DATA_LINKS\n");
        if (scenario.startsWith("nounset")) s.append("builtin unset WTL_PRIVATE_SENTINEL\n: \"${WTL_PRIVATE_SENTINEL}\"\n");
        else if (scenario.equals("false")) s.append("false\n");
        else if (scenario.equals("reject")) s.append("reject runtime-arguments\n");
        else s.append("contract_pass\ncontract_next MANAGED_DATA_LINKS\ncontract_pass\ncontract_next UNIT_RENDER_MATCH\ncontract_pass\ncontract_next STABILITY\ncontract_next COMPLETE\n");
        return s.append(") >\"$fixture/stdout\" 2>\"$fixture/stderr\"\nfixture_exit=$?\nprintf '%s' \"$fixture_exit\" >\"$fixture/exit\"\nexec {fixture_control_fd}>&-\nwait \"$fixture_reader\"\n").toString();
    }
    private static JsonNode control(String text, Integer exit, String output) throws Exception {
        String fixture = "import json,sys\nv=json.load(sys.stdin)\nn={'__name__':'fixture'}\nexec(compile(v['source'],'fixed-followup','exec'),n)\nprint(json.dumps(n['controls'](v['text'].encode(),v['exit'],v['output'].encode())))\n";
        var input = new LinkedHashMap<String,Object>(); input.put("source", resource("publication-contract-diagnostic-followup.py")); input.put("text", text); input.put("exit", exit); input.put("output", output);
        Result result = run(List.of(System.getProperty("managed.test.python", "python"), "-I", "-S", "-c", fixture), JSON.writeValueAsBytes(input)); assertEquals(0, result.exit(), "Python fixture failed: " + result.output()); return JSON.readTree(result.output());
    }
    private record Result(int exit, String output) { }
    private static Result bash(String source) throws Exception { String path = System.getProperty("managed.test.bash", "/bin/bash"); org.junit.jupiter.api.Assumptions.assumeTrue(Files.isExecutable(Path.of(path))); return run(List.of(path, "--noprofile", "--norc", "-p", "-s"), source.getBytes(StandardCharsets.UTF_8)); }
    private static Result run(List<String> command, byte[] input) throws Exception { return run(command, input, 32768); }
    private static Result run(List<String> command, byte[] input, int limit) throws Exception {
        var p = new ProcessBuilder(command).redirectErrorStream(true).start(); var pool = Executors.newFixedThreadPool(2); var drain = pool.submit(() -> p.getInputStream().readNBytes(limit + 1));
        var writer = pool.submit(() -> { try (var stream = p.getOutputStream()) { stream.write(input); } return null; });
        try { assertTrue(p.waitFor(20, TimeUnit.SECONDS)); writer.get(5, TimeUnit.SECONDS); byte[] output = drain.get(5, TimeUnit.SECONDS); assertTrue(output.length <= limit); return new Result(p.exitValue(), new String(output, StandardCharsets.UTF_8).replace("\r\n", "\n")); }
        finally { if (p.isAlive()) { p.destroyForcibly(); assertTrue(p.waitFor(5, TimeUnit.SECONDS)); } p.getInputStream().close(); p.getOutputStream().close(); drain.cancel(true); writer.cancel(true); pool.shutdownNow(); assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS)); }
    }
    private static byte[] raw(String name) throws Exception { try (var in = DeploymentAgentPublicationContractDiagnosticFollowupProgramTest.class.getResourceAsStream(RES + name)) { return Objects.requireNonNull(in).readAllBytes(); } }
    private static String helperResource(String name) throws Exception { try (var in = DeploymentAgentPublicationContractDiagnosticFollowupProgramTest.class.getResourceAsStream("/gold/debug/windowstolinux/mod/deployment/linux/helper/database/" + name)) { return new String(Objects.requireNonNull(in).readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n"); } }
    private static String resource(String name) throws Exception { return new String(raw(name), StandardCharsets.UTF_8).replace("\r\n", "\n"); }
    private static String bashSource(String source) throws Exception {
        String marker = "BASH_SOURCE = "; int start = source.indexOf(marker); assertTrue(start >= 0); start += marker.length();
        if (source.startsWith("r'''", start)) { int end = source.indexOf("'''", start + 4); assertTrue(end > start); return source.substring(start + 4, end); }
        assertEquals('"', source.charAt(start)); int end = source.indexOf('\n', start); assertTrue(end > start);
        String encoded = source.substring(start, end); JsonNode literal = JSON.readTree(encoded); assertTrue(literal.isTextual()); assertEquals(encoded, literal.toString()); return literal.textValue();
    }
    private static String quote(String s) { return "'" + s.replace("'", "'\\''") + "'"; }
    private static String sha(byte[] b) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b)); }
    private static String shaUnchecked(byte[] b) { try { return sha(b); } catch (Exception e) { throw new IllegalStateException(e); } }
}
