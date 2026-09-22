package gold.debug.windowstolinux.app.service.recovery;

import static org.junit.jupiter.api.Assertions.*;

import gold.debug.windowstolinux.app.secret.*;
import gold.debug.windowstolinux.shared.linux.error.*;
import org.junit.jupiter.api.Test;

class RecoveryProbeResultTest {
    @Test
    void preservesOriginalIdentityAndOnlyRetriesExplicitConnectionFailures() {
        for (var type : LinuxOperationFailureType.values()) {
            var failure = LinuxOperationException.create(type, "safe fixture");
            var result = RecoveryProbeResult.failed(failure);
            assertSame(failure.failure(), result.failure().orElseThrow());
            assertEquals(type == LinuxOperationFailureType.CONNECTION_FAILED,
                    result.status() == RecoveryProbeResult.StatusType.RETRYABLE_CONNECTION, type.name());
        }
    }

    @Test
    void separatesAuthenticationIdentityCredentialAndCancellation() {
        assertEquals(RecoveryProbeResult.StatusType.AUTHENTICATION_REJECTED,
                RecoveryProbeResult
                        .failed(LinuxOperationException.create(LinuxOperationFailureType.AUTHENTICATION_FAILED, "safe"))
                        .status());
        assertEquals(RecoveryProbeResult.StatusType.IDENTITY_CONFLICT, RecoveryProbeResult
                .failed(LinuxOperationException.create(LinuxOperationFailureType.HOST_KEY_REJECTED, "safe")).status());
        var missing = SecretStoreException.create(SecretStoreFailureType.SSH_CREDENTIAL_MISSING, "safe");
        assertSame(missing.failure(), RecoveryProbeResult.failed(missing).failure().orElseThrow());
        assertEquals(RecoveryProbeResult.StatusType.CREDENTIAL_UNAVAILABLE,
                RecoveryProbeResult.failed(missing).status());
        var interrupted = LinuxOperationException.create(LinuxOperationFailureType.CONNECTION_FAILED, "safe",
                new InterruptedException());
        assertSame(interrupted.failure(), RecoveryProbeResult.failed(interrupted).failure().orElseThrow());
        assertEquals(RecoveryProbeResult.StatusType.INTERRUPTED, RecoveryProbeResult.failed(interrupted).status());
        assertEquals(RecoveryProbeResult.StatusType.FAILED,
                RecoveryProbeResult.failed(new IllegalStateException("raw secret")).status());
        assertFalse(RecoveryProbeResult.failed(new IllegalStateException("raw secret")).failure().orElseThrow()
                .diagnostic().contains("raw secret"));
    }
}
