package gold.debug.windowstolinux.app.ui.diagnostic;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CompletionException;

import gold.debug.windowstolinux.app.service.failure.ApplicationServiceException;
import gold.debug.windowstolinux.app.service.failure.ApplicationServiceFailureType;
import gold.debug.windowstolinux.app.ui.i18n.MessageCatalog;
import org.junit.jupiter.api.Test;

class DesktopFailurePresenterTest {
    @Test
    void publicCredentialProblemsKeepIdentityAndLocalizationWithoutLegacyReports() {
        var identity = java.util.UUID.randomUUID();
        var problem = new gold.debug.windowstolinux.appapi.task.Problem(
                gold.debug.windowstolinux.appapi.task.ProblemCode.IO_FAILURE, "secret.persistence.decrypt-failed",
                "password=private-fixture", java.util.Map.of(), java.util.Optional.of(identity));
        for (var language : java.util.List.of("en", "zh-CN")) {
            var presenter = new DesktopFailurePresenter(MessageCatalog.forLanguageTag(language)::text, failure -> {
                throw new AssertionError("Public diagnostics must not enter the legacy writer");
            });
            String rendered = presenter
                    .present(new CompletionException(new gold.debug.windowstolinux.appapi.task.ApiException(problem)));
            assertTrue(rendered.contains(problem.eventCode()));
            assertTrue(rendered.contains(identity.toString()));
            assertTrue(rendered.contains(language.equals("en") ? "decrypted" : "解密"));
            assertFalse(rendered.contains("private-fixture"));
            var unknown = new gold.debug.windowstolinux.appapi.task.Problem(problem.code(), "fixture.unknown",
                    "api-key=private-fallback", java.util.Map.of(), problem.diagnosticId());
            assertFalse(presenter.present(unknown).contains("private-fallback"));
            assertTrue(presenter.present(unknown).contains("[REDACTED]"));
        }
    }

    @Test
    void structuredSnapshotsUseTheSameRedactionAndOperationIdentity() {
        var descriptor = ApplicationServiceException.create(ApplicationServiceFailureType.RECOVERY_PROBE_FAILED,
                "password=hidden-value Bearer hidden-bearer").failure();
        var presenter = new DesktopFailurePresenter(MessageCatalog.forLanguageTag("en")::text,
                FailureReportStore.disabled());
        String text = presenter.present(descriptor);
        assertTrue(text.contains(descriptor.code()));
        assertTrue(text.contains(descriptor.operationIdentity().toString()));
        assertFalse(text.contains("hidden-value"));
        assertFalse(text.contains("hidden-bearer"));
    }

    @Test
    void hidesUnknownMessagesAndUnwrapsStructuredTaskFailures() {
        DesktopFailurePresenter presenter = new DesktopFailurePresenter(MessageCatalog.forLanguageTag("en")::text,
                FailureReportStore.disabled());

        String unknown = presenter.present(new RuntimeException("password=do-not-display"));
        assertFalse(unknown.contains("do-not-display"));
        assertTrue(unknown.contains("unexpected") || unknown.contains("No diagnostic"));

        ApplicationServiceException structured = ApplicationServiceException.create(
                ApplicationServiceFailureType.APPLICATION_NOT_SELECTED, "Selection failed with api-key=do-not-display");
        String rendered = presenter.present(new CompletionException(structured));
        assertTrue(rendered.contains(structured.failure().code()));
        assertTrue(rendered.contains(structured.failure().operationIdentity().toString()));
        assertTrue(rendered.contains("[REDACTED]"));
        assertFalse(rendered.contains("do-not-display"));
    }
}
