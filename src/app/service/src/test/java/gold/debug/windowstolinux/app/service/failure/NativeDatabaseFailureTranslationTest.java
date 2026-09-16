package gold.debug.windowstolinux.app.service.failure;

import gold.debug.windowstolinux.shared.linux.error.NativeDatabaseException;
import gold.debug.windowstolinux.shared.linux.error.NativeDatabaseFailureType;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

class NativeDatabaseFailureTranslationTest {
    @ParameterizedTest
    @EnumSource(NativeDatabaseFailureType.class)
    void allSixReasonsRetainTheirServiceCodeAndRecoveryAction(NativeDatabaseFailureType reason) {
        var nativeFailure = new NativeDatabaseException(reason);
        var serviceFailure = ApplicationServiceException.nativeDatabase(nativeFailure);
        var expected = ApplicationServiceFailureType.valueOf("DATABASE_" + reason.name());
        assertSame(expected, serviceFailure.failure().definition());
        assertSame(nativeFailure, serviceFailure.getCause());
        assertEquals("service.database." + reason.name().toLowerCase(Locale.ROOT).replace('_', '-'), serviceFailure.failure().code());
        assertEquals(expected.messageKey(), serviceFailure.failure().userMessage().key());
        assertEquals(expected.recoveryAction(), nativeFailure.failure().recoveryAction());
        assertEquals(expected.recoveryAction(), serviceFailure.failure().recoveryAction());
        assertEquals(expected.severity(), reason.severity());
    }
}
