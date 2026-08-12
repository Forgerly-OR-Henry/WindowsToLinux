package gold.debug.windowstolinux.app.ui.shell;

import gold.debug.windowstolinux.app.ui.ai.AiPageState;
import gold.debug.windowstolinux.app.ui.appearance.DesktopAppearance;
import gold.debug.windowstolinux.app.ui.appearance.ThemeMode;
import gold.debug.windowstolinux.app.ui.appearance.ThemePalette;
import gold.debug.windowstolinux.app.ui.component.DesktopComponents;
import gold.debug.windowstolinux.app.ui.deployment.DeploymentPageState;
import gold.debug.windowstolinux.app.ui.i18n.MessageCatalog;
import gold.debug.windowstolinux.app.ui.managed.ManagedPageState;
import gold.debug.windowstolinux.app.ui.server.ServerPageState;
import gold.debug.windowstolinux.app.ui.settings.SettingsPageState;
import gold.debug.windowstolinux.shared.model.security.CredentialStorageMode;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DesktopLanguageSwitchStateTest {
    @Test
    void preservesTheCurrentPageFormsOutputsAndTemporaryStateAcrossALanguageSwitch() {
        DesktopViewState initial = new DesktopViewState(
                "ai",
                new DeploymentPageState("PYTHON_SERVICE", "TCP", "", "200", "12", "7", "",
                        "3.12", "app", "", "", "", "PODMAN", "8080:8080", "", "PORT=8080", "database-password:1", true,
                        true, "deployment diagnostic", null),
                new ServerPageState("server-two", "198.51.100.24", "2222", "deploy",
                        "ssh-secret".toCharArray(), CredentialStorageMode.MASTER_PASSWORD,
                        "master-secret".toCharArray(), "server diagnostic"),
                new ManagedPageState("demo", "lifecycle diagnostic"),
                new AiPageState("https://example.test/v1/chat/completions", "model-x",
                        "api-secret".toCharArray(), CredentialStorageMode.WINDOWS_CREDENTIAL_MANAGER,
                        new char[0], "AI diagnostic"),
                new SettingsPageState());

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
            assertEquals("database-password:1", chineseState.deployment().secretReferences());
            assertEquals("12", chineseState.deployment().healthTimeoutSeconds());
            assertEquals("7", chineseState.deployment().tcpStabilitySeconds());
            assertTrue(chineseState.deployment().rootBuild());
            assertTrue(chineseState.deployment().experimentalAdapterRisk());
            assertEquals("deployment diagnostic", chineseState.deployment().output());
            assertEquals("server-two", chineseState.server().id());
            assertEquals("198.51.100.24", chineseState.server().host());
            assertArrayEquals("ssh-secret".toCharArray(), chineseState.server().password());
            assertArrayEquals("master-secret".toCharArray(), chineseState.server().masterPassword());
            assertEquals("server diagnostic", chineseState.server().output());
            assertEquals("demo", chineseState.managed().applicationId());
            assertEquals("lifecycle diagnostic", chineseState.managed().output());
            assertEquals("model-x", chineseState.ai().model());
            assertArrayEquals("api-secret".toCharArray(), chineseState.ai().apiKey());
            assertEquals("AI diagnostic", chineseState.ai().output());
        } finally {
            chineseState.close();
        }
    }

    private static DesktopViewState restoreAndCapture(String languageTag, DesktopViewState source) {
        AtomicReference<String> restoredPage = new AtomicReference<>("deployment");
        DesktopPageCoordinator pages = new DesktopPageCoordinator(null, null, MessageCatalog.forLanguageTag(languageTag),
                new DesktopAppearance(languageTag, ThemeMode.LIGHT),
                new DesktopComponents(ThemePalette.light()), (frame, appearance) -> { },
                (page, titleKey, descriptionKey) -> restoredPage.set(page));
        pages.restoreViewState(source);
        pages.currentPage(restoredPage.get());
        return pages.captureViewState();
    }
}
