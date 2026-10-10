package gold.debug.windowstolinux.mod.deployment.service.agent.toolchain;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import gold.debug.windowstolinux.appapi.linux.command.CommandResult;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.result.*;
import org.junit.jupiter.api.Test;

class DeploymentAgentToolchainFailureEvidenceTest {
    private static final String OPERATION = "a".repeat(32);

    private static final ServerId SERVER = new ServerId("original-server");

    @Test
    void actualKnownFailureSurvivesSetupStyleValueRemovalAsBoundedObservation() throws Exception {
        String diagnostic = new ObjectMapper().writeValueAsString(Map.of("operation", OPERATION, "phase", "OS_CONFIGURATION_OWNER",
            "reason", "OWNER_NOT_ALLOWED", "path", "/etc/apt/apt.conf.d/20baseline", "owner", "actual-os-package", "allowedOwners", List.of("apt")));
        var result = retain(TaskResult.FAILED, 65, "TOOLCHAIN_FAILURE=dependencies:OS_CONFIGURATION_OWNER\nTOOLCHAIN_DIAGNOSTIC=" + diagnostic + "\n", false);
        assertEquals(TaskResult.FAILED, result.outcome());
        assertTrue(result.value().isEmpty());
        String observation = DeploymentAgentToolchainFailureEvidence.observation(result.problem());
        assertTrue(observation.contains("OS_CONFIGURATION_OWNER"));
        assertTrue(observation.contains("OWNER_NOT_ALLOWED"));
        assertTrue(observation.contains("actual-os-package"));
        assertTrue(observation.contains("\"actualExit\":65"));
        assertTrue(observation.contains("\"transportOutcome\":\"FAILED\""));
    }

    @Test
    void unknownProtocolAndTruncatedResultCannotBecomeRetryableKnownFailure() {
        var unknown = retain(TaskResult.FAILED, 70, "TOOLCHAIN_FAILURE=unknown:DEPENDENCY_STATE_UNCONFIRMED\n", false);
        assertEquals(TaskResult.UNKNOWN, unknown.outcome());
        assertTrue(DeploymentAgentToolchainFailureEvidence.observation(unknown.problem()).contains("\"productOutcome\":\"UNKNOWN\""));
        assertEquals(TaskResult.UNKNOWN, retain(TaskResult.FAILED, 65, "TOOLCHAIN_FAILURE=dependencies:OS_CONFIGURATION_OWNER\n", true).outcome());
        assertEquals(TaskResult.CANCELLED, retain(TaskResult.CANCELLED, 70, "TOOLCHAIN_FAILURE=unknown:DEPENDENCY_STATE_UNCONFIRMED\n", false).outcome());
    }

    @Test
    void missingResultOrApprovalDenialCannotBorrowAFormerExecutionReceipt() {
        retain(TaskResult.FAILED, 65, "TOOLCHAIN_FAILURE=dependencies:OS_CONFIGURATION_OWNER\n", false);
        OperationResult<CommandResult> denied = new OperationResult<>(TaskResult.FAILED, Optional.empty(),
            Optional.of(new Problem(ProblemCode.ACCESS_DENIED, "REVIEW_DENIED", "Denied before execution", Map.of(), Optional.empty())));
        assertSame(denied, DeploymentAgentToolchainFailureEvidence.retain(denied, "dependencies", SERVER, OPERATION));
        assertEquals("", DeploymentAgentToolchainFailureEvidence.observation(denied.problem()));
    }

    @Test
    void foreignDiagnosticAndArbitraryOutputAreNotCopiedIntoTheObservation() {
        String foreign = "TOOLCHAIN_DIAGNOSTIC={\"operation\":\"" + "b".repeat(32) + "\",\"password\":\"never copy arbitrary body\"}";
        var result = retain(TaskResult.FAILED, 65, "TOOLCHAIN_FAILURE=dependencies:OS_CONFIGURATION_OWNER\n" + foreign + "\n", false);
        String observation = DeploymentAgentToolchainFailureEvidence.observation(result.problem());
        assertFalse(observation.contains("password"));
        assertFalse(observation.contains("never copy"));
        assertFalse(observation.contains("reportedDiagnostic"));
    }

    @Test
    void aptBinaryDiagnosticRetainsCurrentFixedFieldAndDigestWithoutConfigurationText() throws Exception {
        var fields = Map.of("operation", OPERATION, "phase", "UNSUPPORTED_APT_BINARY", "reason", "PATH_OVERRIDE",
            "field", "gzip", "valueBytes", 19, "valueSha256", "c".repeat(64));
        String diagnostic = new ObjectMapper().writeValueAsString(fields);
        var result = retain(TaskResult.FAILED, 65, "TOOLCHAIN_FAILURE=dependencies:UNSUPPORTED_APT_BINARY\nTOOLCHAIN_DIAGNOSTIC=" + diagnostic + "\n", false);
        assertEquals(TaskResult.FAILED, result.outcome());
        String observation = DeploymentAgentToolchainFailureEvidence.observation(result.problem());
        assertTrue(observation.contains("reportedDiagnostic"));
        assertTrue(observation.contains("\"field\":\"gzip\""));
        assertTrue(observation.contains("\"valueBytes\":19"));
        assertTrue(observation.contains("c".repeat(64)));
        assertFalse(observation.contains("configurationText"));
    }

    @Test
    void aptBinaryDiagnosticRejectsUnknownFieldsMismatchedPhaseAndMalformedBounds() throws Exception {
        var baseline = Map.of("operation", OPERATION, "phase", "UNSUPPORTED_APT_BINARY", "reason", "PATH_OVERRIDE",
            "field", "gzip", "valueBytes", 19, "valueSha256", "c".repeat(64));
        var invalid = new ArrayList<Map<String, Object>>();
        for (var change : List.of(Map.<String, Object>entry("operation", "b".repeat(32)), Map.<String, Object>entry("phase", "OS_CONFIGURATION_OWNER"),
            Map.<String, Object>entry("reason", "private-body"), Map.<String, Object>entry("field", "private-field"),
            Map.<String, Object>entry("valueBytes", -1), Map.<String, Object>entry("valueBytes", 1048577), Map.<String, Object>entry("valueBytes", true),
            Map.<String, Object>entry("valueSha256", "private-body"))) {
            var fields = new LinkedHashMap<String, Object>(baseline);
            fields.put(change.getKey(), change.getValue());
            invalid.add(fields);
        }
        var extra = new LinkedHashMap<String, Object>(baseline);
        extra.put("configurationText", "private-body");
        invalid.add(extra);
        for (var fields : invalid) {
            String diagnostic = new ObjectMapper().writeValueAsString(fields);
            var result = retain(TaskResult.FAILED, 65, "TOOLCHAIN_FAILURE=dependencies:UNSUPPORTED_APT_BINARY\nTOOLCHAIN_DIAGNOSTIC=" + diagnostic + "\n", false);
            String observation = DeploymentAgentToolchainFailureEvidence.observation(result.problem());
            assertFalse(observation.contains("reportedDiagnostic"));
            assertFalse(observation.contains("private-body"));
            assertFalse(observation.contains("private-field"));
            assertEquals(TaskResult.FAILED, result.outcome());
        }
    }

    @Test
    void finalControllerExceptionDiagnosticRemainsUnknownAndUsesOnlyCurrentFixedClassification() throws Exception {
        String diagnostic = new ObjectMapper().writeValueAsString(Map.of("operation", OPERATION, "phase", "AGENT_TOOLCHAIN_STATE_UNCONFIRMED",
            "function", "agent_create", "exception", "OSError", "errno", 2, "line", 1401));
        var result = retain(TaskResult.FAILED, 70, "TOOLCHAIN_FAILURE=unknown:AGENT_TOOLCHAIN_STATE_UNCONFIRMED\nTOOLCHAIN_DIAGNOSTIC=" + diagnostic + "\n", false);
        assertEquals(TaskResult.UNKNOWN, result.outcome());
        String observation = DeploymentAgentToolchainFailureEvidence.observation(result.problem());
        assertTrue(observation.contains("reportedDiagnostic"));
        assertTrue(observation.contains("\"function\":\"agent_create\""));
        assertTrue(observation.contains("\"errno\":2"));
        assertTrue(observation.contains("\"line\":1401"));
        assertFalse(observation.contains("exceptionMessage"));
        assertFalse(observation.contains("filename"));
    }

    @Test
    void controllerExceptionProjectionRejectsUnknownFunctionsWrongOperationAndRawTraceFields() throws Exception {
        var baseline = Map.of("operation", OPERATION, "phase", "AGENT_TOOLCHAIN_STATE_UNCONFIRMED", "function", "agent_create",
            "exception", "OSError", "errno", 2, "line", 1401);
        var invalid = new ArrayList<Map<String, Object>>();
        for (var change : List.of(Map.<String, Object>entry("operation", "b".repeat(32)), Map.<String, Object>entry("phase", "UNSUPPORTED_APT_BINARY"),
            Map.<String, Object>entry("function", "private-body"), Map.<String, Object>entry("exception", "private-body"),
            Map.<String, Object>entry("errno", "private-body"), Map.<String, Object>entry("errno", 4096), Map.<String, Object>entry("line", -1),
            Map.<String, Object>entry("line", 16385), Map.<String, Object>entry("line", 0), Map.<String, Object>entry("function", "entry"))) {
            var fields = new LinkedHashMap<String, Object>(baseline);
            fields.put(change.getKey(), change.getValue());
            invalid.add(fields);
        }
        var extra = new LinkedHashMap<String, Object>(baseline);
        extra.put("exceptionMessage", "private-body");
        invalid.add(extra);
        for (var fields : invalid) {
            String diagnostic = new ObjectMapper().writeValueAsString(fields);
            var result = retain(TaskResult.FAILED, 70, "TOOLCHAIN_FAILURE=unknown:AGENT_TOOLCHAIN_STATE_UNCONFIRMED\nTOOLCHAIN_DIAGNOSTIC=" + diagnostic + "\n", false);
            assertEquals(TaskResult.UNKNOWN, result.outcome());
            String observation = DeploymentAgentToolchainFailureEvidence.observation(result.problem());
            assertFalse(observation.contains("reportedDiagnostic"));
            assertFalse(observation.contains("private-body"));
        }
    }

    @Test
    void dpkgRejectionProjectionKeepsKnownFailureAndNeverAdmitsRawOptions() throws Exception {
        var fields = Map.of("operation", OPERATION, "phase", "UNSUPPORTED_DPKG_CONFIGURATION", "kind", "PART",
            "line", 3, "valueBytes", 15, "valueSha256", "d".repeat(64));
        String diagnostic = new ObjectMapper().writeValueAsString(fields);
        var result = retain(TaskResult.FAILED, 65, "TOOLCHAIN_FAILURE=dependencies:UNSUPPORTED_DPKG_CONFIGURATION\nTOOLCHAIN_DIAGNOSTIC=" + diagnostic + "\n", false);
        assertEquals(TaskResult.FAILED, result.outcome());
        String observation = DeploymentAgentToolchainFailureEvidence.observation(result.problem());
        assertTrue(observation.contains("reportedDiagnostic"));
        assertTrue(observation.contains("\"kind\":\"PART\""));
        for (var change : List.of(Map.<String, Object>entry("operation", "b".repeat(32)), Map.<String, Object>entry("kind", "private-body"),
            Map.<String, Object>entry("line", 0), Map.<String, Object>entry("valueBytes", 0), Map.<String, Object>entry("valueSha256", "private-body"))) {
            var invalid = new LinkedHashMap<String, Object>(fields);
            invalid.put(change.getKey(), change.getValue());
            diagnostic = new ObjectMapper().writeValueAsString(invalid);
            result = retain(TaskResult.FAILED, 65, "TOOLCHAIN_FAILURE=dependencies:UNSUPPORTED_DPKG_CONFIGURATION\nTOOLCHAIN_DIAGNOSTIC=" + diagnostic + "\n", false);
            assertEquals(TaskResult.FAILED, result.outcome());
            observation = DeploymentAgentToolchainFailureEvidence.observation(result.problem());
            assertFalse(observation.contains("reportedDiagnostic"));
            assertFalse(observation.contains("private-body"));
        }
    }

    @Test
    void nativeUndispatchedFailureRemainsKnownOnlyWithTheCompleteTransportFacts() {
        var context = Map.of("commandOpenRequested", "false", "channelOpenConfirmed", "false", "exitObserved", "false", "outputObserved", "false");
        var problem = new Problem(ProblemCode.IO_FAILURE, "SSH_COMMAND_NOT_SENT", "Command could not be dispatched", context, Optional.empty());
        var notSent = new OperationResult<>(TaskResult.FAILED, Optional.of(new CommandResult(Optional.empty(), "", "", false)), Optional.of(problem));
        assertSame(notSent, DeploymentAgentToolchainFailureEvidence.retain(notSent, "resolve", SERVER, OPERATION));
        assertEquals("", DeploymentAgentToolchainFailureEvidence.observation(notSent.problem()));
        for (var changed : List.of(
            new OperationResult<>(TaskResult.FAILED, Optional.of(new CommandResult(Optional.empty(), "", "", false)), Optional.of(new Problem(ProblemCode.IO_FAILURE, "SSH_COMMAND_NOT_SENT", "Unconfirmed", Map.of(), Optional.empty()))),
            new OperationResult<>(TaskResult.FAILED, Optional.of(new CommandResult(Optional.empty(), "actual output", "", false)), Optional.of(problem)),
            new OperationResult<>(TaskResult.UNKNOWN, Optional.of(new CommandResult(Optional.empty(), "", "", false)), Optional.of(problem)),
            new OperationResult<>(TaskResult.FAILED, Optional.of(new CommandResult(Optional.empty(), "", "", true)), Optional.of(problem))))
            assertEquals(TaskResult.UNKNOWN, DeploymentAgentToolchainFailureEvidence.retain(changed, "resolve", SERVER, OPERATION).outcome());
    }

    private static OperationResult<CommandResult> retain(TaskResult outcome, int exit, String output, boolean truncated) {
        return DeploymentAgentToolchainFailureEvidence.retain(new OperationResult<>(outcome,
            Optional.of(new CommandResult(Optional.of(exit), output, "", truncated)),
            Optional.of(new Problem(ProblemCode.IO_FAILURE, "SSH_COMMAND_NONZERO", "Actual transport nonzero", Map.of(), Optional.empty()))), "dependencies", SERVER, OPERATION);
    }
}
