package gold.debug.windowstolinux.app.ui.diagnostic;

import gold.debug.windowstolinux.app.service.failure.ApplicationServiceException;
import gold.debug.windowstolinux.app.service.failure.ApplicationServiceFailureType;
import gold.debug.windowstolinux.app.ui.i18n.MessageCatalog;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DesktopFailurePresenterTest {
    @Test void structuredSnapshotsUseTheSameRedactionAndOperationIdentity() {
        var descriptor = ApplicationServiceException.create(ApplicationServiceFailureType.RECOVERY_PROBE_FAILED,
                "password=hidden-value Bearer hidden-bearer").failure();
        var presenter = new DesktopFailurePresenter(MessageCatalog.forLanguageTag("en")::text, FailureReportStore.disabled());
        String text = presenter.present(descriptor);
        assertTrue(text.contains(descriptor.code())); assertTrue(text.contains(descriptor.operationIdentity().toString()));
        assertFalse(text.contains("hidden-value")); assertFalse(text.contains("hidden-bearer"));
    }
    @Test
    void hidesUnknownMessagesAndUnwrapsStructuredTaskFailures() {
        DesktopFailurePresenter presenter = new DesktopFailurePresenter(
                MessageCatalog.forLanguageTag("en")::text, FailureReportStore.disabled());

        String unknown = presenter.present(new RuntimeException("password=do-not-display"));
        assertFalse(unknown.contains("do-not-display"));
        assertTrue(unknown.contains("unexpected") || unknown.contains("No diagnostic"));

        ApplicationServiceException structured = ApplicationServiceException.create(
                ApplicationServiceFailureType.APPLICATION_NOT_SELECTED,
                "Selection failed with api-key=do-not-display");
        String rendered = presenter.present(new CompletionException(structured));
        assertTrue(rendered.contains(structured.failure().code()));
        assertTrue(rendered.contains(structured.failure().operationIdentity().toString()));
        assertTrue(rendered.contains("[REDACTED]"));
        assertFalse(rendered.contains("do-not-display"));
    }
}
