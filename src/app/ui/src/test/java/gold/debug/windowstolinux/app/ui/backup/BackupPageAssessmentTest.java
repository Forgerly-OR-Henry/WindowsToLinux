package gold.debug.windowstolinux.app.ui.backup;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import gold.debug.windowstolinux.app.service.backup.BackupArchiveInspection;
import gold.debug.windowstolinux.app.service.backup.ManagedBackupInputAssessment;
import gold.debug.windowstolinux.app.service.backup.ManagedBackupInputAssessment.MissingInputType;
import gold.debug.windowstolinux.app.service.backup.PreparedBackupCandidate;
import gold.debug.windowstolinux.app.ui.component.DesktopComponentFactory;
import gold.debug.windowstolinux.app.ui.diagnostic.FailureReportStore;
import gold.debug.windowstolinux.app.ui.display.ThemePalette;
import gold.debug.windowstolinux.app.ui.i18n.MessageCatalog;
import gold.debug.windowstolinux.app.ui.i18n.PageMessagePresenter;
import gold.debug.windowstolinux.shared.backup.contract.validation.BackupProvenanceStatus;
import org.junit.jupiter.api.Test;

/** Tests beginner-readable persisted-input assessment output. / 测试小白可读的持久化输入准入输出。 */
class BackupPageAssessmentTest {
    private final BackupPage page = new BackupPage(null, null, new DesktopComponentFactory(ThemePalette.light()),
            new PageMessagePresenter(MessageCatalog.forLanguageTag(MessageCatalog.ENGLISH_TAG),
                    FailureReportStore.disabled()));

    @Test
    void completeSavedInputsStillWarnThatRemoteArchiveReadinessIsUnknown() {
        ManagedBackupInputAssessment assessment = new ManagedBackupInputAssessment("demo", List.of("api"),
                Map.of("api", "a".repeat(64)), List.of(), Map.of());

        String rendered = page.formatAssessment(assessment);

        assertTrue(rendered.contains("Saved desktop inputs are complete."));
        assertTrue(rendered.contains("does not mean remote files, volumes, databases"));
        assertTrue(rendered.contains("api: " + "a".repeat(64)));
    }

    @Test
    void incompleteSavedInputsExplainEachComponentReason() {
        ManagedBackupInputAssessment assessment = new ManagedBackupInputAssessment("demo", List.of("api"), Map.of(),
                List.of(), Map.of("api", List.of(MissingInputType.CURRENT_RELEASE, MissingInputType.REVIEWED_RUNTIME)));

        String rendered = page.formatAssessment(assessment);

        assertTrue(rendered.contains("Saved desktop inputs are incomplete."));
        assertTrue(rendered.contains("No current successful release is saved."));
        assertTrue(rendered.contains("The reviewed runtime definition is missing."));
    }

    @Test
    void authenticatedCandidateOutputContainsOnlyPublicEvidence() {
        BackupArchiveInspection inspection = new BackupArchiveInspection("demo", "3", "2026-08-22T00:00:00Z", 1, 3, 21,
                "b".repeat(64), BackupProvenanceStatus.NOT_PRESENT);
        PreparedBackupCandidate candidate = new PreparedBackupCandidate(inspection,
                Path.of("build", "restore-candidates", "attempt", "demo-" + "b".repeat(16)), 21);

        String rendered = page.formatAuthenticatedCandidate(candidate, 2);

        assertTrue(rendered.contains("Authenticated secret revisions: 2"));
        assertTrue(rendered.contains("Decoded secret values and the entered password were immediately cleared"));
    }
}
