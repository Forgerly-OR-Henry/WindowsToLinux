package gold.debug.windowstolinux.app.main.start;

import static org.junit.jupiter.api.Assertions.*;
import static gold.debug.windowstolinux.app.main.start.PlatformWindowTest.*;
import static gold.debug.windowstolinux.app.main.start.ModStoreWindowTest.click;
import static gold.debug.windowstolinux.app.main.start.ModStoreWindowTest.combo;
import static gold.debug.windowstolinux.app.main.start.ModStoreWindowTest.dispose;
import static gold.debug.windowstolinux.app.main.start.ModStoreWindowTest.type;

import java.awt.*;
import java.awt.event.KeyEvent;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.swing.*;

import gold.debug.windowstolinux.app.config.AppConfig;
import gold.debug.windowstolinux.app.main.runtime.RunModeResolver.*;
import gold.debug.windowstolinux.app.mod.config.ManifestReader;
import gold.debug.windowstolinux.app.mod.config.ModManifest;
import gold.debug.windowstolinux.app.service.mod.PlatformModCatalog;
import gold.debug.windowstolinux.app.service.mod.PlatformModIntents;
import gold.debug.windowstolinux.app.ui.display.*;
import gold.debug.windowstolinux.app.ui.error.FailureReportStore;
import gold.debug.windowstolinux.app.ui.language.MessageCatalog;
import gold.debug.windowstolinux.app.ui.mod.ModNavigationPane;
import gold.debug.windowstolinux.app.ui.mod.ModPageRegistry.PageKey;
import gold.debug.windowstolinux.app.ui.task.DesktopTaskRunner;
import gold.debug.windowstolinux.app.ui.window.PlatformFrame;
import gold.debug.windowstolinux.appapi.mod.lifecycle.ModLaunchMode;
import gold.debug.windowstolinux.appapi.mod.service.AvailabilityState;
import gold.debug.windowstolinux.appapi.task.model.TaskState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Actual CLASS dual-boot composition of deployment, Files and the optional Git service. / 部署、文件及可选 Git 服务的真实 CLASS 双启动装配。 */
class PlatformClassWorkspacesIntegrationTest {
    private static final String PREFIX = "gold.debug.windowstolinux.mod.";

    private static final String DEPLOYMENT = PREFIX + "deployment";

    private static final String FILES = PREFIX + "files";

    private static final String GIT = PREFIX + "git";

    private static final String PASSWORD = "class-workspaces-password";

    private static final String DEPLOYMENT_DRAFT = "deployment-retained-draft";

    private static final String FILES_DRAFT = "files-retained-draft";

    @TempDir
    Path isolated;

    @Test
    void actualDualBootRunsThreeModsAndKeepsTwoNativeWorkspacesUntilExplicitClose() throws Exception {
        assertFalse(GraphicsEnvironment.isHeadless());
        Path workspace = workspace();
        var manifests = manifests(workspace);
        var layout = new RuntimeLayout(RunMode.RUN_CLASS, workspace, isolated.resolve("data"));
        UUID firstBoot = enableOnFirstBoot(layout, manifests);
        Path directory = Files.createDirectories(isolated.resolve("actual-local-directory"));
        Path original = directory.resolve("original.txt");
        Files.writeString(original, "unchanged actual local file");
        float originalScale = com.formdev.flatlaf.util.UIScale.getZoomFactor();
        var runtime = PlatformRuntime.open(layout, FilePlatformFixture.PROTECTION, AppConfig.defaultsConfig());
        PlatformFrame frame = null;
        Throwable failure = null;
        try (runtime) {
            try {
                assertNotEquals(firstBoot, runtime.persistence().database().bootId());
                runtime.security().unlock(PASSWORD.toCharArray());
                var models = runtime.models();
                frame = onEdt(() -> createWindow(runtime, models));
                final PlatformFrame window = frame;
                runtime.started();
                onEdt(() -> {
                    window.setVisible(true);
                    return null;
                });
                var report = ApplicationLauncher.startMods(runtime, layout, window.pages());
                assertTrue(report.problems().isEmpty(), report.problems().toString());
                assertEquals(Set.of(DEPLOYMENT, FILES, GIT), report.running());
                ApplicationLauncher.installModManagement(runtime, window);
                onEdt(() -> {
                    window.finishStartupNavigation();
                    return null;
                });
                await(() -> ((ModNavigationPane) named(window, "mods.navigation")).available());
                assertActualGraph(runtime, window, manifests);
                assertTrue(onEdt(() -> descendants(window).noneMatch(component -> "deployment.workspace".equals(component.getName())
                    || "files.workspace".equals(component.getName()))));
                var robot = new Robot();
                robot.setAutoDelay(35);
                retainedDrafts(robot, runtime, window, directory, original);
                closeAndReopen(robot, runtime, window, original);
                awaitIdle(runtime);
                assertResourcesReleased(runtime);
                onEdt(() -> {
                    capture(window, "class-workspaces-fresh.png");
                    return null;
                });
                click(robot, (JComponent) named(window, "files.left.path"));
                robot.keyPress(KeyEvent.VK_ALT);
                robot.keyPress(KeyEvent.VK_F4);
                robot.keyRelease(KeyEvent.VK_F4);
                robot.keyRelease(KeyEvent.VK_ALT);
                robot.waitForIdle();
                await(() -> !window.isDisplayable());
                assertTrue(runtime.modContexts().isEmpty());
                assertTrue(runtime.modSessions().isEmpty());
                assertTrue(window.pages().contributions().isEmpty());
                assertEquals(0, runtime.persistence().database().openConnections());
                assertFalse(onEdt(() -> ownedWindows(window).stream().anyMatch(Window::isDisplayable)));
            } catch (Exception | Error problem) {
                failure = problem;
                throw problem;
            } finally {
                if (frame != null) try {
                    releaseWindow(frame, runtime);
                } catch (Exception | Error cleanup) {
                    if (failure == null) throw cleanup;
                    failure.addSuppressed(cleanup);
                }
            }
        } finally {
            com.formdev.flatlaf.util.UIScale.setZoomFactor(originalScale);
        }
        assertTrue(runtime.modContexts().isEmpty());
        assertTrue(runtime.modSessions().isEmpty());
        await(() -> Thread.getAllStackTraces().keySet().stream().noneMatch(thread -> thread.isAlive()
            && Set.of("files-view-observation", "deployment-page-view", "deployment-workspace-preferences").contains(thread.getName())));
        assertEquals("unchanged actual local file", Files.readString(original));
    }

    private static Path workspace() throws Exception {
        Path path = Path.of("").toRealPath();
        while (path != null && !Files.isRegularFile(path.resolve("docs/reference/File.md"))) path = path.getParent();
        assertNotNull(path, "Actual reactor workspace is required");
        assertFalse(Files.exists(path.resolve("src/moddev/files")));
        return path;
    }

    private static Map<String, ModManifest> manifests(Path workspace) throws Exception {
        var result = new TreeMap<String, ModManifest>();
        for (String name : List.of("git", "files", "deployment")) {
            Path module = workspace.resolve("src/mod").resolve(name);
            assertTrue(Files.isRegularFile(module.resolve("target/wtlmod-development.json")), module.toString());
            var manifest = ManifestReader.read(Files.readAllBytes(module.resolve("mod.json")));
            assertEquals(PREFIX + name, manifest.id());
            result.put(manifest.id(), manifest);
        }
        assertEquals(Optional.of("1"), result.get(DEPLOYMENT).schemaVersion());
        assertTrue(result.get(FILES).schemaVersion().isEmpty());
        assertTrue(result.get(GIT).schemaVersion().isEmpty());
        return Map.copyOf(result);
    }

    private static UUID enableOnFirstBoot(RuntimeLayout layout, Map<String, ModManifest> manifests) throws Exception {
        try (var runtime = PlatformRuntime.open(layout, FilePlatformFixture.PROTECTION, AppConfig.defaultsConfig())) {
            UUID boot = runtime.persistence().database().bootId();
            runtime.security().setupAndUnlock(PASSWORD.toCharArray(), PASSWORD.toCharArray());
            var refreshed = new PlatformModCatalog(runtime.persistence().database()).refresh(layout.applicationHome(), false);
            assertTrue(refreshed.rejections().isEmpty(), refreshed.rejections().toString());
            assertEquals(manifests.keySet(), refreshed.builds().stream().map(build -> build.candidate().manifest().id())
                .collect(java.util.stream.Collectors.toSet()));
            runtime.started();
            var intents = runtime.modIntents();
            for (String owner : List.of(GIT, FILES, DEPLOYMENT)) {
                var choice = new PlatformModIntents.Choice(true, Optional.of(manifests.get(owner).version().toString()));
                intents.apply(intents.review(owner, choice, Optional.empty()), false);
                assertEquals(choice, intents.list().stream().filter(view -> owner.equals(view.owner())).findFirst().orElseThrow().choice());
            }
            assertTrue(runtime.modContexts().isEmpty());
            assertTrue(runtime.modSessions().isEmpty());
            assertEquals(0, FilePlatformFixture.count(runtime.persistence().database(), "SELECT COUNT(*) FROM mod_schema"));
            assertEquals(1, FilePlatformFixture.count(runtime.persistence().database(), "SELECT COUNT(*) FROM mod_operation WHERE mod_id='"
                + DEPLOYMENT + "' AND target_schema_version='1' AND phase='WAIT_RESTART'"));
            return boot;
        }
    }

    private static PlatformFrame createWindow(PlatformRuntime runtime,
                                              gold.debug.windowstolinux.app.service.ai.PlatformModelService models) throws Exception {
        var previous = Set.copyOf(Arrays.asList(Window.getWindows()));
        try {
            var frame = ApplicationLauncher.createWindow(runtime, MessageCatalog.forLanguageTag("en"),
                new DesktopDisplayConfig("en", ThemeMode.LIGHT, 100, false), ThemeMode.LIGHT, FailureReportStore.disabled(), models);
            frame.setSize(1120, 820);
            frame.setLocation(20, 20);
            return frame;
        } catch (Exception | Error failure) {
            for (var window : Window.getWindows())
                if (!previous.contains(window) && window instanceof PlatformFrame)
                    try {
                        dispose(window);
                    } catch (Exception | Error cleanup) {
                        failure.addSuppressed(cleanup);
                    }
            throw failure;
        }
    }

    private static void assertActualGraph(PlatformRuntime runtime, PlatformFrame frame, Map<String, ModManifest> manifests) throws Exception {
        assertEquals(manifests.keySet(), runtime.modContexts().keySet());
        for (var entry : runtime.modContexts().entrySet()) {
            assertEquals(ModLaunchMode.NORMAL, entry.getValue().context().launchMode());
            assertEquals(manifests.get(entry.getKey()).version().toString(), entry.getValue().context().identity().version());
        }
        var deployment = runtime.modContexts().get(DEPLOYMENT).context();
        assertEquals("1", deployment.db().identity().schemaVersion());
        assertTrue(runtime.modContexts().get(FILES).context().identity().schemaVersion().isEmpty());
        assertTrue(runtime.modContexts().get(GIT).context().identity().schemaVersion().isEmpty());
        assertEquals(1, FilePlatformFixture.count(runtime.persistence().database(), "SELECT COUNT(*) FROM mod_schema"));
        assertEquals(1, FilePlatformFixture.count(runtime.persistence().database(), "SELECT COUNT(*) FROM mod_schema WHERE mod_id='"
            + DEPLOYMENT + "' AND schema_version='1' AND state='READY'"));
        var optional = deployment.services().availability(GIT);
        assertEquals(AvailabilityState.AVAILABLE, optional.state());
        assertEquals(Optional.of(manifests.get(GIT).version().toString()), optional.selectedVersion());
        var roots = frame.pages().contributions().stream().filter(page -> !page.spec().settingsPage()).toList();
        assertEquals(Set.of(new PageKey(DEPLOYMENT, "workspace"), new PageKey(FILES, "workspace")),
            roots.stream().map(page -> page.key()).collect(java.util.stream.Collectors.toSet()));
        assertEquals(2, roots.size());
        assertTrue(frame.pages().contributions().stream().noneMatch(page -> page.key().ownerId().equals(GIT)));
        assertEquals(2, onEdt(() -> descendants(frame).filter(JButton.class::isInstance).map(JButton.class::cast)
            .filter(button -> button.getClientProperty("mod.pageKey") instanceof PageKey).count()));
    }

    private static void retainedDrafts(Robot robot, PlatformRuntime runtime, PlatformFrame frame, Path directory, Path original) throws Exception {
        open(robot, frame, DEPLOYMENT);
        await(() -> descendants(frame).anyMatch(component -> "deployment.sourceName".equals(component.getName()) && component.isEnabled()));
        var deployment = onEdt(() -> named(frame, "deployment.workspace"));
        type(robot, frame, (JTextField) named(frame, "deployment.sourceName"), DEPLOYMENT_DRAFT);
        open(robot, frame, FILES);
        var files = onEdt(() -> named(frame, "files.workspace"));
        assertEquals(0, onEdt(() -> ((JTable) named(frame, "files.left.table")).getRowCount()));
        chooseLocal(robot, frame, directory);
        type(robot, frame, (JTextField) named(frame, "files.left.path"), FILES_DRAFT);
        open(robot, frame, DEPLOYMENT);
        assertSame(deployment, onEdt(() -> named(frame, "deployment.workspace")));
        assertEquals(DEPLOYMENT_DRAFT, onEdt(() -> ((JTextField) named(frame, "deployment.sourceName")).getText()));
        open(robot, frame, FILES);
        assertSame(files, onEdt(() -> named(frame, "files.workspace")));
        assertEquals(FILES_DRAFT, onEdt(() -> ((JTextField) named(frame, "files.left.path")).getText()));
        awaitIdle(runtime);
        onEdt(() -> {
            capture(frame, "class-workspaces-original.png");
            return null;
        });
        lockAndUnlock(robot, runtime, frame, original);
        appearance(robot, frame);
        combo(robot, (JComboBox<?>) named(frame, "appearance.language"), "zh-CN");
        combo(robot, (JComboBox<?>) named(frame, "appearance.theme"), ThemeMode.DARK);
        click(robot, (JComponent) named(frame, "appearance.save"));
        awaitIdle(runtime);
        assertEquals("zh-CN", runtime.persistence().preferences().find("ui_locale").orElseThrow());
        assertEquals("DARK", runtime.persistence().preferences().find("ui_theme").orElseThrow());
        open(robot, frame, DEPLOYMENT);
        assertSame(deployment, onEdt(() -> named(frame, "deployment.workspace")));
        assertEquals(DEPLOYMENT_DRAFT, onEdt(() -> ((JTextField) named(frame, "deployment.sourceName")).getText()));
        assertEquals("标准 / 辅助部署", onEdt(() -> ((JTabbedPane) named(frame, "deployment.workspace.tabs")).getTitleAt(0)));
        open(robot, frame, FILES);
        assertSame(files, onEdt(() -> named(frame, "files.workspace")));
        assertEquals(FILES_DRAFT, onEdt(() -> ((JTextField) named(frame, "files.left.path")).getText()));
        onEdt(() -> {
            capture(frame, "class-workspaces-appearance.png");
            return null;
        });
    }

    private static void open(Robot robot, PlatformFrame frame, String owner) throws Exception {
        var key = new PageKey(owner, "workspace");
        var button = onEdt(() -> descendants(frame).filter(JButton.class::isInstance).map(JButton.class::cast)
            .filter(value -> key.equals(value.getClientProperty("mod.pageKey"))).findFirst().orElseThrow());
        click(robot, button);
        await(() -> owner.equals(frame.currentPage().ownerId()));
    }

    private static void chooseLocal(Robot robot, PlatformFrame frame, Path directory) throws Exception {
        click(robot, (JComponent) named(frame, "files.left.local"));
        await(() -> directoryDialog(frame) != null);
        var dialog = onEdt(() -> directoryDialog(frame));
        onEdt(() -> {
            assertSame(frame, dialog.getOwner());
            var chooser = (JFileChooser) named(dialog, "source.directory");
            assertEquals(JFileChooser.DIRECTORIES_ONLY, chooser.getFileSelectionMode());
            chooser.setCurrentDirectory(directory.getParent().toFile());
            chooser.setSelectedFile(directory.toFile());
            return null;
        });
        await(() -> directory.toFile().equals(((JFileChooser) named(dialog, "source.directory")).getSelectedFile()));
        var confirm = onEdt(() -> descendants(dialog).filter(JButton.class::isInstance).map(JButton.class::cast)
            .filter(button -> UIManager.getString("OptionPane.okButtonText").equals(button.getText())).findFirst().orElseThrow());
        click(robot, confirm);
        await(() -> directoryDialog(frame) == null && ((JTable) named(frame, "files.left.table")).getRowCount() == 1);
        assertEquals(directory.toString(), onEdt(() -> ((JTextField) named(frame, "files.left.path")).getText()));
    }

    private static JDialog directoryDialog(PlatformFrame frame) {
        return Arrays.stream(frame.getOwnedWindows()).filter(JDialog.class::isInstance).map(JDialog.class::cast)
            .filter(window -> window.isShowing() && "task.interaction.dialog".equals(window.getName())
                && descendants(window).anyMatch(component -> "source.directory".equals(component.getName()))).findFirst().orElse(null);
    }

    private static void lockAndUnlock(Robot robot, PlatformRuntime runtime, PlatformFrame frame, Path original) throws Exception {
        click(robot, (JComponent) named(frame, "files.left.path"));
        click(robot, (JComponent) named(frame, "security.lock"));
        await(() -> !runtime.security().securityState().unlocked() && frame.getGlassPane().isVisible());
        for (int key : new int[]{KeyEvent.VK_Q, KeyEvent.VK_F2, KeyEvent.VK_DELETE}) {
            robot.keyPress(key);
            robot.keyRelease(key);
        }
        robot.waitForIdle();
        assertFalse(onEdt(() -> named(frame, "files.left.path").isEnabled()));
        var lockedRoot = onEdt(() -> named(frame, "files.workspace"));
        click(robot, (JComponent) named(frame, "mods.workspace.close"));
        assertNull(onEdt(() -> closeDialog(frame)));
        assertEquals(FILES, onEdt(() -> frame.currentPage().ownerId()));
        assertSame(lockedRoot, onEdt(() -> named(frame, "files.workspace")));
        assertEquals(FILES_DRAFT, onEdt(() -> ((JTextField) named(frame, "files.left.path")).getText()));
        open(robot, frame, DEPLOYMENT);
        assertFalse(onEdt(() -> named(frame, "deployment.sourceName").isEnabled()));
        assertEquals(DEPLOYMENT_DRAFT, onEdt(() -> ((JTextField) named(frame, "deployment.sourceName")).getText()));
        onEdt(() -> {
            capture(frame, "class-workspaces-locked.png");
            return null;
        });
        open(robot, frame, FILES);
        click(robot, (JComponent) named(frame, "security.lock"));
        await(() -> Arrays.stream(frame.getOwnedWindows()).anyMatch(window -> window.isShowing() && "security.unlockDialog".equals(window.getName())));
        var prompt = onEdt(() -> Arrays.stream(frame.getOwnedWindows()).filter(window -> window.isShowing()
            && "security.unlockDialog".equals(window.getName())).findFirst().orElseThrow());
        type(robot, prompt, (JTextField) named(prompt, "security.password"), PASSWORD);
        click(robot, (JComponent) named(prompt, "security.submit"));
        await(() -> runtime.security().securityState().unlocked() && !prompt.isDisplayable() && named(frame, "files.left.path").isEnabled());
        assertEquals(FILES_DRAFT, onEdt(() -> ((JTextField) named(frame, "files.left.path")).getText()));
        assertEquals("unchanged actual local file", Files.readString(original));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void appearance(Robot robot, PlatformFrame frame) throws Exception {
        click(robot, (JComponent) named(frame, "navigation.settings"));
        var button = (JButton) named(frame, "navigation.settings.appearance.title");
        var picker = (JComboBox<?>) named(frame, "settings.category");
        await(() -> button.isShowing() || picker.isShowing());
        if (onEdt(button::isShowing)) click(robot, button);
        else {
            var target = onEdt(() -> {
                for (int index = 0; index < picker.getItemCount(); index++) {
                    var value = picker.getItemAt(index);
                    var label = (JLabel) ((ListCellRenderer) picker.getRenderer()).getListCellRendererComponent(new JList<>(), value, index, false, false);
                    if (button.getText().equals(label.getText())) return value;
                }
                throw new AssertionError("Actual compact appearance category not displayed");
            });
            combo(robot, picker, target);
        }
        await(() -> named(frame, "appearance.language").isShowing());
    }

    private static void closeAndReopen(Robot robot, PlatformRuntime runtime, PlatformFrame frame, Path original) throws Exception {
        var files = onEdt(() -> named(frame, "files.workspace"));
        var generation = runtime.modContexts().get(DEPLOYMENT).context().db().identity();
        open(robot, frame, DEPLOYMENT);
        var deployment = onEdt(() -> named(frame, "deployment.workspace"));
        closeWorkspace(robot, frame, false);
        assertSame(deployment, onEdt(() -> named(frame, "deployment.workspace")));
        assertEquals(DEPLOYMENT_DRAFT, onEdt(() -> ((JTextField) named(frame, "deployment.sourceName")).getText()));
        closeWorkspace(robot, frame, true);
        assertTrue(onEdt(() -> descendants(frame).noneMatch(component -> component == deployment)));
        open(robot, frame, FILES);
        assertSame(files, onEdt(() -> named(frame, "files.workspace")));
        assertEquals(FILES_DRAFT, onEdt(() -> ((JTextField) named(frame, "files.left.path")).getText()));
        open(robot, frame, DEPLOYMENT);
        await(() -> descendants(frame).anyMatch(component -> "deployment.sourceName".equals(component.getName()) && component.isEnabled()));
        assertNotSame(deployment, onEdt(() -> named(frame, "deployment.workspace")));
        assertEquals("", onEdt(() -> ((JTextField) named(frame, "deployment.sourceName")).getText()));
        assertEquals(generation, runtime.modContexts().get(DEPLOYMENT).context().db().identity());
        open(robot, frame, FILES);
        closeWorkspace(robot, frame, true);
        assertTrue(onEdt(() -> descendants(frame).noneMatch(component -> component == files)));
        open(robot, frame, FILES);
        assertNotSame(files, onEdt(() -> named(frame, "files.workspace")));
        assertEquals("", onEdt(() -> ((JTextField) named(frame, "files.left.path")).getText()));
        assertEquals(0, onEdt(() -> ((JTable) named(frame, "files.left.table")).getRowCount()));
        assertTrue(onEdt(() -> descendants(frame).noneMatch(component -> "files.right.table".equals(component.getName()))));
        assertEquals(2, frame.pages().contributions().stream().filter(page -> !page.spec().settingsPage()).count());
        assertEquals("unchanged actual local file", Files.readString(original));
    }

    private static void closeWorkspace(Robot robot, PlatformFrame frame, boolean confirm) throws Exception {
        click(robot, (JComponent) named(frame, "mods.workspace.close"));
        await(() -> closeDialog(frame) != null);
        var dialog = onEdt(() -> closeDialog(frame));
        var button = onEdt(() -> {
            var pane = (JOptionPane) descendants(dialog).filter(JOptionPane.class::isInstance).findFirst().orElseThrow();
            assertEquals(2, pane.getOptions().length);
            String option = pane.getOptions()[confirm ? 0 : 1].toString();
            return descendants(dialog).filter(JButton.class::isInstance).map(JButton.class::cast)
                .filter(value -> option.equals(value.getText())).findFirst().orElseThrow();
        });
        click(robot, button);
        await(() -> !dialog.isShowing());
    }

    private static JDialog closeDialog(PlatformFrame frame) {
        return Arrays.stream(frame.getOwnedWindows()).filter(JDialog.class::isInstance).map(JDialog.class::cast)
            .filter(window -> window.isShowing() && descendants(window).anyMatch(JOptionPane.class::isInstance)).findFirst().orElse(null);
    }

    private static void awaitIdle(PlatformRuntime runtime) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            if (!DesktopTaskRunner.hasActiveTasks() && runtime.security().securityState().activeTasks() == 0
                && runtime.tasks().snapshots().stream().allMatch(task -> task.state() == TaskState.ENDED)) return;
            Thread.sleep(10);
        }
        assertFalse(DesktopTaskRunner.hasActiveTasks(), "Original desktop workers must return");
        assertEquals(0, runtime.security().securityState().activeTasks(), "Actual accepted operations must release");
        assertTrue(runtime.tasks().snapshots().stream().allMatch(task -> task.state() == TaskState.ENDED), runtime.tasks().snapshots().toString());
    }

    private static void assertResourcesReleased(PlatformRuntime runtime) throws Exception {
        assertEquals(0, FilePlatformFixture.count(runtime.persistence().database(), "SELECT COUNT(*) FROM platform_task WHERE state<>'ENDED'"));
        assertEquals(0, FilePlatformFixture.count(runtime.persistence().database(), "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL"));
        assertEquals(0, runtime.persistence().database().openConnections());
    }

    private static List<Window> ownedWindows(Window owner) {
        var windows = new ArrayList<Window>();
        windows.add(owner);
        for (var child : owner.getOwnedWindows()) windows.addAll(ownedWindows(child));
        return List.copyOf(windows);
    }

    private static void releaseWindow(PlatformFrame frame, PlatformRuntime runtime) throws Exception {
        Throwable failure = null;
        boolean stillOpen = onEdt(frame::isDisplayable);
        try {
            for (var contribution : frame.pages().contributions())
                if (!contribution.spec().settingsPage())
                    try {
                        frame.pages().closeView(contribution.key());
                    } catch (Exception | Error cleanup) {
                        if (failure == null) failure = cleanup;
                        else failure.addSuppressed(cleanup);
                    }
        } catch (Exception | Error cleanup) {
            if (failure == null) failure = cleanup;
            else failure.addSuppressed(cleanup);
        }
        if (stillOpen) try {
            onEdt(() -> {
                dispose(frame);
                return null;
            });
        } catch (Exception | Error cleanup) {
            if (failure == null) failure = cleanup;
            else failure.addSuppressed(cleanup);
        }
        try {
            awaitIdle(runtime);
            if (stillOpen) assertResourcesReleased(runtime);
        } catch (Exception | Error cleanup) {
            if (failure == null) failure = cleanup;
            else failure.addSuppressed(cleanup);
        }
        if (failure instanceof Error error) throw error;
        if (failure instanceof Exception exception) throw exception;
    }
}
