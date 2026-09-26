package gold.debug.windowstolinux.app.service.recovery;

import static org.junit.jupiter.api.Assertions.*;

import gold.debug.windowstolinux.app.linux.recovery.RecoveryProbeResult;
import gold.debug.windowstolinux.app.secret.*;
import gold.debug.windowstolinux.shared.linux.error.*;
import org.junit.jupiter.api.Test;

class RecoveryProbeResultTest {
    @Test
    void preservesOriginalIdentityAndOnlyRetriesExplicitConnectionFailures() {
        for (var type : LinuxOperationFailureType.values()) {
            var failure = LinuxOperationException.create(type, "safe fixture");
            var result = RecoveryProbeAdapter.failed(failure);
            assertEquals(failure.failure().operationIdentity().toString(),
                    result.failure().orElseThrow().diagnosticId().orElseThrow().toString());
            assertEquals(failure.failure().code(), result.failure().orElseThrow().eventCode());
            assertEquals(type == LinuxOperationFailureType.CONNECTION_FAILED,
                    result.status() == RecoveryProbeResult.StatusType.RETRYABLE_CONNECTION, type.name());
        }
    }

    @Test
    void separatesAuthenticationIdentityCredentialAndCancellation() {
        assertEquals(RecoveryProbeResult.StatusType.AUTHENTICATION_REJECTED,
                RecoveryProbeAdapter
                        .failed(LinuxOperationException.create(LinuxOperationFailureType.AUTHENTICATION_FAILED, "safe"))
                        .status());
        assertEquals(RecoveryProbeResult.StatusType.IDENTITY_CONFLICT, RecoveryProbeAdapter
                .failed(LinuxOperationException.create(LinuxOperationFailureType.HOST_KEY_REJECTED, "safe")).status());
        var missing = SecretStoreException.create(SecretStoreFailureType.SSH_CREDENTIAL_MISSING, "safe");
        assertSame(missing.problem(), RecoveryProbeAdapter.failed(missing).failure().orElseThrow());
        assertEquals(RecoveryProbeResult.StatusType.CREDENTIAL_UNAVAILABLE,
                RecoveryProbeAdapter.failed(missing).status());
        var interrupted = LinuxOperationException.create(LinuxOperationFailureType.CONNECTION_FAILED, "safe",
                new InterruptedException());
        assertEquals(interrupted.failure().operationIdentity().toString(), RecoveryProbeAdapter.failed(interrupted)
                .failure().orElseThrow().diagnosticId().orElseThrow().toString());
        assertEquals(RecoveryProbeResult.StatusType.INTERRUPTED, RecoveryProbeAdapter.failed(interrupted).status());
        assertEquals(RecoveryProbeResult.StatusType.FAILED,
                RecoveryProbeAdapter.failed(new IllegalStateException("raw secret")).status());
        assertFalse(RecoveryProbeAdapter.failed(new IllegalStateException("raw secret")).failure().orElseThrow()
                .message().contains("raw secret"));
    }
}
