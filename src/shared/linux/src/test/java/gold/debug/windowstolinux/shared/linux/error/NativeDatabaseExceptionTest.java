package gold.debug.windowstolinux.shared.linux.error;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Locale;

import gold.debug.windowstolinux.shared.model.failure.FailureCarrier;
import gold.debug.windowstolinux.shared.model.failure.FailureRecoveryDisposition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class NativeDatabaseExceptionTest {
    @ParameterizedTest
    @EnumSource(NativeDatabaseFailureType.class)
    void allHelperReasonsExposeStructuredUncheckedFailures(NativeDatabaseFailureType reason) {
        var exception = new NativeDatabaseException(reason);
        assertInstanceOf(RuntimeException.class, exception);
        assertInstanceOf(FailureCarrier.class, exception);
        assertSame(reason, exception.reason());
        assertSame(reason, exception.failure().definition());
        assertEquals("linux.database." + reason.name().toLowerCase(Locale.ROOT).replace('_', '-'),
                exception.failure().code());
        assertEquals(reason.messageKey(), exception.failure().userMessage().key());
        assertNotNull(exception.failure().operationIdentity());
        assertEquals(FailureRecoveryDisposition.NOT_ATTEMPTED, exception.failure().recoveryDisposition());
        assertEquals("Native database operation requires attention: " + reason.name(),
                exception.failure().diagnostic());
    }

    @Test
    void aFailureMustHaveAReason() {
        assertThrows(NullPointerException.class, () -> new NativeDatabaseException(null));
    }
}
