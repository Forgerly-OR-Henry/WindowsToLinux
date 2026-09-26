package gold.debug.windowstolinux.app.main.startup;

import static gold.debug.windowstolinux.app.main.startup.PlatformServersWindowTest.dialog;
import static gold.debug.windowstolinux.app.main.startup.PlatformWindowTest.*;
import static org.junit.jupiter.api.Assertions.*;

import java.awt.*;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.*;

import javax.swing.*;

import gold.debug.windowstolinux.app.service.ai.PlatformModelService;
import gold.debug.windowstolinux.app.ui.diagnostic.FailureReportStore;
import gold.debug.windowstolinux.app.ui.display.*;
import gold.debug.windowstolinux.app.ui.i18n.MessageCatalog;
import gold.debug.windowstolinux.app.ui.shell.PlatformFrame;
import gold.debug.windowstolinux.appapi.task.TaskState;
import gold.debug.windowstolinux.appapi.ui.UiEnvironment;
import gold.debug.windowstolinux.shared.model.ai.AiPurposeType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlatformModelsWindowTest {
    @TempDir
    Path root;

    static PlatformFrame window(PlatformRuntime runtime, PlatformModelService service) throws Exception {
        assertFalse(GraphicsEnvironment.isHeadless());
        return onEdt(() -> {
            DesktopThemeService.install(ThemeMode.LIGHT);
            var frame = new PlatformFrame(runtime.security(), MessageCatalog.forLanguageTag("zh-CN"),
                    ThemePalette.light(), new UiEnvironment(Locale.SIMPLIFIED_CHINESE, "LIGHT", false),
                    FailureReportStore.disabled());
            frame.installModels(service);
            ((JTabbedPane) named(frame, "platform.pages")).setSelectedComponent(named(frame, "platform.models"));
            frame.setVisible(true);
            return frame;
        });
    }

    static void form(PlatformFrame frame, PlatformAiServiceTest.Fixture fixture) throws Exception {
        await(() -> named(frame, "ai.open.DEPLOYMENT").isEnabled());
        SwingUtilities.invokeLater(() -> ((JButton) named(frame, "ai.models.add")).doClick(0));
        await(() -> dialog("platform.model.editor") != null);
        onEdt(() -> {
            var editor = dialog("platform.model.editor");
            ((JTextField) named(editor, "model.name")).setText("本机验证模型");
            ((JTextField) named(editor, "model.endpoint")).setText(fixture.uri("/chat").toString());
            ((JTextField) named(editor, "model.model")).setText("fixture-model");
            ((JTextField) named(editor, "model.limit")).setText("128");
            ((JPasswordField) named(editor, "model.key")).setText("fixture-key");
            ((JButton) named(editor, "model.save")).doClick(0);
            assertEquals(0, ((JPasswordField) named(editor, "model.key")).getPassword().length);
            return null;
        });
    }

    @Test
    void actualModelPageVerifiesSavesAndSelectsLanguagePurposeWithoutDeploymentInputs() throws Exception {
        try (var fixture = new PlatformAiServiceTest.Fixture();
                var runtime = PlatformRuntime.open(root, PlatformStartupTest.PROTECTION)) {
            var service = PlatformModelServiceTest.setup(runtime);
            PlatformModelServiceTest.answer(fixture);
            var frame = window(runtime, service);
            try {
                form(frame, fixture);
                await(() -> dialog("platform.model.editor") == null
                        && ((JList<?>) named(frame, "ai.inventory")).getModel().getSize() == 1);
                assertEquals(128,
                        service.outputLimit(service.listAiConfigurations().getFirst().profile().id()).orElseThrow());
                assertTrue(service.listAiPurpose(AiPurposeType.DEPLOYMENT).isEmpty());
                onEdt(() -> {
                    ((JList<?>) named(frame, "ai.inventory")).setSelectedIndex(0);
                    ((JButton) named(frame, "ai.open.DEPLOYMENT")).doClick(0);
                    descendants(frame).filter(JButton.class::isInstance).map(JButton.class::cast)
                            .filter(button -> button.getText().equals("→")).findFirst().orElseThrow().doClick(0);
                    assertEquals(1, ((JList<?>) named(frame, "ai.purpose")).getModel().getSize());
                    capture(frame, "platform-models.png");
                    ((JButton) named(frame, "ai.purpose.save")).doClick(0);
                    return null;
                });
                await(() -> named(frame, "ai.open.DEPLOYMENT").isEnabled()
                        && descendants(frame).noneMatch(component -> "ai.purpose".equals(component.getName())));
                assertEquals(1, service.listAiPurpose(AiPurposeType.DEPLOYMENT).size());
                assertEquals(1, fixture.calls.get());
                onEdt(() -> {
                    ((JButton) named(frame, "security.lock")).doClick(0);
                    assertFalse(frame.getContentPane().isVisible());
                    return null;
                });
                assertThrows(RuntimeException.class, service::listAiConfigurations);
            } finally {
                onEdt(() -> {
                    frame.dispose();
                    return null;
                });
            }
        }
    }

    @Test
    void closingRunningEditorCancelsAndExposesUnknownToExplicitPageRecovery() throws Exception {
        try (var fixture = new PlatformAiServiceTest.Fixture();
                var runtime = PlatformRuntime.open(root, PlatformStartupTest.PROTECTION)) {
            var service = PlatformModelServiceTest.setup(runtime);
            var release = new CountDownLatch(1);
            fixture.handler.set(exchange -> {
                try {
                    release.await(6, TimeUnit.SECONDS);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
                PlatformModelServiceTest.reply(exchange, "late");
            });
            var frame = window(runtime, service);
            try {
                form(frame, fixture);
                assertNotNull(fixture.requests.poll(5, TimeUnit.SECONDS));
                onEdt(() -> {
                    ((JButton) named(dialog("platform.model.editor"), "model.cancel")).doClick(0);
                    return null;
                });
                await(() -> dialog("platform.model.editor") == null && named(frame, "models.reconcile").isEnabled());
                assertEquals(TaskState.UNKNOWN, service.checks().getFirst().state());
                onEdt(() -> {
                    ((JButton) named(frame, "models.reconcile")).doClick(0);
                    return null;
                });
                await(() -> ((JComboBox<?>) named(frame, "models.checks")).getItemCount() == 0);
                assertTrue(service.listAiConfigurations().isEmpty());
                assertEquals(1, fixture.calls.get());
            } finally {
                release.countDown();
                onEdt(() -> {
                    if (dialog("platform.model.editor") != null)
                        dialog("platform.model.editor").dispose();
                    frame.dispose();
                    return null;
                });
            }
        }
    }
}
