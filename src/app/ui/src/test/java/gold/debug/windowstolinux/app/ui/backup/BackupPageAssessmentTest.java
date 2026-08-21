package gold.debug.windowstolinux.app.ui.backup;

import gold.debug.windowstolinux.app.service.backup.ManagedBackupInputAssessment;
import gold.debug.windowstolinux.app.service.backup.ManagedBackupInputAssessment.MissingInputType;
import gold.debug.windowstolinux.app.ui.component.DesktopComponentFactory;
import gold.debug.windowstolinux.app.ui.diagnostic.FailureReportStore;
import gold.debug.windowstolinux.app.ui.display.ThemePalette;
import gold.debug.windowstolinux.app.ui.i18n.MessageCatalog;
import gold.debug.windowstolinux.app.ui.i18n.PageMessagePresenter;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests beginner-readable persisted-input assessment output. / 测试小白可读的持久化输入准入输出。 */
class BackupPageAssessmentTest {
    private final BackupPage page = new BackupPage(null, null,
            new DesktopComponentFactory(ThemePalette.light()),
            new PageMessagePresenter(MessageCatalog.forLanguageTag(MessageCatalog.ENGLISH_TAG),
                    FailureReportStore.disabled()));

    @Test
    void completeSavedInputsStillWarnThatRemoteArchiveReadinessIsUnknown() {
        ManagedBackupInputAssessment assessment = new ManagedBackupInputAssessment(
                "demo", List.of("api"), Map.of("api", "a".repeat(64)), List.of(), Map.of());

        String rendered = page.formatAssessment(assessment);

        assertTrue(rendered.contains("Saved desktop inputs are complete."));
        assertTrue(rendered.contains("does not mean remote files, volumes, databases"));
        assertTrue(rendered.contains("api: " + "a".repeat(64)));
    }

    @Test
    void incompleteSavedInputsExplainEachComponentReason() {
        ManagedBackupInputAssessment assessment = new ManagedBackupInputAssessment(
                "demo", List.of("api"), Map.of(), List.of(),
                Map.of("api", List.of(MissingInputType.CURRENT_RELEASE,
                        MissingInputType.REVIEWED_RUNTIME)));

        String rendered = page.formatAssessment(assessment);

        assertTrue(rendered.contains("Saved desktop inputs are incomplete."));
        assertTrue(rendered.contains("No current successful release is saved."));
        assertTrue(rendered.contains("The reviewed runtime definition is missing."));
    }
}
