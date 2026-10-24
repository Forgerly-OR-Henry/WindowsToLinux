package gold.debug.windowstolinux.app.ui.control;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.Component;
import java.awt.Container;
import java.awt.Dialog;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.Window;
import java.awt.image.BufferedImage;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import javax.imageio.ImageIO;
import javax.swing.*;

import gold.debug.windowstolinux.app.service.security.DesktopPrivacyState;
import gold.debug.windowstolinux.app.service.security.DesktopSecurityState;
import gold.debug.windowstolinux.app.service.security.SecurityAccess;
import gold.debug.windowstolinux.app.service.server.ServerProfile;
import gold.debug.windowstolinux.app.ui.ai.PlatformModelsPane;
import gold.debug.windowstolinux.app.ui.display.DesktopThemeService;
import gold.debug.windowstolinux.app.ui.display.ThemeMode;
import gold.debug.windowstolinux.app.ui.display.ThemePalette;
import gold.debug.windowstolinux.app.ui.error.PageMessagePresenter;
import gold.debug.windowstolinux.app.ui.language.MessageCatalog;
import gold.debug.windowstolinux.app.ui.server.ServerProfileDialog;
import gold.debug.windowstolinux.app.ui.task.TaskInteractionPresenter;
import gold.debug.windowstolinux.appapi.task.action.ActionIntent;
import gold.debug.windowstolinux.appapi.task.model.TaskSnapshot;
import gold.debug.windowstolinux.appapi.task.model.TaskState;
import org.junit.jupiter.api.Test;

/** Exercises production dialogs at actual FlatLaf zoom rather than font-only scaling. / 以真实 FlatLaf 缩放验证生产弹窗。 */
class DesktopDialogsTest {
    @Test
    void actualEditorsMessagesAndSelectorsFitEveryAppearanceAndRestrictedWorkArea() throws Exception {
        assertFalse(GraphicsEnvironment.isHeadless());
        var originalLocale = JComponent.getDefaultLocale();
        String[] standardKeys = {"OptionPane.okButtonText", "OptionPane.cancelButtonText", "OptionPane.yesButtonText", "OptionPane.noButtonText"};
        Object[] originalLabels = java.util.Arrays.stream(standardKeys).map(UIManager::get).toArray();
        try {
            for (String language : List.of("zh-CN", "en"))
                for (ThemeMode theme : List.of(ThemeMode.LIGHT, ThemeMode.DARK))
                    for (int scale : new int[]{100, 125, 150})
                        for (boolean restricted : new boolean[]{false, true}) {
                            String suffix = language + "-" + theme + "-" + scale + "-" + (restricted ? "restricted" : "normal");
                            onEdt(() -> {
                                JComponent.setDefaultLocale(Locale.forLanguageTag(language));
                                DesktopThemeService.install(theme, scale);
                                var factory = new DesktopPartFactory(theme == ThemeMode.DARK ? ThemePalette.dark() : ThemePalette.light());
                                var messages = new PageMessagePresenter(MessageCatalog.forLanguageTag(language));
                                UIManager.put(standardKeys[0], messages.text("button.ok"));
                                UIManager.put(standardKeys[1], messages.text("button.cancel"));
                                UIManager.put(standardKeys[2], messages.text("value.yes"));
                                UIManager.put(standardKeys[3], messages.text("value.no"));
                                var owner = new JFrame("Dialog layout verification");
                                owner.setBounds(40, 40, 1000, 680);
                                owner.setVisible(true);
                                try {
                                    check(new ServerProfileDialog(owner, (profile, password) -> fail("No save during preview"),
                                            factory, messages, new ServerProfile("fixture", "server-with-a-long-name.example.test", 22,
                                            "operator", "fixture/credential", "Application server"), ignored -> fail("No save")),
                                        "server-" + suffix, restricted);
                                    check(PlatformModelsPane.preview(owner, factory, messages), "model-" + suffix, restricted);
                                    var security = (SecurityAccess) Proxy.newProxyInstance(SecurityAccess.class.getClassLoader(),
                                        new Class<?>[]{SecurityAccess.class}, (proxy, method, args) -> switch (method.getName()) {
                                            case "securityState" ->
                                                new DesktopSecurityState(true, false, true, 3, false);
                                            case "privacyState" -> new DesktopPrivacyState(false, Optional.empty());
                                            default ->
                                                throw new AssertionError("No security action during preview: " + method.getName());
                                        });
                                    var type = Class.forName("gold.debug.windowstolinux.app.ui.window.DesktopUnlockDialog");
                                    var constructor = type.getDeclaredConstructor(JFrame.class, SecurityAccess.class,
                                        PageMessagePresenter.class, Runnable.class, Runnable.class);
                                    constructor.setAccessible(true);
                                    Object unlock = constructor.newInstance(owner, security, messages, (Runnable) () -> {
                                    }, (Runnable) () -> {
                                    });
                                    var field = type.getDeclaredField("dialog");
                                    field.setAccessible(true);
                                    check((JDialog) field.get(unlock), "unlock-" + suffix, restricted);
                                    var pane = new JOptionPane(messages.text("security.plaintext.warning") + "\n\n"
                                        + "Long path: /application/" + "component-with-long-path/".repeat(30),
                                        JOptionPane.WARNING_MESSAGE, JOptionPane.OK_CANCEL_OPTION);
                                    check(DesktopDialogs.create(pane, owner, messages.text("security.settings")),
                                        "confirmation-" + suffix, restricted);
                                    var chooser = new JFileChooser();
                                    chooser.setControlButtonsAreShown(false);
                                    check(DesktopDialogs.create(new JOptionPane(chooser, JOptionPane.QUESTION_MESSAGE,
                                        JOptionPane.OK_CANCEL_OPTION), owner, "Directory"), "directory-" + suffix, restricted);
                                } finally {
                                    for (Window child : owner.getOwnedWindows()) child.dispose();
                                    owner.dispose();
                                }
                                return null;
                            });
                        }
        } finally {
            onEdt(() -> {
                JComponent.setDefaultLocale(originalLocale);
                for (int index = 0; index < standardKeys.length; index++)
                    UIManager.put(standardKeys[index], originalLabels[index]);
                DesktopThemeService.install(ThemeMode.LIGHT, 100);
                return null;
            });
        }
    }

    @Test
    void actualTaskApprovalExpansionAndSecretInputKeepExactDataDefaultsAndCancellation() throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        JFrame owner = onEdt(() -> {
            DesktopThemeService.install(ThemeMode.DARK, 150);
            var frame = new JFrame("Task prompt verification");
            frame.setBounds(40, 40, 1000, 680);
            frame.setVisible(true);
            return frame;
        });
        try {
            var messages = MessageCatalog.forLanguageTag("zh-CN");
            var prompts = new TaskInteractionPresenter(owner, messages);
            var task = new TaskSnapshot(UUID.randomUUID(), "fixture.mod", "1.0.0", "fixture", TaskState.RUNNING,
                Optional.empty(), "", Instant.now(), Optional.empty(), List.of());
            String command = "printf '%s\\n' 'exact-reviewed-value'\n".repeat(120);
            var approval = executor.submit(() -> prompts.approve(new ActionIntent("fixture", List.of("server"),
                "/application/fixture", "revision-1", command, "Review the exact original operation."), task, () -> false));
            JDialog dialog = awaitDialog(owner, approval);
            onEdt(() -> {
                assertEquals(messages.text("task.review.cancel"), dialog.getRootPane().getDefaultButton().getText());
                assertEquals(command, ((JTextArea) named(dialog, "task.review.exactAction")).getText().substring(
                    ((JTextArea) named(dialog, "task.review.exactAction")).getText().length() - command.length()));
                ((JCheckBox) named(dialog, "task.review.details")).doClick(0);
                DesktopDialogs.constrain(dialog, restricted(dialog), dialog.getSize());
                var exact = (JTextArea) named(dialog, "task.review.exactAction");
                assertTrue(((JViewport) exact.getParent()).getHeight() >= exact.getFontMetrics(exact.getFont()).getHeight() * 4,
                    "Expanded exact action must retain at least four readable lines");
                assertActionsVisible(dialog);
                capture(dialog, "task-review-expanded-DARK-150-restricted");
                dialog.dispose();
                return null;
            });
            assertFalse(approval.get(5, TimeUnit.SECONDS));
            var input = executor.submit(() -> prompts.text("Secret input", "Supply an ephemeral value. ".repeat(50),
                true, task, () -> false));
            JDialog secret = awaitDialog(owner, input);
            JPasswordField password = onEdt(() -> {
                var value = (JPasswordField) named(secret, "task.input.value");
                value.setText("disposable-test-value");
                DesktopDialogs.constrain(secret, restricted(secret), secret.getSize());
                assertActionsVisible(secret);
                capture(secret, "task-secret-DARK-150-restricted");
                secret.dispose();
                return value;
            });
            assertNull(input.get(5, TimeUnit.SECONDS));
            assertEquals(0, onEdt(password::getPassword).length);
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
            onEdt(() -> {
                for (Window child : owner.getOwnedWindows()) child.dispose();
                owner.dispose();
                DesktopThemeService.install(ThemeMode.LIGHT, 100);
                return null;
            });
        }
    }

    @Test
    void themeRefreshPreservesInputModelsListenersAndBoundedGeometry() throws Exception {
        onEdt(() -> {
            DesktopThemeService.install(ThemeMode.LIGHT, 100);
            var input = new JTextField("retained draft", 40);
            var choices = new JComboBox<>(new String[]{"one", "two"});
            choices.setSelectedIndex(1);
            var panel = new JPanel(new java.awt.GridLayout(0, 1, 0, 8));
            panel.add(input);
            panel.add(choices);
            var cancel = new JButton("Cancel");
            var actions = new JPanel(new java.awt.FlowLayout());
            actions.add(cancel);
            var dialog = new JDialog();
            DesktopDialogs.install(dialog, panel, actions, 700, 480);
            Object document = input.getDocument(), model = choices.getModel();
            try {
                dialog.setVisible(true);
                DesktopThemeService.apply(ThemeMode.DARK, 150);
                dialog.validate();
                assertSame(document, input.getDocument());
                assertSame(model, choices.getModel());
                assertEquals("retained draft", input.getText());
                assertEquals("two", choices.getSelectedItem());
                DesktopDialogs.constrain(dialog, restricted(dialog), dialog.getSize());
                assertActionsVisible(dialog);
            } finally {
                dialog.dispose();
                DesktopThemeService.install(ThemeMode.LIGHT, 100);
            }
            return null;
        });
    }

    @Test
    void keyboardTraversalRevealsTheWholeInputWithoutMovingTheFooter() throws Exception {
        JTextField[] inputs = new JTextField[12];
        JDialog dialog = onEdt(() -> {
            DesktopThemeService.install(ThemeMode.LIGHT, 150);
            for (int index = 0; index < inputs.length; index++) inputs[index] = new JTextField("Field " + index, 30);
            var actions = new JPanel(new java.awt.FlowLayout());
            actions.add(new JButton("Cancel"));
            var window = new JDialog();
            DesktopDialogs.install(window, DesktopDialogs.fields(inputs), actions, 600, 400);
            window.setVisible(true);
            inputs[0].requestFocusInWindow();
            return window;
        });
        try {
            for (JTextField input : inputs) {
                onEdt(() -> {
                    java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().focusNextComponent();
                    input.requestFocusInWindow();
                    return null;
                });
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                while (!onEdt(input::isFocusOwner) && System.nanoTime() < deadline) Thread.sleep(10);
                onEdt(() -> {
                    assertTrue(input.isFocusOwner(), "Actual focus must enter the input");
                    assertEquals(input.getHeight(), input.getVisibleRect().height, "Focused field must be completely visible");
                    assertActionsVisible(dialog);
                    return null;
                });
            }
        } finally {
            onEdt(() -> {
                dialog.dispose();
                DesktopThemeService.install(ThemeMode.LIGHT, 100);
                return null;
            });
        }
    }

    private static void check(JDialog dialog, String name, boolean limited) throws Exception {
        try {
            dialog.setModalityType(Dialog.ModalityType.MODELESS);
            dialog.setVisible(true);
            Rectangle available = limited ? restricted(dialog) : DesktopDialogs.workArea(dialog);
            DesktopDialogs.constrain(dialog, available, dialog.getSize());
            assertTrue(available.contains(dialog.getBounds()), name + " must remain inside the work area");
            assertActionsVisible(dialog);
            if (name.startsWith("confirmation-") || name.startsWith("directory-")) {
                assertTrue(descendants(dialog).anyMatch(component -> component instanceof JButton button
                    && UIManager.getString("OptionPane.okButtonText").equals(button.getText())));
                assertTrue(descendants(dialog).anyMatch(component -> component instanceof JButton button
                    && UIManager.getString("OptionPane.cancelButtonText").equals(button.getText())));
            }
            capture(dialog, name);
            for (Component component : descendants(dialog).toList())
                if (component instanceof JTextField field && field.isShowing()
                    && SwingUtilities.getAncestorOfClass(JFileChooser.class, field) == null) {
                    field.scrollRectToVisible(new Rectangle(0, 0, field.getWidth(), field.getHeight()));
                    dialog.validate();
                    assertEquals(field.getHeight(), field.getVisibleRect().height,
                        name + " must reveal the complete input " + field.getName());
                }
        } finally {
            dialog.dispose();
        }
    }

    private static Rectangle restricted(JDialog dialog) {
        Rectangle area = DesktopDialogs.workArea(dialog);
        return new Rectangle(area.x, area.y, Math.min(900, area.width), Math.min(580, area.height));
    }

    private static void assertActionsVisible(JDialog dialog) {
        var footers = descendants(dialog).filter(component -> component instanceof JComponent control
            && ("OptionPane.buttonArea".equals(control.getName())
            || Boolean.TRUE.equals(control.getClientProperty("desktop.dialog.actions")))).toList();
        for (Component footer : footers)
            for (Component component : descendants(footer).filter(JButton.class::isInstance).toList()) {
                Rectangle bounds = SwingUtilities.convertRectangle(component.getParent(), component.getBounds(), dialog.getContentPane());
                assertTrue(component.getWidth() > 0 && component.getHeight() > 0);
                assertTrue(new Rectangle(0, 0, dialog.getContentPane().getWidth(), dialog.getContentPane().getHeight()).contains(bounds),
                    ((JButton) component).getText() + " must remain visible in the fixed action region: " + bounds);
            }
    }

    private static Component named(Container root, String name) {
        return descendants(root).filter(component -> name.equals(component.getName())).findFirst().orElseThrow();
    }

    private static Stream<Component> descendants(Component root) {
        return Stream.concat(Stream.of(root), root instanceof Container container
            ? java.util.Arrays.stream(container.getComponents()).flatMap(DesktopDialogsTest::descendants) : Stream.empty());
    }

    private static JDialog awaitDialog(JFrame owner, Future<?> operation) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (System.nanoTime() < deadline) {
            JDialog dialog = onEdt(() -> java.util.Arrays.stream(owner.getOwnedWindows()).filter(Window::isShowing)
                .filter(JDialog.class::isInstance).map(JDialog.class::cast).findFirst().orElse(null));
            if (dialog != null) return dialog;
            if (operation.isDone()) operation.get();
            Thread.sleep(10);
        }
        throw new AssertionError("Production task dialog did not open");
    }

    private static void capture(JDialog dialog, String name) throws Exception {
        String location = System.getProperty("desktop.dialog.capture");
        if (location == null) return;
        Path directory = Path.of(location);
        Files.createDirectories(directory);
        var image = new BufferedImage(dialog.getWidth(), dialog.getHeight(), BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        try {
            dialog.printAll(graphics);
        } finally {
            graphics.dispose();
        }
        ImageIO.write(image, "png", directory.resolve(name + ".png").toFile());
    }

    private static <T> T onEdt(Callable<T> action) throws Exception {
        var future = new FutureTask<>(action);
        if (SwingUtilities.isEventDispatchThread()) future.run();
        else SwingUtilities.invokeAndWait(future);
        return future.get();
    }
}
