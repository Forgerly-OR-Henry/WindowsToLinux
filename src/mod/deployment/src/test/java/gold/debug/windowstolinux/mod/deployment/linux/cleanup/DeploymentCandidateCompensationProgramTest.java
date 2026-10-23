package gold.debug.windowstolinux.mod.deployment.linux.cleanup;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import gold.debug.windowstolinux.appapi.linux.command.CommandRequest;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.action.*;
import gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout;
import gold.debug.windowstolinux.mod.deployment.linux.cleanup.DeploymentCandidateCompensationProgram.*;
import gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentManagedHelperBundle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class DeploymentCandidateCompensationProgramTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ServerId SERVER = new ServerId("candidate-compensation-server");

    private Binding binding() {
        var layout = DeploymentRemoteLayout.original();
        return new Binding(UUID.fromString("11111111-2222-3333-4444-555555555555"), UUID.fromString("99999999-2222-3333-4444-555555555555"),
            "fixture-app", "fixture-app-backend", "fixture-app-backend-" + "a".repeat(16), "b".repeat(64), "c".repeat(64), "a".repeat(64),
            layout, DeploymentManagedHelperBundle.helperSha256(layout));
    }

    @Test
    void fixesEveryExecutableByteAndCanonicalBindingWithDistinctReadAndWriteResources() throws Exception {
        var binding = binding();
        assertTrue(binding.json().toString().contains("\"layout\":{"));
        for (var mode : Mode.values()) {
            var request = new Request(mode, binding, mode == Mode.EXECUTE ? "d".repeat(64) : "", 1000);
            assertEquals(request, DeploymentCandidateCompensationProgram.decodeRequest(request.json()));
            try (var command = DeploymentCandidateCompensationProgram.command(request)) {
                var intent = DeploymentCandidateCompensationProgram.intent(SERVER, request, command);
                assertEquals(request, DeploymentCandidateCompensationProgram.verify(intent, command, binding.layout()));
                assertEquals(mode == Mode.EXECUTE ? 120 : 45, command.timeout().toSeconds());
                assertEquals(8192, command.outputLimitBytes());
                String stdin = new String(command.stdin(), StandardCharsets.UTF_8);
                assertEquals(1, stdin.split("\nmain\\(json.loads\\(base64.b64decode\\('", -1).length - 1);
                assertTrue(intent.semantics().orElseThrow().targets().contains(new ActionTarget(SERVER.id(), "filesystem-read", "/proc")));
                assertEquals(mode == Mode.EXECUTE, intent.semantics().orElseThrow().targets().stream().anyMatch(target -> target.kind().equals("filesystem-write")));
                assertTrue(binding.lockPath().endsWith(binding.candidateId() + ".lock"));
                for (String path : List.of(binding.claimRoot(), binding.claimTaskRoot(), binding.claimPath()))
                    assertTrue(intent.semantics().orElseThrow().targets().contains(new ActionTarget(SERVER.id(),
                        mode == Mode.EXECUTE ? "filesystem-write" : "filesystem-read", path)));
                assertTrue(intent.semantics().orElseThrow().targets().contains(new ActionTarget(SERVER.id(), "filesystem-read",
                    "/usr/share/unattended-upgrades/unattended-upgrade-shutdown")));
                assertTrue(DeploymentCandidateCompensationProgram.bodySha256().matches("[0-9a-f]{64}"));
            }
        }
        assertTrue(DeploymentCandidateCompensationProgram.canonical(JSON.readTree("{\"z\":1,\"a\":{\"c\":3,\"b\":2}}"))
            .equals("{\"a\":{\"b\":2,\"c\":3},\"z\":1}"));
    }

    @ParameterizedTest
    @ValueSource(strings={"STDIN", "LAUNCHER", "TIMEOUT", "LIMIT", "TARGET", "REVISION", "SEMANTICS"})
    void rejectsChangedFixedRequestsAndExactResources(String mutation) {
        var request = new Request(Mode.EXECUTE, binding(), "d".repeat(64), 1000);
        try (var original = DeploymentCandidateCompensationProgram.command(request);
             var changed = new CommandRequest(mutation.equals("LAUNCHER") ? "python3 -" : original.script(),
                mutation.equals("STDIN") ? (new String(original.stdin(), StandardCharsets.UTF_8) + "print('extra')\n").getBytes(StandardCharsets.UTF_8) : original.stdin(),
                mutation.equals("TIMEOUT") ? Duration.ofSeconds(119) : original.timeout(), mutation.equals("LIMIT") ? 8191 : original.outputLimitBytes())) {
            var intent = DeploymentCandidateCompensationProgram.intent(SERVER, request, changed);
            if (mutation.equals("TARGET")) intent = intent.withSemantics(new ActionSemantics(request.mode().operation(), Set.of(new ActionTarget(SERVER.id(), "task", request.binding().taskId().toString()))));
            if (mutation.equals("SEMANTICS")) intent = intent.withSemantics(new ActionSemantics(Mode.RECEIPT.operation(), intent.semantics().orElseThrow().targets()));
            if (mutation.equals("REVISION")) intent = new ActionIntent(intent.type(), intent.serverIds(), intent.target(), "e".repeat(64), intent.exactAction(), intent.effectSummary()).withSemantics(intent.semantics().orElseThrow());
            final var actual = intent;
            assertThrows(IllegalArgumentException.class, () -> DeploymentCandidateCompensationProgram.verify(actual, changed, request.binding().layout()));
        }
    }

    @ParameterizedTest
    @ValueSource(strings={"TASK", "OPERATION", "BINDING", "MODE", "EXTRA", "DUPLICATE", "TRAILING", "STDOUT_LINES", "RECEIPT", "PRESENT", "QUIESCENT", "PROOF"})
    void requiresExactBoundedReceiptAndVerifiedAbsentPostcondition(String mutation) throws Exception {
        var request = new Request(Mode.RECEIPT, binding(), "", 1000);
        var response = response(request, "SUCCEEDED", false, true);
        switch (mutation) {
            case "TASK" -> response.put("taskId", UUID.randomUUID().toString());
            case "OPERATION" -> response.put("operationId", UUID.randomUUID().toString());
            case "BINDING" -> response.put("bindingSha256", "0".repeat(64));
            case "MODE" -> response.put("mode", "execute");
            case "EXTRA" -> response.put("extra", true);
            case "RECEIPT" -> response.put("receiptSha256", "");
            case "PRESENT" -> response.put("candidatePresent", true);
            case "QUIESCENT" -> response.put("quiescent", false);
            case "PROOF" -> response.remove("systemProcessExclusion");
        }
        String value = response + "\n";
        if (mutation.equals("DUPLICATE")) value = response.toString().replace("\"formatVersion\":2", "\"formatVersion\":2,\"formatVersion\":2") + "\n";
        if (mutation.equals("TRAILING")) value = response + " {}\n";
        if (mutation.equals("STDOUT_LINES")) value = response + "\n{}\n";
        final String actual = value;
        assertThrows(IllegalArgumentException.class, () -> DeploymentCandidateCompensationProgram.parse(request, actual));
    }

    @Test
    void unknownOrNotDispatchedNeverMeansCompleted() {
        var request = new Request(Mode.RECEIPT, binding(), "", 1000);
        for (String status : List.of("UNKNOWN", "NOT_DISPATCHED", "REFUSED", "ABSENT"))
            assertFalse(DeploymentCandidateCompensationProgram.parse(request, response(request, status, false, true) + "\n").completed());
    }

    private ObjectNode response(Request request, String status, boolean present, boolean quiescent) {
        var result = JSON.createObjectNode().put("formatVersion", 2).put("mode", request.mode().wire()).put("taskId", request.binding().taskId().toString())
            .put("operationId", request.binding().operationId().toString()).put("bindingSha256", request.binding().sha256()).put("status", status)
            .put("reason", "fixture-result").put("candidatePresent", present).put("quiescent", quiescent)
            .put("observationSha256", "d".repeat(64)).put("receiptSha256", "e".repeat(64));
        result.putObject("systemProcessExclusion");
        return result;
    }

    @ParameterizedTest
    @ValueSource(strings={"VALID", "EXTRA_SKIP_PID", "PID", "PARENT", "START", "EPOCH", "FRACTION", "HASH", "INVOCATION", "MISSING", "VERSION"})
    void verifiesDerivedSystemRoleProofWithoutCallerExemptionParameters(String mutation) {
        var request = new Request(Mode.OBSERVE, binding(), "", 1000);
        var result = response(request, "OBSERVED", true, true);
        var proof = result.withObject("systemProcessExclusion").put("role", "unattended-upgrade-shutdown-v1")
            .put("pid", 886).put("parentPid", 1).put("startTicks", 925).put("executableDevice", 64769)
            .put("executableInode", 2954).put("unitInvocationId", "1".repeat(32)).put("startedAtEpochMillis", 500);
        for (String field : List.of("bootIdSha256", "executableSha256", "scriptSha256", "unitSha256", "argvSha256", "controlGroupSha256"))
            proof.put(field, "a".repeat(64));
        switch (mutation) {
            case "EXTRA_SKIP_PID" -> proof.put("skipPID", 886);
            case "PID" -> proof.put("pid", 0);
            case "PARENT" -> proof.put("parentPid", 2);
            case "START" -> proof.put("startTicks", 0);
            case "EPOCH" -> proof.put("startedAtEpochMillis", 1000);
            case "FRACTION" -> proof.put("executableDevice", 1.5);
            case "HASH" -> proof.put("executableSha256", "A".repeat(64));
            case "INVOCATION" -> proof.put("unitInvocationId", "1".repeat(31));
            case "MISSING" -> proof.remove("scriptSha256");
            case "VERSION" -> result.put("formatVersion", 1);
        }
        if (mutation.equals("VALID")) assertEquals(proof, DeploymentCandidateCompensationProgram.parse(request, result + "\n").systemProcessExclusion());
        else assertThrows(IllegalArgumentException.class, () -> DeploymentCandidateCompensationProgram.parse(request, result + "\n"));
        assertThrows(IllegalArgumentException.class, () -> new Request(Mode.OBSERVE, binding(), "", 0));
        assertThrows(IllegalArgumentException.class, () -> DeploymentCandidateCompensationProgram.decodeRequest(request.json().replace("\"mode\":", "\"skipPID\":886,\"mode\":")));
    }
}
