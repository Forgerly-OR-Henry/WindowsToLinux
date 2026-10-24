package gold.debug.windowstolinux.app.ui.control;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.*;
import java.awt.event.FocusEvent;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.lang.reflect.Proxy;
import java.nio.file.*;
import java.time.*;
import java.util.List;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import javax.swing.*;

import gold.debug.windowstolinux.app.linux.ssh.recover.BrowserRecoverySnapshot;
import gold.debug.windowstolinux.app.linux.ssh.recover.SshRecoverySession;
import gold.debug.windowstolinux.app.service.server.ServerProfile;
import gold.debug.windowstolinux.app.ui.debug.UiDebugDialog;
import gold.debug.windowstolinux.app.ui.display.*;
import gold.debug.windowstolinux.app.ui.error.PageMessagePresenter;
import gold.debug.windowstolinux.app.ui.language.MessageCatalog;
import gold.debug.windowstolinux.app.ui.mod.ModPageRegistry;
import gold.debug.windowstolinux.app.ui.server.SshRecoveryDialog;
import gold.debug.windowstolinux.app.ui.task.TaskInteractionPresenter;
import gold.debug.windowstolinux.appapi.file.action.DeleteEffect;
import gold.debug.windowstolinux.appapi.file.model.*;
import gold.debug.windowstolinux.appapi.linux.transfer.*;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.action.ActionIntent;
import gold.debug.windowstolinux.appapi.task.model.*;
import gold.debug.windowstolinux.appapi.ui.page.*;
import org.junit.jupiter.api.Test;

/** Actual production prompt layouts with inert review data and no external actions. / 使用无副作用审核数据验证实际生产提示布局。 */
class DesktopDialogCoverageTest {
    private static final String EXACT = "printf '%s\\n' 'exact-reviewed-fixture'\n".repeat(75);

    private static final String EXPLANATION = "Original synthetic review content / 原始模拟审核内容。".repeat(24);

    private static final String[] SWING_KEYS = {"OptionPane.okButtonText", "OptionPane.cancelButtonText", "OptionPane.yesButtonText", "OptionPane.noButtonText"};

    @Test
    void taskRecoveryGalleryAndStoreLayoutsCoverEveryAppearanceAndRestrictedWorkArea() throws Exception {
        matrix(false);
    }

    @Test
    void sshConsentCaptionsAndImpactCancellationFitEveryAppearanceAndRestrictedWorkArea() throws Exception {
        matrix(true);
    }

    private static void matrix(boolean recoveryOnly) throws Exception {
        assertFalse(GraphicsEnvironment.isHeadless());
        Locale originalLocale = JComponent.getDefaultLocale();
        Map<String, Object> originalLabels = new LinkedHashMap<>();
        for (String key : SWING_KEYS) originalLabels.put(key, UIManager.get(key));
        var executor = Executors.newSingleThreadExecutor();
        try {
            for (String language : List.of("zh-CN", "en"))
                for (ThemeMode theme : List.of(ThemeMode.LIGHT, ThemeMode.DARK))
                    for (int scale : new int[]{100, 125, 150})
                        for (boolean restricted : new boolean[]{false, true}) {
                            MessageCatalog messages = MessageCatalog.forLanguageTag(language);
                            String variant = language + "-" + theme + "-" + scale + "-" + (restricted ? "restricted" : "normal");
                            JFrame owner = edt(() -> {
                                DesktopThemeService.install(theme, scale);
                                JComponent.setDefaultLocale(messages.locale());
                                String[] resourceKeys = {"button.ok", "button.cancel", "value.yes", "value.no"};
                                for (int index = 0; index < SWING_KEYS.length; index++)
                                    UIManager.put(SWING_KEYS[index], messages.text(resourceKeys[index]));
                                var frame = new JFrame("Inert production dialog coverage");
                                frame.setBounds(40, 40, 1000, 680);
                                frame.setVisible(true);
                                return frame;
                            });
                            try {
                                if (!recoveryOnly) taskLayouts(owner, messages, executor, variant, restricted);
                                recoveryLayouts(owner, messages, executor, variant, restricted, theme);
                                if (!recoveryOnly)
                                    galleryAndStore(owner, messages, executor, variant, restricted, theme);
                            } finally {
                                edt(() -> {
                                    for (Window child : owner.getOwnedWindows()) child.dispose();
                                    owner.dispose();
                                    return null;
                                });
                            }
                        }
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
            edt(() -> {
                DesktopThemeService.install(ThemeMode.LIGHT, 100);
                JComponent.setDefaultLocale(originalLocale);
                originalLabels.forEach(UIManager::put);
                return null;
            });
        }
    }

    private static void taskLayouts(JFrame owner, MessageCatalog messages, ExecutorService executor, String variant, boolean restricted) throws Exception {
        var presenter = new TaskInteractionPresenter(owner, messages);
        var task = new TaskSnapshot(UUID.randomUUID(), "fixture.dialog", "1.0.0", "dialog-layout", TaskState.RUNNING,
            Optional.empty(), "", Instant.EPOCH, Optional.empty(), List.of());
        var intent = new ActionIntent("fixture-review", List.of("fixture-server"), "/synthetic/target", "fixture-revision", EXACT, EXPLANATION);
        for (boolean secret : new boolean[]{false, true}) {
            var operation = executor.submit(() -> presenter.text(messages.text("task.review.title"), EXPLANATION, secret, task, () -> false));
            JDialog dialog = awaitDialog(owner, operation);
            JTextField input = edt(() -> {
                assertEquals(messages.text("task.review.title"), dialog.getTitle());
                assertButtons(dialog, messages, "button.ok", "button.cancel");
                var field = (JTextField) named(dialog, "task.input.value");
                assertEquals("", field.getText());
                assertEquals(secret, field instanceof JPasswordField);
                field.setText("synthetic-disposable-value");
                check(dialog, (secret ? "task-secret-" : "task-text-") + variant, restricted);
                click(dialog, messages.text("button.cancel"));
                return field;
            });
            assertNull(operation.get(5, TimeUnit.SECONDS));
            assertEquals("", edt(input::getText));
        }
        var approval = executor.submit(() -> presenter.approve(intent, task, () -> false));
        JDialog review = awaitDialog(owner, approval);
        edt(() -> {
            assertEquals(messages.text("task.review.title"), review.getTitle());
            assertButtons(review, messages, "task.review.allow", "task.review.cancel");
            assertEquals(messages.text("task.review.cancel"), review.getRootPane().getDefaultButton().getText());
            var exact = (JTextArea) named(review, "task.review.exactAction");
            assertTrue(exact.getText().endsWith(EXACT));
            var details = (JCheckBox) named(review, "task.review.details");
            assertFalse(details.isSelected());
            check(review, "task-approval-collapsed-" + variant, restricted);
            details.doClick(0);
            assertTrue(details.isSelected());
            assertTrue(exact.isShowing());
            assertTrue(exact.getText().endsWith(EXACT));
            check(review, "task-approval-expanded-" + variant, restricted);
            if (restricted)
                assertTrue(exact.getParent().getHeight() >= exact.getFontMetrics(exact.getFont()).getHeight() * 4,
                    "Expanded original action retains at least four complete lines");
            click(review, messages.text("task.review.cancel"));
            return null;
        });
        assertFalse(approval.get(5, TimeUnit.SECONDS));
        for (boolean recycle : new boolean[]{false, true}) {
            var choices = recycle ? Map.of(DeleteEffect.RECYCLE, intent, DeleteEffect.PERMANENT, intent) : Map.of(DeleteEffect.PERMANENT, intent);
            var deletion = executor.submit(() -> presenter.delete(choices, EXPLANATION, task, () -> false));
            JDialog dialog = awaitDialog(owner, deletion);
            edt(() -> {
                assertEquals(messages.text("task.review.delete.title"), dialog.getTitle());
                assertButtons(dialog, messages, "task.review.delete.permanentChoice", "task.review.delete.declineChoice");
                assertEquals(messages.text("task.review.delete.declineChoice"), reviewDefault(dialog));
                assertTrue(((JTextArea) named(dialog, "task.review.exactAction")).getText().endsWith(EXPLANATION));
                check(dialog, "task-delete-" + (recycle ? "recycle-" : "permanent-") + variant, restricted);
                click(dialog, messages.text("task.review.delete.declineChoice"));
                return null;
            });
            assertNull(deletion.get(5, TimeUnit.SECONDS));
        }
        var from = observation("I:/synthetic-source/" + "long-name-".repeat(20));
        var to = observation("I:/synthetic-target/" + "long-name-".repeat(20));
        for (boolean directory : new boolean[]{false, true}) {
            var conflict = executor.submit(() -> presenter.fileConflict(from, to, directory, task, () -> false));
            JDialog dialog = awaitDialog(owner, conflict);
            edt(() -> {
                assertEquals(messages.text("file.conflict.title"), dialog.getTitle());
                assertButtons(dialog, messages, "file.copy", directory ? "file.merge" : "transfer.overwrite", "file.skip");
                assertEquals(messages.text("file.skip"), reviewDefault(dialog));
                String text = ((JTextArea) named(dialog, "file.conflict.details")).getText();
                assertTrue(text.contains(from.actual().key()) && text.contains(to.actual().key()));
                check(dialog, "task-file-conflict-" + (directory ? "directory-" : "file-") + variant, restricted);
                dialog.dispatchEvent(new WindowEvent(dialog, WindowEvent.WINDOW_CLOSING));
                return null;
            });
            assertEquals(2, conflict.get(5, TimeUnit.SECONDS));
        }
        var request = new TransferRequest(Path.of("I:/synthetic-source/" + "long-transfer-".repeat(18)), "/synthetic/" + "remote-path/".repeat(30), 4096);
        var conflict = executor.submit(() -> presenter.transferConflict(new TransferConflict(request, true, 1024, 2048), new ServerId("fixture-server"), task, () -> false));
        JDialog transfer = awaitDialog(owner, conflict);
        edt(() -> {
            assertEquals(messages.text("transfer.conflict.title"), transfer.getTitle());
            assertButtons(transfer, messages, "transfer.overwrite", "transfer.keepBoth", "file.skip");
            assertEquals(messages.text("file.skip"), reviewDefault(transfer));
            assertTrue(((JTextArea) named(transfer, "transfer.conflict.details")).getText().contains(request.remoteAbsolutePath()));
            check(transfer, "task-transfer-conflict-" + variant, restricted);
            transfer.dispose();
            return null;
        });
        assertEquals(TransferConflictChoice.SKIP, conflict.get(5, TimeUnit.SECONDS));
        var stalled = executor.submit(() -> presenter.transferStalled(new TransferStall(new TransferProgress(UUID.randomUUID(), TransferProgress.Stage.TRANSFERRING, 12, 1024), request, true, Duration.ofSeconds(60)), new ServerId("fixture-server"), task, () -> false));
        JDialog stall = awaitDialog(owner, stalled);
        edt(() -> {
            assertEquals(messages.text("transfer.stall.title"), stall.getTitle());
            assertButtons(stall, messages, "transfer.retry", "transfer.terminate");
            assertEquals(messages.text("transfer.terminate"), reviewDefault(stall));
            assertTrue(((JTextArea) named(stall, "transfer.stall.details")).getText().contains(request.remoteAbsolutePath()));
            check(stall, "task-transfer-stall-" + variant, restricted);
            stall.dispose();
            return null;
        });
        assertEquals(TransferStallChoice.TERMINATE, stalled.get(5, TimeUnit.SECONDS));
        var cancelled = new AtomicBoolean();
        var directory = executor.submit(() -> presenter.select(messages.text("file.conflict.title"), task, cancelled::get));
        JDialog selection = awaitDialog(owner, directory);
        edt(() -> {
            assertButtons(selection, messages, "button.ok", "button.cancel");
            var chooser = (JFileChooser) named(selection, "source.directory");
            assertFalse(chooser.isMultiSelectionEnabled());
            assertFalse(chooser.getControlButtonsAreShown());
            check(selection, "task-directory-" + variant, restricted);
            cancelled.set(true);
            return null;
        });
        assertNull(directory.get(5, TimeUnit.SECONDS));
    }

    private static void recoveryLayouts(JFrame owner, MessageCatalog messages, ExecutorService executor, String variant, boolean restricted, ThemeMode theme) throws Exception {
        var factory = new DesktopPartFactory(theme == ThemeMode.DARK ? ThemePalette.dark() : ThemePalette.light());
        for (boolean command : new boolean[]{false, true}) {
            var closed = new AtomicBoolean();
            var approved = new AtomicBoolean();
            SshRecoverySession session = (SshRecoverySession) Proxy.newProxyInstance(SshRecoverySession.class.getClassLoader(), new Class<?>[]{SshRecoverySession.class}, (proxy, method, args) -> switch (method.getName()) {
                case "snapshot" ->
                    new BrowserRecoverySnapshot("fixture", command ? BrowserRecoverySnapshot.State.AWAITING_CONFIRMATION : BrowserRecoverySnapshot.State.WAITING_TERMINAL,
                        command ? "confirm" : "handoff", command ? Optional.of(new BrowserRecoverySnapshot.Action(EXACT, EXPLANATION, EXPLANATION, true)) : Optional.empty(), "fixture-confirmation", !command, 1, Map.of(), Optional.empty());
                case "terminals" -> List.of();
                case "confirmAction" -> {
                    approved.set(true);
                    throw new AssertionError("Preview must never approve a command");
                }
                case "close" -> {
                    closed.set(true);
                    yield null;
                }
                case "pause", "resume" -> null;
                default -> throw new AssertionError("No terminal binding or external action: " + method.getName());
            });
            JDialog dialog = edt(() -> {
                SshRecoveryDialog.platform(owner.getRootPane(), new ServerProfile("fixture-server", "example.invalid", 22, "fixture", "fixture/credential"), session,
                    () -> fail("No model settings action"), factory, new PageMessagePresenter(messages));
                return Arrays.stream(owner.getOwnedWindows()).filter(Window::isShowing).filter(JDialog.class::isInstance).map(JDialog.class::cast).findFirst().orElseThrow();
            });
            try {
                edt(() -> {
                    assertEquals(messages.text("recovery.title"), dialog.getTitle());
                    assertButtons(dialog, messages, "recovery.refresh", "recovery.bind", "recovery.approve", "recovery.pause", "recovery.resume", "recovery.end");
                    var boxes = descendants(dialog).filter(JCheckBox.class::isInstance).map(JCheckBox.class::cast).toList();
                    boxes.forEach(box -> assertFalse(box.isSelected()));
                    if (command)
                        assertTrue(descendants(dialog).filter(JTextArea.class::isInstance).map(JTextArea.class::cast).anyMatch(text -> text.getText().contains(EXACT)));
                    check(dialog, "ssh-" + (command ? "command-" : "handoff-") + variant, restricted);
                    assertConsentCaptions(dialog, messages);
                    return null;
                });
                if (command) {
                    var operation = executor.submit(() -> edt(() -> {
                        click(dialog, messages.text("recovery.approve"));
                        return null;
                    }));
                    JDialog impact = awaitDialog(dialog, operation);
                    edt(() -> {
                        assertButtons(impact, messages, "button.cancel", "recovery.approve");
                        assertEquals(messages.text("button.cancel"), reviewDefault(impact));
                        assertTrue(descendants(impact).filter(JTextArea.class::isInstance).map(JTextArea.class::cast).anyMatch(text -> text.getText().contains(EXACT)));
                        check(impact, "ssh-command-impact-" + variant, restricted);
                        click(impact, messages.text("button.cancel"));
                        return null;
                    });
                    operation.get(5, TimeUnit.SECONDS);
                    assertFalse(approved.get());
                    assertTrue(edt(() -> descendants(dialog).filter(JCheckBox.class::isInstance).map(JCheckBox.class::cast).noneMatch(AbstractButton::isSelected)));
                }
                edt(() -> {
                    dialog.dispatchEvent(new WindowEvent(dialog, WindowEvent.WINDOW_CLOSING));
                    return null;
                });
                await(closed::get);
                await(() -> edt(() -> !dialog.isShowing()));
            } finally {
                edt(() -> {
                    dialog.dispose();
                    return null;
                });
            }
        }
    }

    private static void assertConsentCaptions(JDialog dialog, MessageCatalog messages) {
        for (String key : List.of("recovery.consent", "recovery.reconciled")) {
            var caption = (JLabel) named(dialog, key + ".caption");
            var choice = (JCheckBox) caption.getLabelFor();
            assertNotNull(choice, "Caption retains its original checkbox association");
            assertEquals(messages.text(key), caption.getAccessibleContext().getAccessibleName());
            assertEquals(messages.text(key), choice.getAccessibleContext().getAccessibleName());
            assertFalse(choice.isSelected());
            if (!caption.isShowing()) continue;
            assertTrue(caption.getHeight() >= caption.getPreferredSize().height, "Complete natural caption height: " + key);
            Object model = choice.getModel();
            for (var listener : choice.getFocusListeners())
                listener.focusGained(new FocusEvent(choice, FocusEvent.FOCUS_GAINED));
            assertEquals(caption.getParent().getHeight(), ((JComponent) caption.getParent()).getVisibleRect().height,
                "Focused checkbox reveals the complete caption row: " + key);
            for (int click = 0; click < 2; click++)
                caption.dispatchEvent(new java.awt.event.MouseEvent(caption,
                    java.awt.event.MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(), 0, 2, 2, 1, false, java.awt.event.MouseEvent.BUTTON1));
            assertSame(model, choice.getModel());
            assertFalse(choice.isSelected(), "Caption toggles the original model and restores its initial choice");
        }
    }

    private static void galleryAndStore(JFrame owner, MessageCatalog messages, ExecutorService executor, String variant, boolean restricted, ThemeMode theme) throws Exception {
        var factory = new DesktopPartFactory(theme == ThemeMode.DARK ? ThemePalette.dark() : ThemePalette.light());
        edt(() -> {
            var host = new ModPageRegistry.PageHost() {
                public void show(ModPageRegistry.PageKey key, PageSpec spec, JComponent component) {
                    fail("No preview instantiation");
                }

                public void focus(ModPageRegistry.PageKey key) {
                    fail("No preview navigation");
                }

                public void remove(ModPageRegistry.PageKey key) {
                }

                public void environment(UiEnvironment environment) {
                }
            };
            var registry = new ModPageRegistry(host, new UiEnvironment(messages.locale(), theme.name(), false));
            registry.installPreviews(host);
            try (var scope = registry.begin("fixture.dialog", false)) {
                scope.registerPreview(new PageSpec("fixture-preview", "fixture.dialog", messages.text("debug.sample.server"), true), () -> {
                    throw new AssertionError("No page creation");
                });
                scope.commit();
                var gallery = new UiDebugDialog(owner, registry, factory, new PageMessagePresenter(messages));
                try {
                    gallery.navigation(List.of(new UiDebugDialog.PageLink("fixture", messages.text("debug.sample.server"), () -> fail("No navigation"))));
                    gallery.setVisible(true);
                    assertEquals(messages.text("settings.debug.open"), gallery.getTitle());
                    assertButtons(gallery, messages, "auto.addServer", "debug.ai.edit", "debug.open");
                    assertEquals(0, ((JList<?>) named(gallery, "debug.previews")).getSelectedIndex());
                    check(gallery, "debug-gallery-" + variant, restricted);
                    ((JList<?>) named(gallery, "debug.previews")).clearSelection();
                    assertFalse(named(gallery, "debug.open").isEnabled());
                } finally {
                    gallery.dispose();
                }
            }
            return null;
        });
        Class<?> type = Class.forName("gold.debug.windowstolinux.app.ui.mod.ModStoreConfirmation");
        var constructor = type.getDeclaredConstructor(DesktopPartFactory.class, PageMessagePresenter.class);
        constructor.setAccessible(true);
        Object confirmation = constructor.newInstance(factory, new PageMessagePresenter(messages));
        var show = type.getDeclaredMethod("show", JComponent.class, JComponent.class);
        show.setAccessible(true);
        JTextArea review = edt(() -> {
            var text = new JTextArea(EXPLANATION + "\n" + EXACT);
            text.setEditable(false);
            text.setName("fixture.store.review");
            return text;
        });
        var operation = executor.submit(() -> edt(() -> show.invoke(confirmation, owner.getRootPane(), new JScrollPane(review))));
        JDialog store = awaitDialog(owner, operation);
        edt(() -> {
            assertEquals(messages.text("store.confirmTitle"), store.getTitle());
            assertButtons(store, messages, "button.cancel", "store.install");
            assertEquals(EXPLANATION + "\n" + EXACT, ((JTextArea) named(store, "fixture.store.review")).getText());
            check(store, "store-confirmation-" + variant, restricted);
            ((JButton) named(store, "store.confirm.cancel")).doClick(0);
            return null;
        });
        assertEquals(false, operation.get(5, TimeUnit.SECONDS));
    }

    private static void check(JDialog dialog, String name, boolean restricted) throws Exception {
        Rectangle area = DesktopDialogs.workArea(dialog);
        if (restricted) area = new Rectangle(area.x, area.y, Math.min(900, area.width), Math.min(580, area.height));
        DesktopDialogs.constrain(dialog, area, dialog.getSize());
        assertTrue(area.contains(dialog.getBounds()), name + " decorated window bounds");
        var actions = descendants(dialog).filter(component -> component instanceof JComponent control
            && ("OptionPane.buttonArea".equals(control.getName()) || Boolean.TRUE.equals(control.getClientProperty("desktop.dialog.actions")))).toList();
        assertFalse(actions.isEmpty(), name + " fixed action region");
        for (Component footer : actions)
            for (Component component : descendants(footer).filter(JButton.class::isInstance).toList()) {
                Rectangle bounds = SwingUtilities.convertRectangle(component.getParent(), component.getBounds(), dialog.getContentPane());
                assertEquals(component.getPreferredSize().height, component.getHeight(), name + " natural action height");
                assertTrue(new Rectangle(0, 0, dialog.getContentPane().getWidth(), dialog.getContentPane().getHeight()).contains(bounds), name + " complete footer " + bounds);
            }
        for (Component component : descendants(dialog).toList())
            if (component instanceof JTextField input && input.isShowing() && SwingUtilities.getAncestorOfClass(JFileChooser.class, input) == null) {
                for (var listener : input.getFocusListeners())
                    listener.focusGained(new FocusEvent(input, FocusEvent.FOCUS_GAINED));
                assertEquals(input.getPreferredSize().height, input.getHeight(), name + " natural input height");
                assertEquals(input.getHeight(), input.getVisibleRect().height, name + " complete focused input");
            }
        String location = System.getProperty("desktop.dialog.coverage.capture");
        if (location != null) {
            Path evidence = Path.of(location);
            Files.createDirectories(evidence);
            var image = new BufferedImage(dialog.getWidth(), dialog.getHeight(), BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = image.createGraphics();
            try {
                dialog.printAll(graphics);
            } finally {
                graphics.dispose();
            }
            ImageIO.write(image, "png", evidence.resolve(name + ".png").toFile());
        }
    }

    private static FileInfo observation(String path) {
        var location = FilePath.local(Path.of(path));
        return new FileInfo(location, location, FileInfo.Kind.FILE, 1024, Instant.EPOCH, "synthetic-identity", Optional.empty(), List.of());
    }

    private static void assertButtons(JDialog dialog, MessageCatalog messages, String... keys) {
        for (String key : keys)
            assertTrue(descendants(dialog).anyMatch(component -> component instanceof JButton button && messages.text(key).equals(button.getText())), "Actual localized footer: " + key);
    }

    private static String reviewDefault(JDialog dialog) {
        return dialog.getRootPane().getDefaultButton().getText();
    }

    private static void click(JDialog dialog, String text) {
        descendants(dialog).filter(JButton.class::isInstance).map(JButton.class::cast).filter(button -> text.equals(button.getText())).findFirst().orElseThrow().doClick(0);
    }

    private static Component named(Container root, String name) {
        return descendants(root).filter(component -> name.equals(component.getName())).findFirst().orElseThrow();
    }

    private static Stream<Component> descendants(Component root) {
        return Stream.concat(Stream.of(root), root instanceof Container container ? Arrays.stream(container.getComponents()).flatMap(DesktopDialogCoverageTest::descendants) : Stream.empty());
    }

    private static JDialog awaitDialog(Window owner, Future<?> operation) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (System.nanoTime() < deadline) {
            JDialog dialog = edt(() -> Arrays.stream(owner.getOwnedWindows()).filter(Window::isShowing).filter(JDialog.class::isInstance).map(JDialog.class::cast).findFirst().orElse(null));
            if (dialog != null) return dialog;
            if (operation.isDone()) operation.get();
            Thread.sleep(10);
        }
        throw new AssertionError("Production modal dialog did not open");
    }

    private static void await(Callable<Boolean> condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (!condition.call()) {
            if (System.nanoTime() > deadline) fail("Timed out waiting for production dialog cleanup");
            Thread.sleep(10);
        }
    }

    private static <T> T edt(Callable<T> action) throws Exception {
        var task = new FutureTask<>(action);
        if (SwingUtilities.isEventDispatchThread()) task.run();
        else SwingUtilities.invokeAndWait(task);
        return task.get();
    }
}
