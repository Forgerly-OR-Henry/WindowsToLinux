package gold.debug.windowstolinux.app.service.recovery;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import gold.debug.windowstolinux.app.linux.recovery.RecoveryProbeResult;
import gold.debug.windowstolinux.app.secret.*;
import gold.debug.windowstolinux.appapi.task.*;
import org.junit.jupiter.api.Test;

class RecoveryProbeResultTest {
    @Test
    void preservesOriginalIdentityAndOnlyRetriesExplicitConnectionFailures() {
        var events = new ArrayList<>(NON_CONNECTION_EVENTS);
        events.addAll(List.of("SSH_CONNECTION_FAILED", "SSH_CONNECT_TIMEOUT", "SSH_AUTHENTICATION_FAILED",
                "SSH_HOST_KEY_REJECTED", "SSH_HOST_TRUST_FAILED"));
        for (String event : events) {
            var code = switch (event) {
                case "SSH_CONNECT_TIMEOUT" -> ProblemCode.TIMEOUT;
                case "SSH_AUTHENTICATION_FAILED", "SSH_HOST_KEY_REJECTED" -> ProblemCode.ACCESS_DENIED;
                case "SSH_HOST_TRUST_FAILED" -> ProblemCode.CONTRACT_VIOLATION;
                default -> ProblemCode.IO_FAILURE;
            };
            var original = problem(code, event);
            var failure = new ApiException(original);
            var result = RecoveryProbeAdapter.failed(failure);
            assertSame(original, result.failure().orElseThrow());
            assertEquals(original.diagnosticId(), result.failure().orElseThrow().diagnosticId());
            assertEquals(event, result.failure().orElseThrow().eventCode());
            assertEquals(Set.of("SSH_CONNECTION_FAILED", "SSH_CONNECT_TIMEOUT").contains(event),
                    result.status() == RecoveryProbeResult.StatusType.RETRYABLE_CONNECTION, event);
        }
    }

    @Test
    void separatesAuthenticationIdentityCredentialAndCancellation() {
        assertEquals(RecoveryProbeResult.StatusType.AUTHENTICATION_REJECTED,
                RecoveryProbeAdapter
                        .failed(new ApiException(problem(ProblemCode.ACCESS_DENIED, "SSH_AUTHENTICATION_FAILED")))
                        .status());
        assertEquals(RecoveryProbeResult.StatusType.IDENTITY_CONFLICT, RecoveryProbeAdapter
                .failed(new ApiException(problem(ProblemCode.ACCESS_DENIED, "SSH_HOST_KEY_REJECTED"))).status());
        assertEquals(RecoveryProbeResult.StatusType.IDENTITY_CONFLICT, RecoveryProbeAdapter
                .failed(new ApiException(problem(ProblemCode.CONTRACT_VIOLATION, "SSH_HOST_TRUST_FAILED"))).status());
        var missing = SecretStoreException.create(SecretStoreFailureType.SSH_CREDENTIAL_MISSING, "safe");
        assertSame(missing.problem(), RecoveryProbeAdapter.failed(missing).failure().orElseThrow());
        assertEquals(RecoveryProbeResult.StatusType.CREDENTIAL_UNAVAILABLE,
                RecoveryProbeAdapter.failed(missing).status());
        assertSame(missing.problem(), RecoveryProbeAdapter.failed(new Exception("wrapper", missing)).failure().orElseThrow());
        var original = problem(ProblemCode.IO_FAILURE, "SSH_CONNECTION_FAILED");
        var interrupted = new ApiException(original);
        interrupted.initCause(new InterruptedException());
        assertSame(original, RecoveryProbeAdapter.failed(interrupted).failure().orElseThrow());
        assertEquals(original.diagnosticId(), RecoveryProbeAdapter.failed(interrupted).failure().orElseThrow().diagnosticId());
        assertEquals(RecoveryProbeResult.StatusType.INTERRUPTED, RecoveryProbeAdapter.failed(interrupted).status());
        var cancelled = problem(ProblemCode.CANCELLED, "SSH_CANCELLED");
        var cancelledResult = RecoveryProbeAdapter.failed(new ApiException(cancelled));
        assertEquals(RecoveryProbeResult.StatusType.INTERRUPTED, cancelledResult.status());
        assertSame(cancelled, cancelledResult.failure().orElseThrow());
        assertEquals(RecoveryProbeResult.StatusType.FAILED,
                RecoveryProbeAdapter.failed(new IllegalStateException("raw secret")).status());
        assertFalse(RecoveryProbeAdapter.failed(new IllegalStateException("raw secret")).failure().orElseThrow()
                .message().contains("raw secret"));
    }

    private static Problem problem(ProblemCode code, String event) {
        return new Problem(code, event, "safe fixture", Map.of(), Optional.of(UUID.randomUUID()));
    }

    // Original non-connection module events remain explicit negative inputs; generic IO_FAILURE never grants a reconnect.
    private static final List<String> NON_CONNECTION_EVENTS = List.of(
            "linux.lifecycle.external-identity-changed", "linux.lifecycle.external-ownership-required", "linux.lifecycle.external-operation-failed",
            "linux.command.ssh-command-failed", "linux.command.command-failed", "linux.capability.collection-failed",
            "linux.capability.deployment-collection-failed", "linux.deployment.input-staging-failed", "linux.database.preflight-failed",
            "linux.database.backup-failed", "linux.database.restore-failed", "linux.database.evidence-invalid",
            "linux.backup.artifact-creation-failed", "linux.backup.artifact-evidence-invalid", "linux.backup.artifact-transfer-failed",
            "linux.backup.operation-cleanup-failed", "linux.deployment.candidate-preparation-failed", "linux.transfer.source-upload-failed",
            "linux.transfer.upload-verification-failed", "linux.restore.source-invalid", "linux.restore.upload-failed",
            "linux.restore.upload-verification-failed", "linux.restore.activation-failed", "linux.restore.recovery-failed",
            "linux.transfer.local-archive-missing", "linux.transfer.local-archive-read-failed", "linux.transfer.local-archive-size-mismatch",
            "linux.deployment.snapshot-identity-unverified", "linux.deployment.snapshot-token-missing", "linux.deployment.build-publish-unverified",
            "linux.deployment.build-rollback-unverified", "linux.build.root-session-required", "linux.environment.preparation-failed",
            "linux.environment.requirements-unmet", "linux.environment.unsupported-distro", "linux.runtime.observation-failed",
            "linux.lifecycle.action-failed", "linux.lifecycle.start-requires-stopped", "linux.lifecycle.post-start-health-failed",
            "linux.health.check-unsupported", "linux.lifecycle.stop-unverified", "linux.lifecycle.stop-wait-interrupted",
            "linux.lifecycle.main-process-still-running",
            "linux.lifecycle.enable-autostart-unverified",
            "linux.lifecycle.disable-autostart-unverified");
}
