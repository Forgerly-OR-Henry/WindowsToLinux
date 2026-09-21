package gold.debug.windowstolinux.app.ui.shell;

import gold.debug.windowstolinux.app.service.backup.BackupArchiveInspection;
import gold.debug.windowstolinux.app.service.backup.PreparedBackupCandidate;
import gold.debug.windowstolinux.app.ui.ai.AiPageState;
import gold.debug.windowstolinux.app.ui.backup.BackupPageState;
import gold.debug.windowstolinux.app.ui.display.DesktopDisplayConfiguration;
import gold.debug.windowstolinux.app.ui.display.ThemeMode;
import gold.debug.windowstolinux.app.ui.display.ThemePalette;
import gold.debug.windowstolinux.app.ui.component.DesktopComponentFactory;
import gold.debug.windowstolinux.app.ui.deployment.single.DeploymentPageState;
import gold.debug.windowstolinux.app.ui.deployment.multi.MultiComponentFormState;
import gold.debug.windowstolinux.app.ui.deployment.multi.MultiComponentPageState;
import gold.debug.windowstolinux.app.ui.i18n.MessageCatalog;
import gold.debug.windowstolinux.app.ui.managed.ManagedPageState;
import gold.debug.windowstolinux.app.ui.server.ServerPageState;
import gold.debug.windowstolinux.app.ui.setting.SettingPageState;
import gold.debug.windowstolinux.shared.model.security.CredentialStorageMode;
import gold.debug.windowstolinux.shared.ai.collaboration.role.AiCollaborationRoleKind;
import gold.debug.windowstolinux.shared.model.lifecycle.LifecycleAction;
import gold.debug.windowstolinux.shared.backup.contract.validation.BackupProvenanceStatus;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DesktopLanguageSwitchStateTest {
    @Test
    void preservesTheCurrentPageFormsOutputsAndTemporaryStateAcrossALanguageSwitch() {
        BackupArchiveInspection backupInspection = new BackupArchiveInspection(
                "demo", "3", "2026-08-22T00:00:00Z", 1, 3, 21,
                "a".repeat(64), BackupProvenanceStatus.NOT_PRESENT);
        PreparedBackupCandidate backupCandidate = new PreparedBackupCandidate(backupInspection,
                Path.of("build", "restore-candidates", "attempt-1", "demo-" + "a".repeat(16)), 21);
        DesktopViewState initial = new DesktopViewState(
                "ai",
                new DeploymentPageState("PYTHON_SERVICE", "TCP", "", "200", "12", "7", "",
                        "3.12", "app", "", "", "", "PODMAN", "8080:8080", "", "PORT=8080",
                        "POSTGRESQL", "primary|db.example.test|5432|shop|shop|database-password:1|required",
                        "database-password:1", true,
                        true, "deployment diagnostic", null),
                new MultiComponentPageState("C:\\sources\\shop", "shop", "web", "api,web",
                        LifecycleAction.REFRESH_STATUS,
                        new MultiComponentFormState("api", "api", "NODE_SERVICE", "", "", "22", "", "",
                                "TCP", "18081", "200", "20", "1", "", "api/dist", "18081", "",
                                "PORT=18081", "NONE", "", "database-password:1", true, false),
                        java.util.List.of(), "component diagnostic", null, null),
                new ServerPageState("server-two", "198.51.100.24", "2222", "deploy",
                        "ssh-secret".toCharArray(), CredentialStorageMode.MASTER_PASSWORD,
                        "master-secret".toCharArray(), "server diagnostic"),
                new ManagedPageState("demo", "lifecycle diagnostic"),
                new BackupPageState("demo", "server-two", "C:\\backups\\demo.zip", "C:\\backups\\next.zip",
                        "backup diagnostic", backupCandidate, 1, "backup-secret".toCharArray(), "backup-master".toCharArray()),
                new AiPageState(new char[0], "AI diagnostic", gold.debug.windowstolinux.app.ui.ai.AiInventoryState.empty()),
                new SettingPageState());

        DesktopViewState englishState = restoreAndCapture(MessageCatalog.ENGLISH_TAG, initial);
        initial.close();
        DesktopViewState chineseState = restoreAndCapture(MessageCatalog.SIMPLIFIED_CHINESE_TAG, englishState);
        englishState.close();
        try {
            assertEquals("ai", chineseState.page());
            assertEquals("TCP", chineseState.deployment().healthMode());
            assertEquals("PYTHON_SERVICE", chineseState.deployment().projectType());
            assertEquals("3.12", chineseState.deployment().runtimePrimary());
            assertEquals("app", chineseState.deployment().runtimeSecondary());
            assertEquals("", chineseState.deployment().javaVersion());
            assertEquals("PODMAN", chineseState.deployment().containerEngine());
            assertEquals("PORT=8080", chineseState.deployment().configurationEntries());
            assertEquals("POSTGRESQL", chineseState.deployment().databaseMode());
            assertEquals("primary|db.example.test|5432|shop|shop|database-password:1|required",
                    chineseState.deployment().databaseDetails());
            assertEquals("database-password:1", chineseState.deployment().secretReferences());
            assertEquals("12", chineseState.deployment().healthTimeoutSeconds());
            assertEquals("7", chineseState.deployment().tcpStabilitySeconds());
            assertEquals(false, chineseState.deployment().rootBuild(), "historical root build UI state must be cleared");
            assertTrue(chineseState.deployment().experimentalAdapterRisk());
            assertEquals("deployment diagnostic", chineseState.deployment().output());
            assertEquals("shop", chineseState.multiComponent().applicationId());
            assertEquals("api", chineseState.multiComponent().form().componentId());
            assertEquals("NODE_SERVICE", chineseState.multiComponent().form().projectType());
            assertEquals("PORT=18081", chineseState.multiComponent().form().configuration());
            assertEquals("NONE", chineseState.multiComponent().form().databaseMode());
            assertEquals("component diagnostic", chineseState.multiComponent().output());
            assertEquals("server-two", chineseState.server().id());
            assertEquals("198.51.100.24", chineseState.server().host());
            assertArrayEquals("ssh-secret".toCharArray(), chineseState.server().password());
            assertArrayEquals("master-secret".toCharArray(), chineseState.server().masterPassword());
            assertEquals("server diagnostic", chineseState.server().output());
            assertEquals("demo", chineseState.managed().applicationId());
            assertEquals("lifecycle diagnostic", chineseState.managed().output());
            assertEquals("demo", chineseState.backup().applicationId());
            assertEquals("C:\\backups\\demo.zip", chineseState.backup().archivePath());
            assertEquals("backup diagnostic", chineseState.backup().output());
            assertEquals(backupCandidate, chineseState.backup().preparedCandidate());
            assertEquals(1, chineseState.backup().task());
            assertEquals("server-two", chineseState.backup().targetServerId());
            assertArrayEquals("backup-secret".toCharArray(), chineseState.backup().backupPassword());
            assertArrayEquals("backup-master".toCharArray(), chineseState.backup().masterPassword());
            assertEquals("AI diagnostic", chineseState.ai().output());
        } finally {
            chineseState.close();
        }
    }

    private static DesktopViewState restoreAndCapture(String languageTag, DesktopViewState source) {
        AtomicReference<String> restoredPage = new AtomicReference<>("deployment");
        DesktopPageCoordinator pages = new DesktopPageCoordinator(null, null, MessageCatalog.forLanguageTag(languageTag),
                new DesktopDisplayConfiguration(languageTag, ThemeMode.LIGHT),
                new DesktopComponentFactory(ThemePalette.light()), (frame, appearance) -> { },
                (page, titleKey, descriptionKey) -> restoredPage.set(page));
        pages.restoreViewState(source);
        pages.currentPage(restoredPage.get());
        return pages.captureViewState();
    }
}
