package gold.debug.windowstolinux.mod.files.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.*;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.nio.file.*;
import java.util.Set;
import javax.swing.*;

import gold.debug.windowstolinux.app.main.start.PlatformRuntime;
import gold.debug.windowstolinux.app.main.start.ApplicationLauncher;
import gold.debug.windowstolinux.app.main.start.FilePlatformFixture;
import gold.debug.windowstolinux.app.mod.config.ManifestReader;
import gold.debug.windowstolinux.app.service.mod.PlatformModCatalog;
import gold.debug.windowstolinux.app.service.mod.PlatformModIntents;
import gold.debug.windowstolinux.app.ui.display.DesktopDisplayConfig;
import gold.debug.windowstolinux.app.ui.display.ThemeMode;
import gold.debug.windowstolinux.app.ui.error.FailureReportStore;
import gold.debug.windowstolinux.app.ui.language.MessageCatalog;
import gold.debug.windowstolinux.app.ui.mod.ModNavigationPane;
import gold.debug.windowstolinux.app.ui.mod.ModPageRegistry;
import gold.debug.windowstolinux.app.ui.task.DesktopTaskRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Actual CLASS file Mod in the production shell after its physical move. / 整体移动后生产外壳中的真实 CLASS 文件 Mod。 */
class FilesShellTest {
    @TempDir
    Path root;

    @Test
    void actualClassWorkspaceKeepsTwoLocationsAcrossNavigationAppearanceAndLockedInput() throws Exception {
        assertFalse(GraphicsEnvironment.isHeadless());
        Path workspace = Path.of("").toRealPath();
        while (workspace != null && !Files.isRegularFile(workspace.resolve("docs/File.md")))
            workspace = workspace.getParent();
        assertNotNull(workspace);
        final Path actualWorkspace = workspace;
        final var manifest = ManifestReader.read(Files.readAllBytes(actualWorkspace.resolve("src/mod/files/mod.json")));
        final String owner = manifest.id();
        assertTrue(Files.isRegularFile(actualWorkspace.resolve("src/mod/files/target/wtlmod-development.json")));
        assertFalse(Files.exists(workspace.resolve("src/moddev/files")));
        var robot = new Robot();
        robot.setAutoDelay(40);
        float originalScale = com.formdev.flatlaf.util.UIScale.getZoomFactor();
        try {
            for (int scale : new int[]{100, 150, 200})
                for (var theme : new ThemeMode[]{ThemeMode.LIGHT, ThemeMode.DARK})
                    for (String language : new String[]{"zh-CN", "en"}) {
                        String variant = theme + "-" + language + "-" + scale;
                        Path local = Files.createDirectories(root.resolve(variant).resolve("local"));
                        Files.writeString(local.resolve("original.txt"), "original");
                        Path database = root.resolve(variant).resolve("db");
                        final java.util.UUID requestedBoot;
                        try (var initial = PlatformRuntime.open(database, FilePlatformFixture.PROTECTION)) {
                            requestedBoot = initial.persistence().database().bootId();
                            initial.security().setupAndUnlock("files-shell-password".toCharArray(), "files-shell-password".toCharArray());
                            var catalog = new PlatformModCatalog(initial.persistence().database()).refresh(actualWorkspace, false);
                            assertTrue(catalog.rejections().isEmpty(), catalog.rejections().toString());
                            assertTrue(catalog.builds().stream().anyMatch(build -> owner.equals(build.candidate().manifest().id())));
                            initial.started();
                            initial.modIntents().apply(initial.modIntents().review(owner,
                                new PlatformModIntents.Choice(true, java.util.Optional.of(manifest.version().toString())), java.util.Optional.empty()), false);
                        }
                        try (var runtime = PlatformRuntime.open(database, FilePlatformFixture.PROTECTION)) {
                            assertNotEquals(requestedBoot, runtime.persistence().database().bootId());
                            runtime.security().unlock("files-shell-password".toCharArray());
                            var models = runtime.models();
                            var frame = onEdt(() -> {
                                var previous = Set.copyOf(java.util.Arrays.asList(Window.getWindows()));
                                try {
                                    return FilePlatformFixture.window(runtime,
                                        MessageCatalog.forLanguageTag(language), new DesktopDisplayConfig(language, theme, scale, false),
                                        theme, FailureReportStore.disabled(), models);
                                } catch (Exception | Error failure) {
                                    for (var window : Window.getWindows())
                                        if (!previous.contains(window) && window instanceof gold.debug.windowstolinux.app.ui.window.PlatformFrame)
                                            try {
                                                disposeWindow(window);
                                            } catch (Exception | Error cleanup) {
                                                failure.addSuppressed(cleanup);
                                            }
                                    throw failure;
                                }
                            });
                            Throwable failure = null;
                            try {
                                runtime.started();
                                onEdt(() -> {
                                    frame.setSize(1120, 820);
                                    frame.setVisible(true);
                                    return null;
                                });
                                var report = runtime.startDevelopmentMods(actualWorkspace, frame.pages());
                                assertTrue(report.problems().isEmpty(), report.problems().toString());
                                assertEquals(Set.of(owner), report.running());
                                var install = ApplicationLauncher.class.getDeclaredMethod("installModManagement", PlatformRuntime.class,
                                    gold.debug.windowstolinux.app.ui.window.PlatformFrame.class);
                                install.setAccessible(true);
                                install.invoke(null, runtime, frame);
                                await(() -> descendants(frame).anyMatch(c -> "mods.navigation".equals(c.getName()))
                                    && ((ModNavigationPane) named(frame, "mods.navigation")).available());
                                assertTrue(descendants(frame).noneMatch(c -> "files.workspace".equals(c.getName())));
                                onEdt(() -> {
                                    frame.pages().navigateView(new ModPageRegistry.PageKey(owner, "workspace"));
                                    return null;
                                });
                                var original = onEdt(() -> named(frame, "files.workspace"));
                                assertEquals(0, onEdt(() -> ((JTable) named(frame, "files.left.table")).getRowCount()));
                                assertTrue(descendants(frame).noneMatch(c -> "files.right.table".equals(c.getName())));
                                chooseLocal(robot, frame, "left", local);
                                await(() -> ((JTable) named(frame, "files.left.table")).getRowCount() == 1);
                                assertEquals(2, onEdt(() -> ((JTable) named(frame, "files.left.table")).getColumnCount()));
                                click(robot, (JComponent) named(frame, "files.second"));
                                await(() -> descendants(frame).anyMatch(c -> "files.right.table".equals(c.getName())));
                                assertEquals(0, onEdt(() -> ((JTable) named(frame, "files.right.table")).getRowCount()));
                                chooseLocal(robot, frame, "right", local);
                                await(() -> ((JTable) named(frame, "files.right.table")).getRowCount() == 1);
                                onEdt(() -> {
                                    assertTrue(Math.abs(named(frame, "files.left.table").getWidth() - named(frame, "files.right.table").getWidth()) <= 1);
                                    frame.showHome();
                                    frame.pages().navigateView(new ModPageRegistry.PageKey(owner, "workspace"));
                                    assertSame(original, named(frame, "files.workspace"));
                                    assertEquals(local.toString(), ((JTextField) named(frame, "files.left.path")).getText());
                                    assertEquals(local.toString(), ((JTextField) named(frame, "files.right.path")).getText());
                                    frame.setSize(800, 560);
                                    frame.validate();
                                    assertMinimumLayout(frame, variant);
                                    capture(frame, "files-" + variant + "-minimum.png");
                                    assertTrue(named(frame, "files.left.table").getWidth() > 0);
                                    assertTrue(named(frame, "files.right.table").getWidth() > 0);
                                    assertTrue(Math.abs(named(frame, "files.left.table").getWidth() - named(frame, "files.right.table").getWidth()) <= 1);
                                    frame.setSize(1120, 820);
                                    return null;
                                });
                                click(robot, (JComponent) named(frame, "files.left.path"));
                                String draft = onEdt(() -> ((JTextField) named(frame, "files.left.path")).getText());
                                onEdt(() -> {
                                    ((JButton) named(frame, "security.lock")).doClick(0);
                                    return null;
                                });
                                assertFalse(onEdt(() -> named(frame, "files.left.path").isEnabled()));
                                assertFalse(onEdt(() -> named(frame, "files.left.local").isEnabled()));
                                Point lockedScroll = onEdt(() -> {
                                    var pageScroll = (JScrollPane) named(frame, "files.scroll");
                                    assertFalse(pageScroll.isWheelScrollingEnabled());
                                    assertFalse(pageScroll.getVerticalScrollBar().isEnabled());
                                    return pageScroll.getViewport().getViewPosition();
                                });
                                robot.keyPress(KeyEvent.VK_Q);
                                robot.keyRelease(KeyEvent.VK_Q);
                                robot.keyPress(KeyEvent.VK_F2);
                                robot.keyRelease(KeyEvent.VK_F2);
                                robot.keyPress(KeyEvent.VK_DELETE);
                                robot.keyRelease(KeyEvent.VK_DELETE);
                                robot.keyPress(KeyEvent.VK_PAGE_DOWN);
                                robot.keyRelease(KeyEvent.VK_PAGE_DOWN);
                                robot.keyPress(KeyEvent.VK_END);
                                robot.keyRelease(KeyEvent.VK_END);
                                robot.mouseWheel(2);
                                robot.waitForIdle();
                                assertEquals(lockedScroll, onEdt(() -> ((JScrollPane) named(frame, "files.scroll")).getViewport().getViewPosition()));
                                assertEquals(draft, onEdt(() -> ((JTextField) named(frame, "files.left.path")).getText()));
                                assertEquals("original", Files.readString(local.resolve("original.txt")));
                                onEdt(() -> {
                                    frame.showHome();
                                    frame.pages().navigateView(new ModPageRegistry.PageKey(owner, "workspace"));
                                    return null;
                                });
                                assertSame(original, onEdt(() -> named(frame, "files.workspace")));
                                assertFalse(onEdt(() -> named(frame, "files.left.table").isEnabled()));
                                unlockPassword(frame, "files-shell-password");
                                await(() -> runtime.security().securityState().unlocked() && named(frame, "files.left.table").isEnabled());
                                onEdt(() -> {
                                    capture(frame, "files-" + variant + ".png");
                                    return null;
                                });
                                assertTrue(runtime.tasks().snapshots().stream().allMatch(task -> task.state()
                                    == gold.debug.windowstolinux.appapi.task.model.TaskState.ENDED));
                                assertTrue(runtime.modContexts().get(owner).context().identity().schemaVersion().isEmpty());
                                assertEquals(0, FilePlatformFixture.count(runtime.persistence().database(), "SELECT COUNT(*) FROM mod_schema"));
                            } catch (Exception | Error problem) {
                                failure = problem;
                                throw problem;
                            } finally {
                                try {
                                    releaseFrame(frame, runtime);
                                } catch (Exception | Error cleanup) {
                                    if (failure == null) throw cleanup;
                                    failure.addSuppressed(cleanup);
                                }
                            }
                        }
                    }
        } finally {
            com.formdev.flatlaf.util.UIScale.setZoomFactor(originalScale);
        }
        assertFalse(Thread.getAllStackTraces().keySet().stream().anyMatch(t -> t.isAlive() && t.getName().equals("files-view-observation")));
    }

    static void click(Robot robot, JComponent component) throws Exception {
        Point point = onEdt(() -> {
            component.scrollRectToVisible(new Rectangle(0, 0, component.getWidth(), component.getHeight()));
            Point p = component.getLocationOnScreen();
            p.translate(component.getWidth() / 2, component.getHeight() / 2);
            return p;
        });
        robot.mouseMove(point.x, point.y);
        robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
        robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
        robot.waitForIdle();
    }

    static void assertMinimumLayout(Window frame, String variant) throws Exception {
        var root = (JComponent) named(frame, "files.workspace");
        var first = (JButton) named(frame, "files.action.copy");
        reveal(frame, root, first, new Rectangle(0, 0, first.getWidth(), first.getHeight()));
        assertWholeHeight(first, "toolbar button");
        capture(frame, "files-" + variant + "-minimum-toolbar.png");
        for (String side : new String[]{"left", "right"}) {
            var path = (JTextField) named(frame, "files." + side + ".path");
            reveal(frame, root, path, new Rectangle(0, 0, path.getWidth(), path.getHeight()));
            assertWholeHeight(path, side + " path");
        }
        capture(frame, "files-" + variant + "-minimum-path.png");
        for (String side : new String[]{"left", "right"}) {
            var table = (JTable) named(frame, "files." + side + ".table");
            var viewport = (JViewport) table.getParent();
            Rectangle row = table.getCellRect(0, 0, true);
            table.scrollRectToVisible(row);
            var header = table.getTableHeader();
            Rectangle rowAndHeader = SwingUtilities.convertRectangle(table, row, root).union(
                SwingUtilities.convertRectangle(header, new Rectangle(0, 0, header.getWidth(), header.getHeight()), root));
            root.scrollRectToVisible(rowAndHeader);
            frame.validate();
            assertTrue(viewport.getExtentSize().height >= table.getRowHeight(), side + " viewport must fit a whole row");
            assertTrue(viewport.getVisibleRect().height >= table.getRowHeight(), side + " viewport must expose a whole row");
            assertWholeHeight(header, side + " table header");
            assertTrue(table.getVisibleRect().contains(row), side + " first row must be completely visible");
        }
    }

    static void reveal(Window frame, JComponent root, JComponent component, Rectangle area) {
        component.scrollRectToVisible(area);
        root.scrollRectToVisible(SwingUtilities.convertRectangle(component, area, root));
        frame.validate();
    }

    static void assertWholeHeight(JComponent component, String label) {
        assertTrue(component.getHeight() >= component.getPreferredSize().height, label + " must retain its full preferred height");
        assertEquals(0, component.getVisibleRect().y, label + " top must be visible");
        assertEquals(component.getHeight(), component.getVisibleRect().height, label + " must be vertically complete");
    }

    static void chooseLocal(Robot robot, gold.debug.windowstolinux.app.ui.window.PlatformFrame frame, String side, Path local) throws Exception {
        click(robot, (JComponent) named(frame, "files." + side + ".local"));
        await(() -> directoryDialog(frame) != null);
        onEdt(() -> {
            var dialog = directoryDialog(frame);
            assertSame(frame, dialog.getOwner());
            var chooser = (JFileChooser) named(dialog, "source.directory");
            assertEquals(JFileChooser.DIRECTORIES_ONLY, chooser.getFileSelectionMode());
            chooser.setCurrentDirectory(local.getParent().toFile());
            chooser.setSelectedFile(local.toFile());
            return null;
        });
        await(() -> local.toFile().equals(((JFileChooser) named(directoryDialog(frame), "source.directory")).getSelectedFile()));
        var confirm = onEdt(() -> descendants(directoryDialog(frame)).filter(JButton.class::isInstance).map(JButton.class::cast)
            .filter(button -> UIManager.getString("OptionPane.okButtonText").equals(button.getText())).findFirst().orElseThrow());
        click(robot, confirm);
        await(() -> directoryDialog(frame) == null);
        await(() -> local.toString().equals(((JTextField) named(frame, "files." + side + ".path")).getText()));
    }

    static JDialog directoryDialog(Window owner) {
        return java.util.Arrays.stream(owner.getOwnedWindows()).filter(window -> window.isShowing()
                && window instanceof JDialog && "task.interaction.dialog".equals(window.getName())
                && descendants(window).anyMatch(component -> "source.directory".equals(component.getName())))
            .map(JDialog.class::cast).findFirst().orElse(null);
    }

    static void disposeWindow(Window window) {
        for (var owned : window.getOwnedWindows()) disposeWindow(owned);
        window.dispose();
    }

    static void releaseFrame(gold.debug.windowstolinux.app.ui.window.PlatformFrame frame, PlatformRuntime runtime) throws Exception {
        try {
            onEdt(() -> {
                disposeWindow(frame);
                return null;
            });
        } finally {
            await(() -> !DesktopTaskRunner.hasActiveTasks() && runtime.security().securityState().activeTasks() == 0
                && runtime.tasks().snapshots().stream().allMatch(task -> task.state() == gold.debug.windowstolinux.appapi.task.model.TaskState.ENDED));
        }
    }

    static java.util.stream.Stream<Component> descendants(Component component) {
        return java.util.stream.Stream.concat(java.util.stream.Stream.of(component), component instanceof Container container
            ? java.util.stream.Stream.of(container.getComponents()).flatMap(FilesShellTest::descendants) : java.util.stream.Stream.empty());
    }

    static Component named(Component root, String name) {
        return descendants(root).filter(component -> name.equals(component.getName())).findFirst().orElseThrow();
    }

    static <T> T onEdt(java.util.concurrent.Callable<T> action) throws Exception {
        var result = new java.util.concurrent.FutureTask<>(action);
        SwingUtilities.invokeAndWait(result);
        return result.get();
    }

    static void await(java.util.function.BooleanSupplier condition) throws Exception {
        long until = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < until) {
            if (onEdt(condition::getAsBoolean)) return;
            Thread.sleep(25);
        }
        assertTrue(onEdt(condition::getAsBoolean));
    }

    static void unlockPassword(gold.debug.windowstolinux.app.ui.window.PlatformFrame frame, String password) throws Exception {
        SwingUtilities.invokeLater(() -> ((JButton) named(frame, "security.lock")).doClick(0));
        await(() -> java.util.Arrays.stream(frame.getOwnedWindows()).anyMatch(window -> window.isShowing()
            && "security.unlockDialog".equals(window.getName())));
        onEdt(() -> {
            var dialog = java.util.Arrays.stream(frame.getOwnedWindows()).filter(window -> window.isShowing()
                && "security.unlockDialog".equals(window.getName())).findFirst().orElseThrow();
            ((JPasswordField) named(dialog, "security.password")).setText(password);
            ((JButton) named(dialog, "security.submit")).doClick(0);
            return null;
        });
    }

    static void capture(Window window, String name) throws Exception {
        window.validate();
        var bitmap = new java.awt.image.BufferedImage(window.getWidth(), window.getHeight(), java.awt.image.BufferedImage.TYPE_INT_RGB);
        var graphics = bitmap.createGraphics();
        try {
            window.paint(graphics);
        } finally {
            graphics.dispose();
        }
        Path output = Path.of("target/phase13-files", name);
        Files.createDirectories(output.getParent());
        assertTrue(javax.imageio.ImageIO.write(bitmap, "png", output.toFile()));
    }
}
