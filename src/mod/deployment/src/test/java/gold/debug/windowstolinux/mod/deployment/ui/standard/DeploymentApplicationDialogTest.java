package gold.debug.windowstolinux.mod.deployment.ui.standard;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.InvocationTargetException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.*;

import gold.debug.windowstolinux.app.ui.display.DesktopThemeService;
import gold.debug.windowstolinux.app.ui.display.ThemeMode;
import gold.debug.windowstolinux.appapi.ui.page.UiEnvironment;
import gold.debug.windowstolinux.mod.deployment.DeploymentRuntimeFixture;
import gold.debug.windowstolinux.mod.deployment.service.core.application.DeploymentApplicationsService;
import gold.debug.windowstolinux.mod.deployment.ui.application.DeploymentApplicationsPage;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Actual application inputs remain owned through appearance, lock and close. / 实际应用输入在外观、锁定及关闭间保持归属。 */
class DeploymentApplicationDialogTest extends DeploymentRuntimeFixture {
    @ParameterizedTest
    @CsvSource({"edit,true", "edit,false", "history,true", "history,false", "reattach,true", "reattach,false"})
    void originalInputKeepsDraftAcrossAppearanceAndIsRevokedByLockOrClose(String kind, boolean lock) throws Exception {
        enable();
        var owner = new AtomicReference<JFrame>();
        var page = new AtomicReference<DeploymentApplicationsPage>();
        var decision = new CompletableFuture<Object>();
        try (var runtime = open()) {
            start(runtime, true);
            SwingUtilities.invokeAndWait(() -> {
                DesktopThemeService.install(ThemeMode.LIGHT, 100);
                var application = new DeploymentApplicationsPage(new DeploymentApplicationsService(runtime.modContexts().get(OWNER).context()));
                page.set(application);
                var frame = new JFrame("Application dialog ownership");
                frame.setContentPane(application.create(new UiEnvironment(Locale.SIMPLIFIED_CHINESE, "LIGHT", false)));
                frame.setSize(1280, 720);
                frame.setVisible(true);
                owner.set(frame);
                SwingUtilities.invokeLater(() -> {
                    try {
                        if (kind.equals("edit")) {
                            var method = DeploymentApplicationsPage.class.getDeclaredMethod("editor", String.class, String.class, Optional.class, boolean.class);
                            method.setAccessible(true);
                            decision.complete(method.invoke(application, "original", "WEBSITE", Optional.of("https://example.invalid/"), true));
                        } else {
                            var family = kind.equals("history") ? DeploymentDialogPreview.Kind.APPLICATION_HISTORY : DeploymentDialogPreview.Kind.REATTACHMENT;
                            var environment = new UiEnvironment(Locale.SIMPLIFIED_CHINESE, "LIGHT", false);
                            var body = DeploymentDialogPreview.content(family, environment);
                            var dialog = DeploymentDialogPreview.window(frame.getRootPane(), family, body, DeploymentDialogPreview.messages(family, environment.locale()));
                            var options = DeploymentDialogVisualTest.descendants(dialog).stream().filter(JOptionPane.class::isInstance).map(JOptionPane.class::cast).findFirst().orElseThrow();
                            var method = DeploymentApplicationsPage.class.getDeclaredMethod("decide", JDialog.class, JOptionPane.class, String.class);
                            method.setAccessible(true);
                            decision.complete(method.invoke(application, dialog, options, kind));
                        }
                    } catch (Throwable failure) {
                        decision.completeExceptionally(failure instanceof InvocationTargetException invoked ? invoked.getCause() : failure);
                    }
                });
            });
            var dialog = DeploymentDialogVisualTest.awaitDialog(owner.get());
            SwingUtilities.invokeAndWait(() -> {
                var input = DeploymentDialogVisualTest.descendants(dialog).stream().filter(JTextField.class::isInstance).map(JTextField.class::cast).findFirst().orElse(null);
                var choices = DeploymentDialogVisualTest.descendants(dialog).stream().filter(JComboBox.class::isInstance).map(JComboBox.class::cast).findFirst().orElse(null);
                var document = input == null ? null : input.getDocument();
                if (input != null) input.setText("retained-draft");
                Object selected = choices == null ? null : choices.getSelectedItem();
                var options = DeploymentDialogVisualTest.descendants(dialog).stream().filter(JOptionPane.class::isInstance).map(JOptionPane.class::cast).findFirst().orElseThrow();
                assertEquals(JOptionPane.UNINITIALIZED_VALUE, options.getValue());
                DesktopThemeService.install(ThemeMode.DARK, 125);
                page.get().environmentChanged(new UiEnvironment(Locale.ENGLISH, "DARK", false));
                assertTrue(dialog.isShowing());
                if (input != null) {
                    assertSame(document, input.getDocument());
                    assertEquals("retained-draft", input.getText());
                }
                if (choices != null) assertEquals(selected, choices.getSelectedItem());
                assertEquals(JOptionPane.UNINITIALIZED_VALUE, options.getValue(), "Appearance never supplies approval");
                if (lock) page.get().environmentChanged(new UiEnvironment(Locale.ENGLISH, "DARK", true));
                else page.get().close();
                assertFalse(dialog.isDisplayable());
            });
            Object answer = decision.get(10, TimeUnit.SECONDS);
            if (kind.equals("edit")) assertNull(answer);
            else assertEquals(false, answer);
        } finally {
            SwingUtilities.invokeAndWait(() -> {
                if (page.get() != null) page.get().close();
                if (owner.get() != null) owner.get().dispose();
                DesktopThemeService.install(ThemeMode.LIGHT, 100);
            });
        }
    }
}
