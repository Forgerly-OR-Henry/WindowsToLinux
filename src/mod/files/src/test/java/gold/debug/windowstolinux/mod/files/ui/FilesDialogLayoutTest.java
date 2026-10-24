package gold.debug.windowstolinux.mod.files.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.*;
import java.awt.event.WindowEvent;
import java.nio.file.*;
import java.time.Instant;
import java.util.List;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import javax.imageio.ImageIO;
import javax.swing.*;

import gold.debug.windowstolinux.app.ui.display.DesktopThemeService;
import gold.debug.windowstolinux.app.ui.display.ThemeMode;
import gold.debug.windowstolinux.appapi.file.model.FileInfo;
import gold.debug.windowstolinux.appapi.file.model.FilePath;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.server.model.ServerInfo;
import gold.debug.windowstolinux.appapi.ui.page.UiEnvironment;
import gold.debug.windowstolinux.mod.files.service.FilesRuntimeFixture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Real modal controls, decorated-window bounds and retained-input checks. / 真实模态控件、完整窗框边界及保留输入检查。 */
class FilesDialogLayoutTest {
    @TempDir
    Path temporary;

    @Test
    void actualWorkspaceActionsKeepFileEffectsAndRevokeUnsubmittedInputOnLockAndClose() throws Exception {
        try (var fixture = new FilesRuntimeFixture(temporary, false)) {
            var workspace = new FilesWorkspace(fixture.files());
            JFrame frame = edt(() -> {
                DesktopThemeService.install(ThemeMode.LIGHT, 100);
                var window = new JFrame("Files action verification");
                window.setContentPane((Container) workspace.create(new UiEnvironment(Locale.ENGLISH, "light", false)));
                window.setBounds(60, 60, 1000, 650);
                window.setVisible(true);
                return window;
            });
            try {
                fixture.choose(fixture.local());
                edt(() -> {
                    ((JButton) named(frame, "files.left.local")).doClick(0);
                    return null;
                });
                try {
                    await(() -> edt(() -> ((JTextField) named(frame, "files.left.path")).getText().equals(fixture.local().toString())));
                } catch (AssertionError failure) {
                    throw new AssertionError(edt(() -> ((JTextArea) named(frame, "files.feedback")).getText()), failure);
                }
                var result = new AtomicReference<Object>();
                JDialog create = open(() -> {
                    ((JButton) named(frame, "files.action.file")).doClick(0);
                    return "done";
                }, result);
                edt(() -> {
                    assertEquals("", ((JTextField) named(create, "files.dialog.name")).getText());
                    ((JTextField) named(create, "files.dialog.name")).setText("original.txt");
                    ((JButton) named(create, "files.dialog.ok")).doClick(0);
                    return null;
                });
                await(() -> Files.isRegularFile(fixture.local().resolve("original.txt")));
                await(() -> edt(() -> ((JTable) named(frame, "files.left.table")).getRowCount() == 1));
                fixture.awaitIdle();
                await(() -> edt(workspace::available));
                edt(() -> {
                    ((JTable) named(frame, "files.left.table")).setRowSelectionInterval(0, 0);
                    return null;
                });
                result.set(null);
                JDialog rename = open(() -> {
                    ((JButton) named(frame, "files.action.rename")).doClick(0);
                    return "done";
                }, result);
                edt(() -> {
                    ((JTextField) named(rename, "files.dialog.name")).setText("renamed.txt");
                    ((JButton) named(rename, "files.dialog.ok")).doClick(0);
                    return null;
                });
                await(() -> Files.isRegularFile(fixture.local().resolve("renamed.txt")) && !Files.exists(fixture.local().resolve("original.txt")));
                fixture.awaitIdle();
                await(() -> edt(workspace::available));
                await(() -> edt(() -> "renamed.txt".equals(((JTable) named(frame, "files.left.table")).getValueAt(0, 0))));
                for (boolean close : new boolean[]{false, true}) {
                    result.set(null);
                    JDialog revoked = open(() -> {
                        ((JTable) named(frame, "files.left.table")).setRowSelectionInterval(0, 0);
                        workspace.update();
                        assertTrue(named(frame, "files.action.rename").isEnabled());
                        ((JButton) named(frame, "files.action.rename")).doClick(0);
                        return "done";
                    }, result);
                    edt(() -> {
                        ((JTextField) named(revoked, "files.dialog.name")).setText("revoked.txt");
                        if (close) workspace.close();
                        else workspace.environmentChanged(new UiEnvironment(Locale.SIMPLIFIED_CHINESE, "dark", true));
                        return null;
                    });
                    await(() -> result.get() != null);
                    assertFalse(edt(revoked::isShowing));
                    assertTrue(Files.isRegularFile(fixture.local().resolve("renamed.txt")));
                    assertFalse(Files.exists(fixture.local().resolve("revoked.txt")));
                    if (!close) edt(() -> {
                        workspace.environmentChanged(new UiEnvironment(Locale.ENGLISH, "light", false));
                        return null;
                    });
                }
                fixture.awaitIdle();
            } finally {
                edt(() -> {
                    workspace.close();
                    frame.dispose();
                    return null;
                });
            }
        }
        assertFalse(Thread.getAllStackTraces().keySet().stream().anyMatch(thread -> thread.isAlive() && thread.getName().equals("files-view-observation")));
    }

    @Test
    void allBusinessDialogsFitAcrossLanguageThemeScaleAndRestrictedWorkAreas() throws Exception {
        assertFalse(GraphicsEnvironment.isHeadless());
        Path evidence = Path.of(System.getProperty("files.dialog.evidence", ".ai-workspace/evidence/dialog-unification-20261024/files")).toAbsolutePath();
        Files.createDirectories(evidence);
        StringBuilder geometry = new StringBuilder("variant,dialog,x,y,width,height,workX,workY,workWidth,workHeight,okHeight,cancelHeight\n");
        var observations = observations();
        float originalScale = com.formdev.flatlaf.util.UIScale.getZoomFactor();
        try {
            for (String language : new String[]{"zh-CN", "en"})
                for (ThemeMode theme : new ThemeMode[]{ThemeMode.LIGHT, ThemeMode.DARK})
                    for (int scale : new int[]{100, 125, 150}) {
                        edt(() -> {
                            DesktopThemeService.install(theme, scale);
                            return null;
                        });
                        for (boolean restricted : new boolean[]{false, true}) {
                            String variant = language + "-" + theme + "-" + scale + (restricted ? "-restricted" : "-normal");
                            var root = new JPanel();
                            var messages = new FilesMessages(Locale.forLanguageTag(language));
                            var dialogs = new FilesDialogs(root, messages);
                            JFrame owner = edt(() -> {
                                var frame = new JFrame("Files dialog verification");
                                frame.setContentPane(root);
                                frame.setBounds(80, 80, 850, 600);
                                frame.setVisible(true);
                                return frame;
                            });
                            try {
                                Rectangle actual = edt(() -> FilesDialogLayout.workArea(owner));
                                Rectangle area = restricted
                                    ? new Rectangle(actual.x + 20, actual.y + 20, Math.min(620, actual.width - 40), Math.min(380, actual.height - 40))
                                    : actual;
                                for (String kind : new String[]{"folder", "file", "rename", "replaceLocation", "delete", "remote", "links"}) {
                                    Supplier<Object> action = switch (kind) {
                                        case "replaceLocation" -> dialogs::replaceLocation;
                                        case "delete" -> () -> dialogs.delete(observations);
                                        case "remote" -> () -> dialogs.remote(servers());
                                        case "links" -> () -> dialogs.links(observations);
                                        default -> () -> dialogs.name(kind);
                                    };
                                    var result = new AtomicReference<Object>();
                                    JDialog dialog = open(action, result);
                                    try {
                                        edt(() -> {
                                            FilesDialogLayout.fit(dialog, owner, area);
                                            return null;
                                        });
                                        edt(() -> {
                                            assertLayout(dialog, area);
                                            if (kind.equals("remote")) {
                                                assertEquals(-1, ((JComboBox<?>) named(dialog, "files.dialog.server")).getSelectedIndex());
                                                assertEquals("", ((JTextField) named(dialog, "files.dialog.remotePath")).getText());
                                            }
                                            if (kind.equals("links")) {
                                                var boxes = descendants(dialog).filter(JCheckBox.class::isInstance).map(JCheckBox.class::cast).toList();
                                                boxes.forEach(box -> {
                                                    assertFalse(box.isSelected());
                                                    assertEquals(box.getAccessibleContext().getAccessibleName(), box.getToolTipText());
                                                });
                                                assertEquals(1, descendants(dialog).filter(JScrollPane.class::isInstance).count(), "one business scroll area");
                                                var last = boxes.getLast();
                                                var bodyScroll = (JScrollPane) named(dialog, "files.dialog.body");
                                                bodyScroll.getVerticalScrollBar().setValue(bodyScroll.getVerticalScrollBar().getMaximum());
                                                assertEquals(last.getHeight(), last.getVisibleRect().height, "last observed link can be completely revealed; model="
                                                    + bodyScroll.getVerticalScrollBar().getModel() + "; view=" + bodyScroll.getViewport().getViewSize() + "; last=" + last.getBounds());
                                                var paths = descendants(dialog).filter(JTextArea.class::isInstance).map(JTextArea.class::cast)
                                                    .filter(text -> "files.dialog.linkPath".equals(text.getName())).toList();
                                                for (var pathText : paths) {
                                                    assertEquals(pathText.getClientProperty("files.dialog.linkKey"), pathText.getText());
                                                    assertTrue(pathText.getLineWrap());
                                                    assertTrue(pathText.getHeight() >= pathText.getPreferredSize().height, "complete wrapped link text");
                                                }
                                                assertEquals(paths.getLast().getHeight(), paths.getLast().getVisibleRect().height, "last full path can be completely revealed");
                                            }
                                            Rectangle bounds = dialog.getBounds();
                                            geometry.append(String.join(",", variant, kind, "" + bounds.x, "" + bounds.y, "" + bounds.width, "" + bounds.height,
                                                "" + area.x, "" + area.y, "" + area.width, "" + area.height,
                                                "" + named(dialog, "files.dialog.ok").getHeight(), "" + named(dialog, "files.dialog.cancel").getHeight())).append('\n');
                                            return null;
                                        });
                                        var snapshot = edt(() -> {
                                            var rendered = new java.awt.image.BufferedImage(dialog.getWidth(), dialog.getHeight(), java.awt.image.BufferedImage.TYPE_INT_RGB);
                                            Graphics2D graphics = rendered.createGraphics();
                                            try {
                                                graphics.setColor(dialog.getBackground());
                                                graphics.fillRect(0, 0, rendered.getWidth(), rendered.getHeight());
                                                dialog.printAll(graphics);
                                            } finally {
                                                graphics.dispose();
                                            }
                                            return rendered;
                                        });
                                        ImageIO.write(snapshot, "png", evidence.resolve(variant + "-" + kind + ".png").toFile());
                                        edt(() -> {
                                            ((JButton) named(dialog, "files.dialog.cancel")).doClick(0);
                                            return null;
                                        });
                                        await(() -> result.get() != null);
                                        if (kind.equals("delete") || kind.equals("replaceLocation"))
                                            assertEquals(false, result.get());
                                        else assertEquals(Optional.empty(), result.get());
                                    } finally {
                                        edt(() -> {
                                            dialogs.revoke();
                                            return null;
                                        });
                                    }
                                }
                            } finally {
                                edt(() -> {
                                    dialogs.revoke();
                                    owner.dispose();
                                    return null;
                                });
                            }
                        }
                    }
        } finally {
            edt(() -> {
                com.formdev.flatlaf.util.UIScale.setZoomFactor(originalScale);
                return null;
            });
            Files.writeString(evidence.resolve("geometry.csv"), geometry);
        }
        assertFalse(edt(() -> Arrays.stream(Window.getWindows()).anyMatch(window -> window.isShowing() && "files.dialog".equals(window.getName()))));
    }

    @Test
    void appearanceKeepsDraftSelectionAndUsesFreshLogicalBaselinesWhileRevokeCloseAndCancelStayAbsent() throws Exception {
        var root = new JPanel();
        var messages = new FilesMessages(Locale.ENGLISH);
        var dialogs = new FilesDialogs(root, messages);
        JFrame owner = edt(() -> {
            DesktopThemeService.install(ThemeMode.LIGHT, 100);
            var frame = new JFrame("Retained files input");
            frame.setContentPane(root);
            frame.setBounds(50, 50, 750, 550);
            frame.setVisible(true);
            return frame;
        });
        try {
            var result = new AtomicReference<Object>();
            JDialog remoteDialog = open(() -> dialogs.remote(servers()), result);
            edt(() -> {
                var input = (JTextField) named(remoteDialog, "files.dialog.remotePath");
                var choices = (JComboBox<?>) named(remoteDialog, "files.dialog.server");
                var document = input.getDocument();
                var model = choices.getModel();
                input.setText("/unsubmitted/draft");
                choices.setSelectedIndex(0);
                for (int scale : new int[]{150, 125, 100, 150}) {
                    DesktopThemeService.install(ThemeMode.DARK, scale);
                    messages.update(Locale.SIMPLIFIED_CHINESE);
                    dialogs.environmentChanged();
                    assertSame(document, input.getDocument());
                    assertSame(model, choices.getModel());
                    assertEquals("/unsubmitted/draft", input.getText());
                    assertEquals(0, choices.getSelectedIndex());
                    assertEquals(FilesDialogLayout.scale(20), ((JComponent) remoteDialog.getContentPane()).getInsets().left);
                    assertLayout(remoteDialog, FilesDialogLayout.workArea(owner));
                }
                ((JButton) named(remoteDialog, "files.dialog.ok")).doClick(0);
                return null;
            });
            await(() -> result.get() != null);
            assertEquals(Optional.of(FilePath.remote(servers().getFirst().ref(), "/unsubmitted/draft")), result.get());
            for (int cancellation = 0; cancellation < 3; cancellation++) {
                result.set(null);
                JDialog current = open(() -> dialogs.name("rename"), result);
                int mode = cancellation;
                edt(() -> {
                    ((JTextField) named(current, "files.dialog.name")).setText("revoked.txt");
                    if (mode == 0) dialogs.revoke();
                    else if (mode == 1) current.dispatchEvent(new WindowEvent(current, WindowEvent.WINDOW_CLOSING));
                    else ((JButton) named(current, "files.dialog.cancel")).doClick(0);
                    return null;
                });
                await(() -> result.get() != null);
                assertEquals(Optional.empty(), result.get());
            }
            result.set(null);
            JDialog links = open(() -> dialogs.links(observations()), result);
            edt(() -> {
                var boxes = descendants(links).filter(JCheckBox.class::isInstance).map(JCheckBox.class::cast).toList();
                var label = descendants(links).filter(JTextArea.class::isInstance).map(JTextArea.class::cast)
                    .filter(text -> "files.dialog.linkPath".equals(text.getName())).findFirst().orElseThrow();
                label.dispatchEvent(new java.awt.event.MouseEvent(label, java.awt.event.MouseEvent.MOUSE_CLICKED,
                    System.currentTimeMillis(), 0, 2, 2, 1, false, java.awt.event.MouseEvent.BUTTON1));
                assertTrue(boxes.getFirst().isSelected(), "wrapped label toggles the original checkbox");
                Object model = boxes.getFirst().getModel();
                dialogs.environmentChanged();
                assertSame(model, boxes.getFirst().getModel());
                assertTrue(boxes.getFirst().isSelected());
                ((JButton) named(links, "files.dialog.ok")).doClick(0);
                return null;
            });
            await(() -> result.get() != null);
            assertEquals(Optional.of(List.of(observations().getFirst())), result.get());
        } finally {
            edt(() -> {
                dialogs.revoke();
                owner.dispose();
                DesktopThemeService.install(ThemeMode.LIGHT, 100);
                return null;
            });
        }
    }

    private static void assertLayout(JDialog dialog, Rectangle area) {
        Rectangle safe = new Rectangle(area);
        safe.grow(-20, -20);
        assertTrue(safe.contains(dialog.getBounds()), "whole decorated dialog: " + dialog.getBounds() + " safe: " + safe);
        var content = (JComponent) dialog.getContentPane();
        assertEquals(FilesDialogLayout.scale(20), content.getInsets().top);
        assertEquals(FilesDialogLayout.scale(20), content.getInsets().bottom);
        for (String name : new String[]{"files.dialog.ok", "files.dialog.cancel"}) {
            var button = (JButton) named(dialog, name);
            assertEquals(button.getPreferredSize().height, button.getHeight(), name);
            Rectangle bounds = SwingUtilities.convertRectangle(button.getParent(), button.getBounds(), content);
            assertTrue(new Rectangle(0, 0, content.getWidth(), content.getHeight()).contains(bounds), name + " fully visible");
        }
        descendants(dialog).filter(JTextField.class::isInstance).map(JTextField.class::cast).forEach(field -> {
            assertEquals(field.getPreferredSize().height, field.getHeight(), "natural field height");
            for (var listener : field.getFocusListeners())
                listener.focusGained(new java.awt.event.FocusEvent(field, java.awt.event.FocusEvent.FOCUS_GAINED));
            assertEquals(field.getHeight(), field.getVisibleRect().height, "field can be completely revealed");
        });
        descendants(dialog).filter(JTextArea.class::isInstance).forEach(text ->
            assertEquals(UIManager.getFont("Label.font"), text.getFont(), "shared text font"));
    }

    private static List<FileInfo> observations() {
        return java.util.stream.IntStream.range(0, 24).mapToObj(index -> {
            FilePath path = FilePath.local(Path.of("I:/dialog-verification/" + "long-observed-link-segment-".repeat(6) + index + ".txt"));
            return new FileInfo(path, path, FileInfo.Kind.LINK, 0, Instant.EPOCH, "synthetic-" + index, Optional.of("synthetic-target"), List.of());
        }).toList();
    }

    private static List<ServerInfo> servers() {
        return List.of(new ServerInfo(new ServerId("synthetic-dialog-server"), "Saved display name", "example.invalid", 22, "display-only", Optional.empty(), Optional.empty()));
    }

    private static JDialog open(Supplier<Object> action, AtomicReference<Object> result) throws Exception {
        SwingUtilities.invokeLater(() -> {
            try {
                result.set(action.get());
            } catch (Throwable failure) {
                result.set(failure);
            }
        });
        await(() -> edt(() -> Arrays.stream(Window.getWindows()).anyMatch(window -> window.isShowing() && "files.dialog".equals(window.getName()))) || result.get() != null);
        if (result.get() instanceof Throwable failure) throw new AssertionError("dialog action", failure);
        return edt(() -> Arrays.stream(Window.getWindows()).filter(window -> window.isShowing() && "files.dialog".equals(window.getName())).map(JDialog.class::cast).findFirst().orElseThrow());
    }

    private static Component named(Container root, String name) {
        return descendants(root).filter(component -> name.equals(component.getName())).findFirst().orElseThrow();
    }

    private static java.util.stream.Stream<Component> descendants(Container root) {
        return Arrays.stream(root.getComponents()).flatMap(child -> child instanceof Container container
            ? java.util.stream.Stream.concat(java.util.stream.Stream.of(child), descendants(container)) : java.util.stream.Stream.of(child));
    }

    private static <T> T edt(Callable<T> action) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) return action.call();
        var task = new java.util.concurrent.FutureTask<>(action);
        SwingUtilities.invokeAndWait(task);
        return task.get();
    }

    private static void await(Callable<Boolean> condition) throws Exception {
        long end = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        while (!condition.call()) {
            if (System.nanoTime() > end) fail("Timed out waiting for real modal UI");
            Thread.sleep(15);
        }
    }
}
