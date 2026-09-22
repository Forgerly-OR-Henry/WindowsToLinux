package gold.debug.windowstolinux.app.main.diagnostic;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import gold.debug.windowstolinux.app.ui.diagnostic.FailureReportStore;
import gold.debug.windowstolinux.app.ui.i18n.MessageCatalog;
import org.junit.jupiter.api.Test;

class DesktopUncaughtFailureBoundaryTest {
    @Test
    void recordsFatalJvmFailureAndRequestsProcessExitWithoutContinuing() {
        AtomicInteger reports = new AtomicInteger();
        FailureReportStore store = failure -> {
            reports.incrementAndGet();
            return Optional.empty();
        };
        AtomicInteger exitCode = new AtomicInteger(-1);
        DesktopUncaughtFailureBoundary boundary = new DesktopUncaughtFailureBoundary(store,
                MessageCatalog.forLanguageTag("en"), exitCode::set);

        boundary.uncaughtException(Thread.currentThread(), new OutOfMemoryError("fixture"));

        assertEquals(1, reports.get());
        assertEquals(70, exitCode.get());
    }
}
