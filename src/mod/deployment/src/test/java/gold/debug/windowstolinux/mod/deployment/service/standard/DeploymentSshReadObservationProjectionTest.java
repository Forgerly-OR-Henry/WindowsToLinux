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
        context.put("transportFailureCauseType", "java.io.EOFException");
        for (int index = 0; index < 6; index++)
            context.put("transportFailureStack[" + index + "]", "org.apache.sshd.common.session.AbstractSession#exceptionCaught:174");
        String longFrame = "gold.debug.windowstolinux.app.linux." + "A".repeat(160) + "#" + "m".repeat(128) + ":2147483647";
        assertTrue(longFrame.length() > 180 && longFrame.length() <= 384);
        context.put("transportFailureStack[5]", longFrame);
        for (var suffix : List.of("Attempts", "Returned", "Completed", "Failed", "Thrown", "FutureAttempts", "ActiveCalls", "AwaitingCallbacks",
            "LastAttemptAgeMillis", "LastReturnAgeMillis", "LastCompletionAgeMillis")) context.put("transportSocketRead" + suffix, "1");
        var diagnostic = UUID.fromString("10000000-0000-4000-8000-000000000026");
        var original = new ApiException(new Problem(ProblemCode.UNKNOWN, "SSH_COMMAND_TIMEOUT", "private message", context, Optional.of(diagnostic)));
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
        assertEquals(Optional.of(diagnostic), original.problem().diagnosticId());
        for (String causeType : List.of("UNKNOWN", "org.apache.sshd.common.SshException")) {
            String detail = "context.transportFailureCauseType=" + causeType;
            assertEquals(List.of(detail), DeploymentStaticLiveAcceptanceTest.safeOriginalCallCauses(detail));
        }
    }

    @Test void invalidExecAcceptanceBodiesUnknownKeysAndUnboundedValuesStayExcluded() {
        var rejected = new ArrayList<>(Map.ofEntries(
            Map.entry("execRequestPeerAcceptance", "true"), Map.entry("execRequestWriteState", "READY"),
            Map.entry("execRequestWriteFailureType", "private exception body"), Map.entry("execRequestWantReply", "private"),
            Map.entry("transportSocketReadPending", "READY"), Map.entry("transportSocketReadCoverage", "KERNEL_ACCEPTED"),
            Map.entry("transportSocketReadLastResult", "private payload"), Map.entry("transportSocketReadAttempts", "10000000000000"),
            Map.entry("transportSocketReadReturned", "-1"), Map.entry("transportSocketReadLastReturnAgeMillis", "1\nprivate"),
            Map.entry("transportSocketReadBuffer", "private"), Map.entry("execRequestCommand", "private")).entrySet());
        rejected.addAll(List.of(
            Map.entry("transportFailureCauseType", "private.Exception"),
            Map.entry("transportFailureCauseType", "java.io.EOFException: private"),
            Map.entry("transportFailureCauseType", "java.io.EOFException\nprivate"),
            Map.entry("transportFailureStack[6]", "java.io.InputStream#read:1"),
            Map.entry("transportFailureStack[-1]", "java.io.InputStream#read:1"),
            Map.entry("transportFailureStack[0]", "private.Reader#read:1"),
            Map.entry("transportFailureStack[0]", "java.io.InputStream#read:1 /private/source.java"),
            Map.entry("transportFailureStack[0]", "java.io.InputStream#read:1\nprivate"),
            Map.entry("transportFailureStack[0]", "java." + "A".repeat(161) + "#read:1"),
            Map.entry("transportFailureStack[0]", "java.io.InputStream#" + "m".repeat(129) + ":1"),
            Map.entry("transportFailureStack[0]", "java." + "A".repeat(384) + "#read:1"),
            Map.entry("transportFailureMessage", "private"), Map.entry("argv", "private"), Map.entry("payload", "private")));
        for (var entry : rejected) {
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
