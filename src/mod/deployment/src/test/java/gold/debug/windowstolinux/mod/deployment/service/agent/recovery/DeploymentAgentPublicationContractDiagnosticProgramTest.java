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
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DeploymentAgentPublicationContractDiagnosticProgramTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String RES = "/gold/debug/windowstolinux/mod/deployment/";
    private final DeploymentRemoteLayout layout = DeploymentRemoteLayout.original();
    private final DeploymentAgentPublicationContractDiagnosticProgram program = new DeploymentAgentPublicationContractDiagnosticProgram(layout);
    private DeploymentAgentPublicationContractDiagnosticProgram.Binding binding() {
        var o = new DeploymentAgentPublicationRecoveryDiagnosticProgram.Binding("host", UUID.fromString("072bc074-4bce-4c14-b425-f2ff965c7d40"), UUID.fromString("45e3a9a4-a6b8-403a-89f5-605e11eeeca0"), UUID.fromString("d5ace0b0-25c5-4fa9-a8fa-dc1839cd5b24"), 271, "a".repeat(64), "b".repeat(64), "backend", "c".repeat(64), "backend-0123456789abcdef", "d".repeat(64), layout.digest());
        var c = new DeploymentAgentPublicationContinuationProgram.Binding(o, 272, "e".repeat(64), new DeploymentAgentPublicationContinuationProgram.PriorPreflight(UUID.fromString("e3c6caa1-3a02-4a85-ad7a-7552fc73ec6c"), 353, 361, "f".repeat(64), "1".repeat(64)), new DeploymentAgentPublicationContinuationProgram.CurrentFingerprints("2".repeat(64), "3".repeat(64), "4".repeat(64), "5".repeat(64), "6".repeat(64), "7".repeat(64)));
        return new DeploymentAgentPublicationContractDiagnosticProgram.Binding(c, new DeploymentAgentPublicationContractDiagnosticProgram.PriorContinuation(UUID.fromString("cd6fee42-b595-433b-8b13-b79b5ed3ad24"), 385, 391, "8".repeat(64), "9".repeat(64), new DeploymentAgentPublicationContinuationProgram(layout).sourceVersion()), 426, "0".repeat(64));
    }
    @Test void contractDiagnosticPreservesOldBytesAndReconstructsExactReadOnlyRequest() throws Exception {
        var old = new DeploymentAgentPublicationRecoveryProgram(layout);
        assertEquals("4bce53b129711ad351344107e8f298b2588c20f0ec82f9856d9b5e3b19eb2021", old.version());
        assertEquals("477ca1395417bbf1ec024c7e427066775da8d7861f2d2ee759896d598cd59ca2", sha(old.source().getBytes(StandardCharsets.UTF_8)));
        assertEquals("46dc35ea0c0c9b2eb5d2f1d4903891d9a62a391d8c80182a01efc9efdc7d0151", sha(old.loader().getBytes(StandardCharsets.UTF_8)));
        assertEquals("0a16f4fccb224865bbcb42a4472070f54ae7f04a786f18e1bc73bda7bdb94d22", sha(old.installer().getBytes(StandardCharsets.UTF_8)));
        assertEquals("ee44f8cce327f38b8b6c519829ef8581301261eee7fbbdc77fb47ce14053f113", new DeploymentAgentPublicationContinuationProgram(layout).sourceVersion());
        try (var r = program.request(binding(), UUID.randomUUID())) {
            assertArrayEquals(program.source().getBytes(StandardCharsets.UTF_8), r.command().stdin());
            assertEquals(r.resourceTargets(), DeploymentAgentPublicationContractDiagnosticProgram.reviewedResourceTargets(r.command(), layout, new ServerId("host")));
            assertEquals("commandEvidence.completeExecutableInput", DeploymentAgentPublicationContractDiagnosticProgram.reviewFacts(r.command(), layout).get("executableInputReference"));
            try (var bad = new CommandRequest(r.command().script() + "\n", r.command().stdin(), r.command().timeout(), r.command().outputLimitBytes())) { assertThrows(IllegalArgumentException.class, () -> DeploymentAgentPublicationContractDiagnosticProgram.reviewFacts(bad, layout)); }
            try (var bad = new CommandRequest(r.command().script(), (program.source() + "\n").getBytes(StandardCharsets.UTF_8), r.command().timeout(), r.command().outputLimitBytes())) { assertThrows(IllegalArgumentException.class, () -> DeploymentAgentPublicationContractDiagnosticProgram.reviewFacts(bad, layout)); }
            var n = JSON.readTree(JSON.writeValueAsBytes(r.binding()));
            assertTrue(n.path("observationEnd").isInt()); // The small-long normalization regression.
        }
    }
    @Test void contractDiagnosticRejectsMalformedReplayAndNeverTreatsObservationAsGoal() throws Exception {
        try (var r = program.request(binding(), UUID.randomUUID())) {
            ObjectNode n = envelope(r); n.put("status", "OBSERVED").put("phase", "COMPLETE").put("reason", "MATCH").put("exitCode", 0);
            var checks = n.putArray("checks"); for (String id : DeploymentAgentPublicationContractDiagnosticProgram.CHECK_IDS) checks.addObject().put("id", id).put("status", "PASS").put("reason", "MATCH").put("exitCode", 0);
            assertEquals(n.toString(), program.validateReceipt(r, n.toString()).canonicalJson());
            String frame = DeploymentAgentPublicationContractDiagnosticProgram.FRAME + n + "\n";
            assertEquals(n.toString(), program.parse(r, actual(frame, "", 0, false)).canonicalJson());
            for (var bad : List.of(actual(frame, "secret stderr", 0, false), actual(frame, "", 1, false), actual(frame, "", 0, true), actual(frame + frame, "", 0, false))) assertThrows(IllegalArgumentException.class, () -> program.parse(r, bad));
            for (String field : List.of("format", "sourceVersion", "action", "binding", "status", "phase", "reason", "exitCode", "checks")) { var bad = n.deepCopy(); bad.remove(field); assertThrows(IllegalArgumentException.class, () -> program.validateReceipt(r, bad.toString())); }
            assertThrows(IllegalArgumentException.class, () -> program.validateReceipt(r, n.toString().replace("\"status\":", "\"foreign\":true,\"status\":")));
            assertThrows(IllegalArgumentException.class, () -> program.validateReceipt(r, n.toString().replace("\"status\":", "\"status\":\"UNKNOWN\",\"status\":")));
            for (String field : List.of("requestDetailSha256", "receiptSha256", "sourceVersion")) { var bad = n.deepCopy(); ((ObjectNode) bad.path("binding").path("prior")).put(field, "e".repeat(64)); assertThrows(IllegalArgumentException.class, () -> program.validateReceipt(r, bad.toString())); }
            var extra = n.deepCopy(); ((ObjectNode) extra.path("checks").get(0)).put("raw", "forbidden"); assertThrows(IllegalArgumentException.class, () -> program.validateReceipt(r, extra.toString()));
            var drift = n.deepCopy(); drift.put("status", "REFUSED").put("phase", "STABILITY").put("reason", "REJECTED").put("exitCode", 64); assertEquals(drift.toString(), program.validateReceipt(r, drift.toString()).canonicalJson());
            var refused = envelope(r); refused.put("status", "REFUSED").put("phase", "FULL_PARAMETER_VALIDATION").put("reason", "REJECTED").put("exitCode", 64); var stopped = refused.putArray("checks");
            for (int i = 0; i < 3; i++) { var item = stopped.addObject().put("id", DeploymentAgentPublicationContractDiagnosticProgram.CHECK_IDS.get(i)).put("status", i == 0 ? "FAIL" : "UNKNOWN").put("reason", i == 0 ? "REJECTED" : "NOT_CHECKED"); if (i == 0) item.put("exitCode", 64); else item.putNull("exitCode"); }
            assertEquals(refused.toString(), program.validateReceipt(r, refused.toString()).canonicalJson());
            ((ObjectNode) stopped.get(2)).put("status", "PASS").put("reason", "MATCH").put("exitCode", 0); assertThrows(IllegalArgumentException.class, () -> program.validateReceipt(r, refused.toString()));
            var unknown = envelope(r); unknown.put("status", "UNKNOWN").put("phase", "FULL_PARAMETER_VALIDATION").put("reason", "UNCONFIRMED").put("exitCode", 23); var uncertain = unknown.putArray("checks");
            for (int i = 0; i < 3; i++) { var item = uncertain.addObject().put("id", DeploymentAgentPublicationContractDiagnosticProgram.CHECK_IDS.get(i)).put("status", "UNKNOWN").put("reason", i == 0 ? "UNCONFIRMED" : "NOT_CHECKED"); if (i == 0) item.put("exitCode", 24); else item.putNull("exitCode"); }
            assertThrows(IllegalArgumentException.class, () -> program.validateReceipt(r, unknown.toString()));
            ((ObjectNode) uncertain.get(0)).put("exitCode", 23); assertEquals(unknown.toString(), program.validateReceipt(r, unknown.toString()).canonicalJson());
            try (var replay = program.request(binding(), UUID.randomUUID())) { assertThrows(IllegalArgumentException.class, () -> program.validateReceipt(replay, n.toString())); }
        }
        // Production control decoder, evaluated with no process/host operation: malformed data is UNKNOWN.
        for (String bad : List.of("", "unsafe\tsecret\n", "x".repeat(8193), "WTL_CONTRACT_CONTROL_1\tBEGIN\tARGUMENTS\t0\t2\n")) assertEquals("UNKNOWN", control(bad, 64, "").get(0).asText());
        assertEquals("UNKNOWN", control("WTL_CONTRACT_CONTROL_1\tBEGIN\tARGUMENTS\t0\t2\nWTL_CONTRACT_CONTROL_1\tEXIT\tARGUMENTS\t0\t2\n", 0, "unexpected stdout").get(0).asText());
    }
    @Test void nativeContractsKeepRejectOnIndependentPipeThroughRedirectPipelineAndSubstitution(@TempDir Path temp) throws Exception {
        // Runs the original three functions and original render_deployment_unit, not substitutes.
        // Native-contract cases replace file ownership only. Query-failure cases additionally
        // replace the named lowest-level stat/systemctl query in self-owned fixtures.
        for (String scenario : List.of("parameters", "managed-data", "unit-render", "nonexplicit", "unknown-reject", "dropins-query", "lock-stat-query")) {
            Path f = temp.resolve(scenario); Files.createDirectories(f); Files.writeString(f.resolve("parameters"), "invalid-nonempty-arity\n");
            String script = nativeFixture(f, scenario); Result result = bash(script);
            assertEquals(0, result.exit(), result.output());
            int exit = Integer.parseInt(Files.readString(f.resolve("exit")).trim()); assertNotEquals(0, exit);
            String frames = Files.readString(f.resolve("control")).replace("\r\n", "\n");
            var decoded = control(frames, exit, Files.readString(f.resolve("stdout")));
            if (Set.of("nonexplicit", "unknown-reject", "dropins-query", "lock-stat-query").contains(scenario)) { assertEquals("UNKNOWN", decoded.get(0).asText()); assertFalse(decoded.toString().contains("private-value"));
                if (scenario.equals("dropins-query")) { assertEquals("UNIT_RENDER_MATCH", decoded.get(1).asText()); assertEquals(17, decoded.get(3).asInt()); }
                if (scenario.equals("lock-stat-query")) { assertEquals("LOCKS", decoded.get(1).asText()); assertEquals(23, decoded.get(3).asInt()); }
            }
            else {
                assertEquals("REFUSED", decoded.get(0).asText(), frames);
                assertEquals(switch (scenario) { case "parameters" -> "FULL_PARAMETER_VALIDATION"; case "managed-data" -> "MANAGED_DATA_LINKS"; default -> "UNIT_RENDER_MATCH"; }, decoded.get(1).asText(), frames);
                assertTrue(frames.contains("\tREJECT\t"));
            }
            assertEquals("", Files.readString(f.resolve("mutations")));
            assertEquals("invalid-nonempty-arity\n", Files.readString(f.resolve("parameters")));
            assertFalse(frames.contains("private-value"));
        }
    }
    private static ObjectNode envelope(DeploymentAgentPublicationContractDiagnosticProgram.Request r) {
        var n = JSON.createObjectNode().put("format", DeploymentAgentPublicationContractDiagnosticProgram.FORMAT).put("sourceVersion", shaUnchecked(r.command().stdin())).put("action", r.action().toString()); n.set("binding", JSON.valueToTree(r.binding())); return n;
    }
    private static OperationResult<CommandResult> actual(String out, String err, int code, boolean truncated) { return new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(new CommandResult(Optional.of(code), out, err, truncated)), Optional.empty()); }
    private static String nativeFixture(Path f, String scenario) throws Exception {
        var definitions = new StringBuilder();
        for (String name : List.of("00-protocol-foundation.sh", "input/15-deployment-input.sh", "input/16-application-input.sh", "input/17-managed-content.sh", "ecosystem/35-ecosystem-dispatch.sh")) definitions.append(resource("linux/helper/" + name)).append('\n');
        String guard = resource("service/agent/recovery/publication-contract-diagnostic-guard.sh"), marker = "task=\"$1\" action=";
        int cut = guard.indexOf(marker); assertTrue(cut > 0); assertEquals(cut, guard.lastIndexOf(marker));
        String controlHeader = guard.substring(0, cut);
        String q = quote(f.toString().replace('\\', '/'));
        // Independent OS pipe from process substitution, not an alias of stdout/stderr. The
        // Linux production Python pass_fds/selectors/killpg path is a separate live boundary.
        StringBuilder script = new StringBuilder("fixture=" + q + "\n: >\"$fixture/mutations\"\nexec {fixture_control_fd}> >(command cat >\"$fixture/control\")\nfixture_reader=$!\n(\n");
        script.append(definitions).append("\nset -- x x x x x x x x x x x x x x \"$fixture_control_fd\"\n").append(controlHeader);
        script.append("\nfixture=").append(q).append("\ncurrent_application=backend\nassert_root_owned_regular() { [[ -f \"$1\" && ! -L \"$1\" ]] || reject controlled-file; }\n");
        // No mutating native entry is used. Unexpected file/lifecycle commands are sentinels.
        script.append("rm() { printf mutation >>\"$fixture/mutations\"; exit 91; }; install() { printf mutation >>\"$fixture/mutations\"; exit 91; }; systemctl() { printf mutation >>\"$fixture/mutations\"; exit 91; }\n");
        if (scenario.equals("lock-stat-query")) {
            // Execute the exact production lock identity function. Both identity stat queries
            // return no output/nonzero; the first failed assignment must stop as UNKNOWN.
            int start = guard.indexOf("contract_lock_identity() {"), end = guard.indexOf("\n}", start); assertTrue(start > 0 && end > start);
            script.append(guard, start, end + 2).append('\n');
            script.append("directory=\"$fixture\"\nassert_storage_parent() { :; }\nstat() { case \"$2\" in %h) printf 1 ;; %a) printf 600 ;; '%d:%i:%u:%g:%a:%h') return 23 ;; *) return 90 ;; esac; }\ncontract_next ROOT_IDENTITY\ncontract_next LOCKS\ncontract_lock_identity parameters 19\n");
            return finishFixture(script);
        }
        script.append("for fixture_phase in ROOT_IDENTITY LOCKS CANDIDATE FINGERPRINTS BUILD SNAPSHOTS CURRENT MAINTENANCE; do contract_next \"$fixture_phase\"; done\ncontract_next FULL_PARAMETER_VALIDATION\n");
        if (scenario.equals("parameters")) script.append("load_deployment_parameters \"$fixture/parameters\"\n");
        else {
            // Earlier PASS frames are explicit synthetic framing only; the named failing
            // contract below is real. No test claims a fully valid Linux contract chain.
            script.append("contract_pass\ncontract_next MANAGED_DATA_LINKS\n");
            if (scenario.equals("managed-data")) script.append("managed_data_bindings=()\nassert_managed_data_links \"$fixture/missing-source\"\n");
            else {
                script.append("contract_pass\ncontract_next UNIT_RENDER_MATCH\n");
                if (scenario.equals("unit-render")) script.append("contract_digest=\"$(expected_deployment_unit_digest backend invalid-nonempty-arity)\"\n");
                else if (scenario.equals("dropins-query")) {
                    // Use the actual production query/empty check, without executing the
                    // unrelated earlier unit guard fixtures. Query failure is not rejection.
                    int start = guard.indexOf("contract_dropins=\"$(systemctl "), end = guard.indexOf("\ncontract_pass", start); assertTrue(start > 0 && end > start);
                    script.append("app=backend\nsystemctl() { return 17; }\n").append(guard, start, end).append('\n');
                }
                else if (scenario.equals("nonexplicit")) script.append("printf 'private-value' >&2\nfalse\n");
                else script.append("reject private-value\n");
            }
        }
        return finishFixture(script);
    }
    private static String finishFixture(StringBuilder script) { return script.append(") >\"$fixture/stdout\" 2>\"$fixture/stderr\"\nfixture_exit=$?\nprintf '%s' \"$fixture_exit\" >\"$fixture/exit\"\nexec {fixture_control_fd}>&-\nwait \"$fixture_reader\"\n").toString(); }
    private static com.fasterxml.jackson.databind.JsonNode control(String text, int exit, String out) throws Exception {
        String source = resource("service/agent/recovery/publication-contract-diagnostic.py");
        // Carry the original resource as data, avoiding nested executable source quoting
        // across Java/Windows/-c. Loading with a non-main name calls only controls().
        String fixture = "import json,sys\nv=json.load(sys.stdin)\nnamespace={'__name__':'fixture'}\nexec(compile(v['source'],'fixed-diagnostic','exec'),namespace)\nprint(json.dumps(namespace['controls'](v['text'].encode(),v['exit'],v['output'].encode())))\n";
        Result result = run(List.of(System.getProperty("managed.test.python", "python"), "-I", "-S", "-c", fixture), JSON.writeValueAsBytes(Map.of("source", source, "text", text, "exit", exit, "output", out)));
        assertEquals(0, result.exit(), "Python control harness failed: " + result.output());
        return JSON.readTree(result.output());
    }
    private record Result(int exit, String output) { }
    private static Result bash(String body) throws Exception {
        String path = System.getProperty("managed.test.bash", "/bin/bash"); org.junit.jupiter.api.Assumptions.assumeTrue(Files.isExecutable(Path.of(path)));
        return run(List.of(path, "--noprofile", "--norc", "-p", "-s"), body.getBytes(StandardCharsets.UTF_8));
    }
    private static Result run(List<String> command, byte[] input) throws Exception {
        var p = new ProcessBuilder(command).redirectErrorStream(true).start(); var pool = Executors.newFixedThreadPool(2);
        var drain = pool.submit(() -> p.getInputStream().readNBytes(32769));
        var writer = pool.submit(() -> { try (var stream = p.getOutputStream()) { stream.write(input); } return null; });
        try { assertTrue(p.waitFor(20, TimeUnit.SECONDS)); writer.get(5, TimeUnit.SECONDS); byte[] output = drain.get(5, TimeUnit.SECONDS); assertTrue(output.length <= 32768); return new Result(p.exitValue(), new String(output, StandardCharsets.UTF_8).replace("\r\n", "\n")); }
        finally { if (p.isAlive()) { p.destroyForcibly(); assertTrue(p.waitFor(5, TimeUnit.SECONDS)); } p.getInputStream().close(); p.getOutputStream().close(); drain.cancel(true); writer.cancel(true); pool.shutdownNow(); assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS)); }
    }
    private static String resource(String name) throws Exception { try (var in = DeploymentAgentPublicationContractDiagnosticProgramTest.class.getResourceAsStream(RES + name)) { return new String(Objects.requireNonNull(in).readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n"); } }
    private static String quote(String value) { return "'" + value.replace("'", "'\\''") + "'"; }
    private static String sha(byte[] value) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
    private static String shaUnchecked(byte[] value) { try { return sha(value); } catch (Exception e) { throw new IllegalStateException(e); } }
}
