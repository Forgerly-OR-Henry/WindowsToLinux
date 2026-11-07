package gold.debug.windowstolinux.mod.deployment.service.standard;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.Problem;
import gold.debug.windowstolinux.appapi.task.result.ProblemCode;
import org.junit.jupiter.api.Test;

/** Only the two existing public diagnostic projections; no runtime, persistence, SSH, or model. */
class DeploymentSshReadObservationProjectionTest {
    @Test void bothExistingDiagnosticExitsRetainOnlyTypedExecAndReadFacts() {
        var context = new LinkedHashMap<String, String>();
        context.put("channelOpenWorkerSnapshotCommandPhase", "WAITING_FOR_EXIT");
        context.put("execRequestObservation", "AVAILABLE"); context.put("execRequestWriteState", "PENDING");
        context.put("execRequestWriteFailureType", "UNKNOWN"); context.put("execRequestWriteStartedMillis", "17");
        context.put("execRequestWriteCompletedMillis", "UNKNOWN"); context.put("execRequestWantReply", "false");
        context.put("execRequestPeerAcceptance", "UNKNOWN"); context.put("transportSocketReadObservation", "AVAILABLE");
        context.put("transportSocketReadCoverage", "CALLBACK_ONLY"); context.put("transportSocketReadPending", "true");
        context.put("transportSocketReadLastResult", "DATA");
        for (var suffix : List.of("Attempts", "Returned", "Completed", "Failed", "Thrown", "FutureAttempts", "ActiveCalls", "AwaitingCallbacks",
            "LastAttemptAgeMillis", "LastReturnAgeMillis", "LastCompletionAgeMillis")) context.put("transportSocketRead" + suffix, "1");
        var original = new ApiException(new Problem(ProblemCode.UNKNOWN, "SSH_COMMAND_TIMEOUT", "private message", context, Optional.empty()));
        var lines = new ArrayList<String>();
        assertSame(original, assertThrows(ApiException.class, () -> DeploymentStaticLiveAcceptanceTest.observedRemoteCall(
            DeploymentStaticLiveAcceptanceTest.LiveRemotePhase.EXECUTE, () -> UUID.fromString("10000000-0000-4000-8000-000000000025"),
            () -> { throw original; }, lines::add)));
        context.forEach((key, value) -> {
            String detail = "problem[0].context." + key + "=" + value;
            assertEquals(List.of(detail), DeploymentStaticLiveAcceptanceTest.safeOriginalCallCauses(detail));
            assertTrue(lines.stream().anyMatch(line -> line.endsWith(" " + detail)), detail);
        });
        assertFalse(lines.toString().contains("private"));
    }

    @Test void invalidExecAcceptanceBodiesUnknownKeysAndUnboundedValuesStayExcluded() {
        for (var entry : Map.ofEntries(
            Map.entry("execRequestPeerAcceptance", "true"), Map.entry("execRequestWriteState", "READY"),
            Map.entry("execRequestWriteFailureType", "private exception body"), Map.entry("execRequestWantReply", "private"),
            Map.entry("transportSocketReadPending", "READY"), Map.entry("transportSocketReadCoverage", "KERNEL_ACCEPTED"),
            Map.entry("transportSocketReadLastResult", "private payload"), Map.entry("transportSocketReadAttempts", "1000000000000"),
            Map.entry("transportSocketReadReturned", "-1"), Map.entry("transportSocketReadLastReturnAgeMillis", "1\nprivate"),
            Map.entry("transportSocketReadBuffer", "private"), Map.entry("execRequestCommand", "private")).entrySet()) {
            String detail = "context." + entry.getKey() + "=" + entry.getValue();
            if (entry.getValue().contains("\n"))
                assertEquals(List.of(detail.substring(0, detail.indexOf('\n'))), DeploymentStaticLiveAcceptanceTest.safeOriginalCallCauses(detail),
                    "Stored diagnostics filter complete log lines; a private second line is not retained");
            else assertTrue(DeploymentStaticLiveAcceptanceTest.safeOriginalCallCauses(detail).isEmpty(), detail);
            var lines = new ArrayList<String>();
            var failure = new ApiException(new Problem(ProblemCode.UNKNOWN, "SSH_COMMAND_TIMEOUT", "private",
                Map.of(entry.getKey(), entry.getValue()), Optional.empty()));
            assertSame(failure, assertThrows(ApiException.class, () -> DeploymentStaticLiveAcceptanceTest.observedRemoteCall(
                DeploymentStaticLiveAcceptanceTest.LiveRemotePhase.EXECUTE, () -> null, () -> { throw failure; }, lines::add)));
            assertFalse(lines.stream().anyMatch(line -> line.contains("context." + entry.getKey() + "=")), detail);
        }
        for (String detail : List.of("context.transportSocketReadPending=UNKNOWN", "context.transportSocketReadCoverage=FUTURE_UNOBSERVED",
            "context.transportSocketReadCoverage=HANDLER_UNOBSERVED", "context.execRequestObservation=NOT_OBSERVED",
            "context.transportSocketReadObservation=UNAVAILABLE", "context.execRequestWriteState=FAILED", "context.execRequestWriteFailureType=IO"))
            assertEquals(List.of(detail), DeploymentStaticLiveAcceptanceTest.safeOriginalCallCauses(detail));
    }
}
