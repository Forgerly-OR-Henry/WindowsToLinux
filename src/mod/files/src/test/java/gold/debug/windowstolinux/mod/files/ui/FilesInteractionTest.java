package gold.debug.windowstolinux.mod.files.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.*;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.function.BooleanSupplier;
import javax.swing.*;

import gold.debug.windowstolinux.mod.files.service.FilesRuntimeFixture;
import gold.debug.windowstolinux.appapi.ui.page.UiEnvironment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Real platform file effects initiated by actual Robot keyboard and pointer input. / 由实际 Robot 键盘及指针输入发起的真实平台文件副作用。 */
class FilesInteractionTest {
    @TempDir
    Path temporary;

    @Test
    void keyboardCutPasteRenameDeleteAndBothDragDirectionsUseActualFiles() throws Exception {
        assertFalse(GraphicsEnvironment.isHeadless());
        try (var fixture = new FilesRuntimeFixture(temporary, false)) {
            var source = fixture.local().resolve("source");
            var target = fixture.local().resolve("target");
            Files.createDirectories(source);
            Files.createDirectories(target);
            Files.writeString(source.resolve("alpha.txt"), "alpha");
            Files.writeString(source.resolve("beta.txt"), "beta");
            var workspace = new FilesWorkspace(fixture.files());
            var frame = frame(workspace);
            var robot = new Robot();
            robot.setAutoDelay(65);
            try {
                assertEquals(0, edt(() -> table(frame, "left").getRowCount()));
                assertNull(find(frame, "files.right.table"));
                click(robot, named(frame, "files.second"));
                assertEquals(0, edt(() -> table(frame, "right").getRowCount()));
                choose(robot, fixture, frame, "left", source);
                choose(robot, fixture, frame, "right", target);
                await(() -> table(frame, "left").getRowCount() == 2);
                cell(robot, table(frame, "left"), 0);
                keys(robot, KeyEvent.VK_CONTROL, KeyEvent.VK_X);
                assertTrue(Files.exists(source.resolve("alpha.txt")));
                click(robot, table(frame, "right"));
                keys(robot, KeyEvent.VK_CONTROL, KeyEvent.VK_V);
                await(() -> findDialog("files.dialog.ok") != null);
                click(robot, findDialog("files.dialog.ok"));
                await(() -> Files.exists(target.resolve("alpha.txt")) && !Files.exists(source.resolve("alpha.txt")));
                await(() -> table(frame, "right").getRowCount() == 1 && table(frame, "right").isEnabled());
                cell(robot, table(frame, "right"), 0);
                robot.keyPress(KeyEvent.VK_F2);
                robot.keyRelease(KeyEvent.VK_F2);
                await(() -> findDialog("files.dialog.name") != null);
                assertEquals("", edt(() -> ((JTextField) findDialog("files.dialog.name")).getText()));
                type(robot, "renamed.bin");
                robot.keyPress(KeyEvent.VK_ENTER);
                robot.keyRelease(KeyEvent.VK_ENTER);
                await(() -> Files.exists(target.resolve("renamed.bin")));
                await(() -> table(frame, "right").getRowCount() == 1 && table(frame, "right").isEnabled());
                drag(robot, table(frame, "right"), 0, table(frame, "left"));
                await(() -> findDialog("files.dialog.ok") != null);
                click(robot, findDialog("files.dialog.ok"));
                await(() -> Files.exists(source.resolve("renamed.bin")) && !Files.exists(target.resolve("renamed.bin")));
                await(() -> table(frame, "left").getRowCount() == 2 && table(frame, "left").isEnabled());
                int renamed = row(table(frame, "left"), "renamed.bin");
                drag(robot, table(frame, "left"), renamed, table(frame, "right"));
                await(() -> findDialog("files.dialog.ok") != null);
                click(robot, findDialog("files.dialog.ok"));
                await(() -> Files.exists(target.resolve("renamed.bin")) && !Files.exists(source.resolve("renamed.bin")));
                await(() -> table(frame, "right").getRowCount() == 1 && table(frame, "right").isEnabled());
                cell(robot, table(frame, "right"), 0);
                robot.keyPress(KeyEvent.VK_DELETE);
                robot.keyRelease(KeyEvent.VK_DELETE);
                await(() -> findDialog("files.dialog.ok") != null);
                click(robot, findDialog("files.dialog.ok"));
                await(() -> !Files.exists(target.resolve("renamed.bin")));
                await(() -> table(frame, "right").getRowCount() == 0);
                assertTrue(edt(() -> ((JTextArea) named(frame, "files.feedback")).getText()).contains("SUCCEEDED"));
                fixture.noResources();
            } finally {
                edt(() -> {
                    workspace.close();
                    frame.dispose();
                    return null;
                });
            }
        }
    }

    @Test
    void actualPopupSelectionMultiCopyAndEmptyNameCreationUseTheCorrectPane() throws Exception {
        assertFalse(GraphicsEnvironment.isHeadless());
        try (var fixture = new FilesRuntimeFixture(temporary, false)) {
            var source = Files.createDirectories(fixture.local().resolve("source"));
            var target = Files.createDirectories(fixture.local().resolve("target"));
            Files.writeString(source.resolve("one.txt"), "one");
            Files.writeString(source.resolve("two.txt"), "two");
            var workspace = new FilesWorkspace(fixture.files());
            var frame = frame(workspace);
            var robot = new Robot();
            robot.setAutoDelay(65);
            try {
                click(robot, named(frame, "files.second"));
                choose(robot, fixture, frame, "left", source);
                choose(robot, fixture, frame, "right", target);
                await(() -> table(frame, "left").getRowCount() == 2);
                cell(robot, table(frame, "left"), 0);
                robot.keyPress(KeyEvent.VK_CONTROL);
                cell(robot, table(frame, "left"), 1);
                robot.keyRelease(KeyEvent.VK_CONTROL);
                assertEquals(2, edt(() -> table(frame, "left").getSelectedRowCount()));
                popup(robot, table(frame, "left"), 0);
                await(() -> menu("Copy") != null);
                click(robot, menu("Copy"));
                assertEquals(2, edt(() -> table(frame, "left").getSelectedRowCount()));
                popup(robot, table(frame, "right"), -1);
                await(() -> menu("Paste") != null);
                click(robot, menu("Paste"));
                await(() -> Files.exists(target.resolve("one.txt")) && Files.exists(target.resolve("two.txt")));
                assertEquals("one", Files.readString(source.resolve("one.txt")));
                assertEquals("two", Files.readString(source.resolve("two.txt")));
                await(() -> table(frame, "right").getRowCount() == 2 && table(frame, "right").isEnabled());
                click(robot, named(frame, "files.action.file"));
                await(() -> findDialog("files.dialog.name") != null);
                assertEquals("", edt(() -> ((JTextField) findDialog("files.dialog.name")).getText()));
                type(robot, "empty.custom");
                click(robot, findDialog("files.dialog.ok"));
                awaitOperation(() -> Files.exists(target.resolve("empty.custom")), fixture, workspace,
                    source.resolve("empty.custom"), target.resolve("empty.custom"));
                assertEquals(0, Files.size(target.resolve("empty.custom")));
                assertFalse(Files.exists(source.resolve("empty.custom")));
                await(() -> table(frame, "right").getRowCount() == 3 && table(frame, "right").isEnabled());
                click(robot, named(frame, "files.action.folder"));
                await(() -> findDialog("files.dialog.name") != null);
                type(robot, "folder");
                click(robot, findDialog("files.dialog.ok"));
                await(() -> Files.isDirectory(target.resolve("folder")));
            } finally {
                edt(() -> {
                    workspace.close();
                    frame.dispose();
                    return null;
                });
            }
        }
    }

    @Test
    void actualCopyRefreshPreservesRightActionTargetAndLeftMultiSelection() throws Exception {
        assertFalse(GraphicsEnvironment.isHeadless());
        try (var fixture = new FilesRuntimeFixture(temporary, false)) {
            var source = Files.createDirectories(fixture.local().resolve("source"));
            var target = Files.createDirectories(fixture.local().resolve("target"));
            Files.writeString(source.resolve("one.txt"), "one");
            Files.writeString(source.resolve("two.txt"), "two");
            var workspace = new FilesWorkspace(fixture.files());
            var frame = frame(workspace);
            var robot = new Robot();
            robot.setAutoDelay(65);
            try {
                click(robot, named(frame, "files.second"));
                choose(robot, fixture, frame, "left", source);
                choose(robot, fixture, frame, "right", target);
                await(() -> table(frame, "left").getRowCount() == 2);
                cell(robot, table(frame, "left"), 0);
                robot.keyPress(KeyEvent.VK_CONTROL);
                cell(robot, table(frame, "left"), 1);
                robot.keyRelease(KeyEvent.VK_CONTROL);
                keys(robot, KeyEvent.VK_CONTROL, KeyEvent.VK_C);
                popup(robot, table(frame, "right"), -1);
                await(() -> menu("Paste") != null);
                click(robot, menu("Paste"));
                awaitOperation(() -> Files.exists(target.resolve("one.txt")) && Files.exists(target.resolve("two.txt")),
                    fixture, workspace, source, target);
                await(() -> table(frame, "right").getRowCount() == 2 && table(frame, "left").getSelectedRowCount() == 2);
                assertSame(table(frame, "right"), edt(() -> workspace.active().table()), "Background listing restoration must preserve the actual right-pane input context");
                assertEquals(0, edt(() -> table(frame, "right").getSelectedRowCount()));
                assertEquals("one", Files.readString(source.resolve("one.txt")));
                assertEquals("two", Files.readString(source.resolve("two.txt")));
                fixture.awaitIdle();
            } finally {
                edt(() -> {
                    workspace.close();
                    frame.dispose();
                    return null;
                });
            }
        }
    }

    @Test
    void explicitCloseStopsObservationButAcceptedRenameFinishesUnderOriginalTask() throws Exception {
        assertFalse(GraphicsEnvironment.isHeadless());
        try (var fixture = new FilesRuntimeFixture(temporary, false)) {
            Files.writeString(fixture.local().resolve("before.txt"), "original");
            var entered = new java.util.concurrent.CountDownLatch(1);
            var release = new java.util.concurrent.CountDownLatch(1);
            var workspace = new FilesWorkspace(fixture.files());
            var frame = frame(workspace);
            var robot = new Robot();
            robot.setAutoDelay(65);
            try {
                choose(robot, fixture, frame, "left", fixture.local());
                await(() -> table(frame, "left").getRowCount() == 1);
                fixture.approval((intent, state) -> {
                    entered.countDown();
                    try {
                        assertTrue(release.await(20, java.util.concurrent.TimeUnit.SECONDS));
                    } catch (InterruptedException failure) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(failure);
                    }
                    return true;
                });
                cell(robot, table(frame, "left"), 0);
                robot.keyPress(KeyEvent.VK_F2);
                robot.keyRelease(KeyEvent.VK_F2);
                await(() -> findDialog("files.dialog.name") != null);
                type(robot, "after.txt");
                click(robot, findDialog("files.dialog.ok"));
                assertTrue(entered.await(20, java.util.concurrent.TimeUnit.SECONDS));
                edt(() -> {
                    workspace.close();
                    frame.dispose();
                    return null;
                });
                release.countDown();
                await(() -> Files.exists(fixture.local().resolve("after.txt")) && !Files.exists(fixture.local().resolve("before.txt")));
                assertEquals("original", Files.readString(fixture.local().resolve("after.txt")));
                assertTrue(java.util.Arrays.stream(Thread.getAllStackTraces().keySet().toArray(Thread[]::new))
                    .noneMatch(thread -> thread.isAlive() && thread.getName().equals("files-view-observation")));
            } finally {
                release.countDown();
                edt(() -> {
                    workspace.close();
                    frame.dispose();
                    return null;
                });
            }
        }
    }

    @Test
    void explicitCancelUsesTheAcceptedHandleWhileTheTaskIsWaiting() throws Exception {
        assertFalse(GraphicsEnvironment.isHeadless());
        try (var fixture = new FilesRuntimeFixture(temporary, false)) {
            Files.writeString(fixture.local().resolve("before.txt"), "original");
            var entered = new java.util.concurrent.CountDownLatch(1);
            var release = new java.util.concurrent.CountDownLatch(1);
            var workspace = new FilesWorkspace(fixture.files());
            var frame = frame(workspace);
            var robot = new Robot();
            robot.setAutoDelay(65);
            try {
                choose(robot, fixture, frame, "left", fixture.local());
                await(() -> table(frame, "left").getRowCount() == 1);
                fixture.approval((intent, state) -> {
                    entered.countDown();
                    try {
                        assertTrue(release.await(20, java.util.concurrent.TimeUnit.SECONDS));
                    } catch (InterruptedException failure) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(failure);
                    }
                    return true;
                });
                cell(robot, table(frame, "left"), 0);
                robot.keyPress(KeyEvent.VK_F2);
                robot.keyRelease(KeyEvent.VK_F2);
                await(() -> findDialog("files.dialog.name") != null);
                type(robot, "cancelled.txt");
                click(robot, findDialog("files.dialog.ok"));
                assertTrue(entered.await(20, java.util.concurrent.TimeUnit.SECONDS));
                click(robot, named(frame, "files.cancel"));
                await(() -> ((JTextArea) named(frame, "files.feedback")).getText().contains("CANCEL_REQUESTED"));
                release.countDown();
                await(() -> ((JTextArea) named(frame, "files.feedback")).getText().contains("CANCELLED"));
                assertTrue(Files.exists(fixture.local().resolve("before.txt")));
                assertFalse(Files.exists(fixture.local().resolve("cancelled.txt")));
            } finally {
                release.countDown();
                edt(() -> {
                    workspace.close();
                    frame.dispose();
                    return null;
                });
            }
        }
    }

    @Test
    void lockCancelsTheOriginalDirectoryReadAndFiltersItsLateCallback() throws Exception {
        assertFalse(GraphicsEnvironment.isHeadless());
        try (var fixture = new FilesRuntimeFixture(temporary, false)) {
            Files.writeString(fixture.local().resolve("late.txt"), "late");
            var entered = new java.util.concurrent.CountDownLatch(1);
            var release = new java.util.concurrent.CountDownLatch(1);
            fixture.chooser((task, cancel) -> {
                entered.countDown();
                try {
                    assertTrue(release.await(20, java.util.concurrent.TimeUnit.SECONDS));
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(failure);
                }
                return fixture.local();
            });
            var workspace = new FilesWorkspace(fixture.files());
            var frame = frame(workspace);
            var robot = new Robot();
            robot.setAutoDelay(65);
            try {
                click(robot, named(frame, "files.left.local"));
                assertTrue(entered.await(20, java.util.concurrent.TimeUnit.SECONDS));
                edt(() -> {
                    workspace.environmentChanged(new UiEnvironment(Locale.ENGLISH, "light", true));
                    return null;
                });
                release.countDown();
                fixture.awaitIdle();
                edt(() -> {
                    workspace.environmentChanged(new UiEnvironment(Locale.ENGLISH, "light", false));
                    return null;
                });
                assertEquals(0, edt(() -> table(frame, "left").getRowCount()));
                assertEquals("", edt(() -> ((JTextField) named(frame, "files.left.path")).getText()));
            } finally {
                release.countDown();
                edt(() -> {
                    workspace.close();
                    frame.dispose();
                    return null;
                });
            }
        }
    }

    @Test
    void appearanceLockHideAndExplicitReopenPreserveOnlyTheOriginalRootState() throws Exception {
        assertFalse(GraphicsEnvironment.isHeadless());
        try (var fixture = new FilesRuntimeFixture(temporary, false)) {
            Files.writeString(fixture.local().resolve("draft.txt"), "draft");
            var workspace = new FilesWorkspace(fixture.files());
            var frame = frame(workspace);
            var robot = new Robot();
            robot.setAutoDelay(65);
            try {
                choose(robot, fixture, frame, "left", fixture.local());
                await(() -> table(frame, "left").getRowCount() == 1);
                click(robot, named(frame, "files.second"));
                var right = named(frame, "files.right.table");
                cell(robot, table(frame, "left"), 0);
                var selected = edt(() -> table(frame, "left").getSelectedRows());
                click(robot, named(frame, "files.left.path"));
                keys(robot, KeyEvent.VK_CONTROL, KeyEvent.VK_A);
                type(robot, "unsubmitted");
                for (String theme : new String[]{"light", "dark"})
                    for (Locale language : new Locale[]{Locale.SIMPLIFIED_CHINESE, Locale.ENGLISH}) {
                        edt(() -> {
                            workspace.environmentChanged(new UiEnvironment(language, theme, false));
                            return null;
                        });
                        assertSame(right, named(frame, "files.right.table"));
                        assertArrayEquals(selected, edt(() -> table(frame, "left").getSelectedRows()));
                        assertEquals("unsubmitted", edt(() -> ((JTextField) named(frame, "files.left.path")).getText()));
                    }
                click(robot, named(frame, "files.second"));
                assertNull(find(frame, "files.right.table"));
                click(robot, named(frame, "files.second"));
                assertSame(right, named(frame, "files.right.table"));
                edt(() -> {
                    workspace.environmentChanged(new UiEnvironment(Locale.ENGLISH, "dark", true));
                    return null;
                });
                assertFalse(edt(() -> table(frame, "left").isEnabled()));
                assertFalse(edt(() -> named(frame, "files.action.paste").isEnabled()));
                edt(() -> {
                    workspace.environmentChanged(new UiEnvironment(Locale.ENGLISH, "dark", false));
                    return null;
                });
                click(robot, named(frame, "files.action.rename"));
                await(() -> findDialog("files.dialog.name") != null);
                type(robot, "revoked.txt");
                edt(() -> {
                    workspace.environmentChanged(new UiEnvironment(Locale.SIMPLIFIED_CHINESE, "dark", false));
                    return null;
                });
                assertEquals("revoked.txt", edt(() -> ((JTextField) findDialog("files.dialog.name")).getText()));
                assertEquals("重命名", edt(() -> ((JDialog) SwingUtilities.getWindowAncestor(findDialog("files.dialog.name"))).getTitle()));
                edt(() -> {
                    workspace.environmentChanged(new UiEnvironment(Locale.ENGLISH, "dark", true));
                    return null;
                });
                await(() -> findDialog("files.dialog.name") == null);
                assertTrue(Files.exists(fixture.local().resolve("draft.txt")));
                assertFalse(Files.exists(fixture.local().resolve("revoked.txt")));
                edt(() -> {
                    workspace.close();
                    frame.dispose();
                    return null;
                });
                var fresh = new FilesWorkspace(fixture.files());
                var reopened = frame(fresh);
                try {
                    assertEquals(0, edt(() -> table(reopened, "left").getRowCount()));
                    assertNull(find(reopened, "files.right.table"));
                } finally {
                    edt(() -> {
                        fresh.close();
                        reopened.dispose();
                        return null;
                    });
                }
            } finally {
                edt(() -> {
                    workspace.close();
                    frame.dispose();
                    return null;
                });
            }
        }
    }

    static JFrame frame(FilesWorkspace workspace) throws Exception {
        return edt(() -> {
            var frame = new JFrame("Files actual UI");
            frame.add(workspace.create(new UiEnvironment(Locale.ENGLISH, "light", false)));
            frame.setSize(1000, 650);
            frame.setLocation(100, 80);
            frame.setVisible(true);
            return frame;
        });
    }

    static void choose(Robot robot, FilesRuntimeFixture fixture, JFrame frame, String side, Path path) throws Exception {
        fixture.choose(path);
        click(robot, named(frame, "files." + side + ".local"));
        await(() -> ((JTextField) named(frame, "files." + side + ".path")).getText().equals(path.toString()));
    }

    static JTable table(Container frame, String side) {
        return (JTable) named(frame, "files." + side + ".table");
    }

    static Component named(Container root, String name) {
        var value = find(root, name);
        assertNotNull(value, name);
        return value;
    }

    static Component find(Container root, String name) {
        for (var child : root.getComponents()) {
            if (name.equals(child.getName())) return child;
            if (child instanceof Container nested) {
                var value = find(nested, name);
                if (value != null) return value;
            }
        }
        return null;
    }

    static Component findDialog(String name) {
        for (var window : Window.getWindows())
            if (window.isShowing() && window instanceof JDialog) {
                var value = find(window, name);
                if (value != null) return value;
            }
        return null;
    }

    static JMenuItem menu(String label) {
        for (var window : Window.getWindows())
            if (window.isShowing()) {
                var value = menuIn(window, label);
                if (value != null) return value;
            }
        return null;
    }

    static JMenuItem menuIn(Container parent, String label) {
        for (var component : parent.getComponents()) {
            if (component instanceof JMenuItem item && item.isShowing() && label.equals(item.getText())) return item;
            if (component instanceof Container child) {
                var item = menuIn(child, label);
                if (item != null) return item;
            }
        }
        return null;
    }

    static void popup(Robot robot, JTable table, int row) throws Exception {
        var point = edt(() -> {
            var p = table.getLocationOnScreen();
            return new Point(p.x + 30, p.y + (row < 0 ? Math.max(100, table.getRowHeight() * (table.getRowCount() + 2)) : row * table.getRowHeight() + table.getRowHeight() / 2));
        });
        robot.mouseMove(point.x, point.y);
        robot.mousePress(InputEvent.BUTTON3_DOWN_MASK);
        robot.mouseRelease(InputEvent.BUTTON3_DOWN_MASK);
        robot.waitForIdle();
    }

    static void click(Robot robot, Component component) throws Exception {
        var point = edt(() -> {
            var p = component.getLocationOnScreen();
            return new Point(p.x + component.getWidth() / 2, p.y + component.getHeight() / 2);
        });
        robot.mouseMove(point.x, point.y);
        robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
        robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
        robot.waitForIdle();
    }

    static void cell(Robot robot, JTable table, int row) throws Exception {
        var point = edt(() -> {
            var box = table.getCellRect(row, 0, true);
            var p = table.getLocationOnScreen();
            return new Point(p.x + box.x + 30, p.y + box.y + box.height / 2);
        });
        robot.mouseMove(point.x, point.y);
        robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
        robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
        robot.waitForIdle();
    }

    static void drag(Robot robot, JTable source, int row, JTable target) throws Exception {
        cell(robot, source, row);
        var start = edt(() -> {
            var box = source.getCellRect(row, 0, true);
            var p = source.getLocationOnScreen();
            return new Point(p.x + 30, p.y + box.y + box.height / 2);
        });
        var end = edt(() -> {
            var p = target.getLocationOnScreen();
            return new Point(p.x + target.getWidth() / 2, p.y + Math.max(80, target.getRowHeight() * (target.getRowCount() + 2)));
        });
        robot.mouseMove(start.x, start.y);
        robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
        for (int step = 1; step <= 15; step++)
            robot.mouseMove(start.x + (end.x - start.x) * step / 15, start.y + (end.y - start.y) * step / 15);
        robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
        robot.waitForIdle();
    }

    static int row(JTable table, String name) throws Exception {
        return edt(() -> {
            for (int row = 0; row < table.getRowCount(); row++) if (name.equals(table.getValueAt(row, 0))) return row;
            throw new AssertionError(name);
        });
    }

    static void keys(Robot robot, int modifier, int key) {
        robot.keyPress(modifier);
        robot.keyPress(key);
        robot.keyRelease(key);
        robot.keyRelease(modifier);
        robot.waitForIdle();
    }

    static void type(Robot robot, String text) throws Exception {
        var control = edt(() -> assertInstanceOf(javax.swing.text.JTextComponent.class,
            KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner()));
        assertTrue(control.getName().startsWith("files."));
        var trace = new java.util.concurrent.CopyOnWriteArrayList<String>();
        var window = edt(() -> SwingUtilities.getWindowAncestor(control));
        java.awt.event.AWTEventListener listener = event -> {
            if (event.getSource() instanceof Component component && SwingUtilities.getWindowAncestor(component) == window) {
                if (event instanceof java.awt.event.KeyEvent key)
                    trace.add("key=" + key.getID() + ",code=" + key.getKeyCode() + ",char=" + (int) key.getKeyChar() + ",consumed=" + key.isConsumed() + ",source=" + component.getName());
                else if (event instanceof java.awt.event.FocusEvent focus)
                    trace.add("focus=" + focus.getID() + ",source=" + component.getName());
                else if (event instanceof java.awt.event.InputMethodEvent input)
                    trace.add("inputMethod=" + input.paramString());
            }
        };
        var changes = new javax.swing.event.DocumentListener() {
            @Override
            public void insertUpdate(javax.swing.event.DocumentEvent event) {
                trace.add("insert=" + control.getText());
            }

            @Override
            public void removeUpdate(javax.swing.event.DocumentEvent event) {
                trace.add("remove=" + control.getText());
            }

            @Override
            public void changedUpdate(javax.swing.event.DocumentEvent event) {
                trace.add("change=" + control.getText());
            }
        };
        var context = edt(control::getInputContext);
        Boolean composition = edt(() -> {
            System.out.println("s4.input.before " + inputState(control));
            control.getDocument().addDocumentListener(changes);
            control.enableInputMethods(true);
            if (context == null) return null;
            try {
                boolean previous = context.isCompositionEnabled();
                context.endComposition();
                context.setCompositionEnabled(false);
                return previous;
            } catch (UnsupportedOperationException unsupported) {
                trace.add("composition-control-unavailable=" + unsupported.getClass().getSimpleName());
                return null;
            }
        });
        Toolkit.getDefaultToolkit().addAWTEventListener(listener, AWTEvent.KEY_EVENT_MASK | AWTEvent.FOCUS_EVENT_MASK | AWTEvent.INPUT_METHOD_EVENT_MASK);
        try {
            robot.waitForIdle();
            assertSame(control, edt(() -> KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner()), "Owned input must keep actual keyboard focus after native composition change");
            System.out.println("s4.input.ready " + edt(() -> inputState(control)));
            for (char character : text.toCharArray()) {
                int key = KeyEvent.getExtendedKeyCodeForChar(character);
                robot.keyPress(key);
                robot.keyRelease(key);
            }
            robot.waitForIdle();
        } finally {
            Toolkit.getDefaultToolkit().removeAWTEventListener(listener);
            edt(() -> {
                control.getDocument().removeDocumentListener(changes);
                System.out.println("s4.input.after " + inputState(control));
                trace.forEach(value -> System.out.println("s4.input.event " + value));
                if (context != null && composition != null) context.setCompositionEnabled(composition);
                return null;
            });
        }
    }

    static String inputState(javax.swing.text.JTextComponent control) {
        var focus = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
        var activeWindow = KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow();
        return "name=" + control.getName() + ",focus=" + (focus == null ? "null" : focus.getName())
            + ",window=" + (activeWindow == null ? "null" : activeWindow.getClass().getSimpleName())
            + ",showing=" + control.isShowing() + ",enabled=" + control.isEnabled() + ",text=" + control.getText()
            + ",dot=" + control.getCaret().getDot() + ",mark=" + control.getCaret().getMark()
            + ",inputLocale=" + (control.getInputContext() == null ? "null" : control.getInputContext().getLocale());
    }

    static void await(BooleanSupplier condition) throws Exception {
        long end = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < end) {
            if (edt(condition::getAsBoolean)) return;
            Thread.sleep(25);
        }
        assertTrue(edt(condition::getAsBoolean));
    }

    static void awaitOperation(BooleanSupplier condition, FilesRuntimeFixture fixture, FilesWorkspace workspace, Path... locations) throws Exception {
        try {
            await(condition);
        } catch (AssertionError failure) {
            try {
                System.out.println("s4.operation.active " + edt(() -> workspace.active().directory()));
                for (var location : locations)
                    System.out.println("s4.operation.path " + location + ",exists=" + Files.exists(location));
                for (var task : fixture.runtime().tasks().snapshots()) {
                    System.out.println("s4.operation.task " + task);
                    System.out.println("s4.operation.facts " + fixture.files().observation(task.id()));
                }
                System.out.println("s4.operation.feedback " + edt(() -> ((JTextArea) find((Container) SwingUtilities.getWindowAncestor(workspace.active()), "files.feedback")).getText()));
            } catch (Exception | AssertionError diagnostic) {
                failure.addSuppressed(diagnostic);
            }
            throw failure;
        }
    }

    static <T> T edt(Callable<T> work) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) return work.call();
        var future = new java.util.concurrent.FutureTask<>(work);
        SwingUtilities.invokeAndWait(future);
        return future.get();
    }
}
