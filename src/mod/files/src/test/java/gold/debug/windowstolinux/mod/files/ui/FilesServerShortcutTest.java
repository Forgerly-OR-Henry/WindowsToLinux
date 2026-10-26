package gold.debug.windowstolinux.mod.files.ui;

import static org.junit.jupiter.api.Assertions.*;
import static gold.debug.windowstolinux.mod.files.ui.FilesShellTest.*;

import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.event.KeyEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import javax.imageio.ImageIO;
import javax.swing.*;

import gold.debug.windowstolinux.app.db.server.StoredServerIdentity;
import gold.debug.windowstolinux.app.main.start.FilePlatformFixture;
import gold.debug.windowstolinux.app.main.start.PlatformRuntime;
import gold.debug.windowstolinux.app.mod.config.ManifestReader;
import gold.debug.windowstolinux.app.service.mod.PlatformModCatalog;
import gold.debug.windowstolinux.app.service.mod.PlatformModIntents;
import gold.debug.windowstolinux.app.service.server.ServerProfile;
import gold.debug.windowstolinux.app.ui.display.DesktopDisplayConfig;
import gold.debug.windowstolinux.app.ui.display.ThemeMode;
import gold.debug.windowstolinux.app.ui.error.FailureReportStore;
import gold.debug.windowstolinux.app.ui.language.MessageCatalog;
import gold.debug.windowstolinux.app.ui.mod.ModPageRegistry;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.digest.BuiltinDigests;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FilesServerShortcutTest {
    @TempDir
    Path root;

    @Test
    void actualModMenuNavigatesByKeyboardAndCapturesThemesScalesWithoutEagerWorkspaceCreation() throws Exception {
        Path workspace = Path.of("").toRealPath();
        while (workspace != null && !Files.isRegularFile(workspace.resolve("docs/reference/File.md")))
            workspace = workspace.getParent();
        assertNotNull(workspace);
        final Path actualWorkspace = workspace;
        var manifest = ManifestReader.read(Files.readAllBytes(workspace.resolve("src/mod/files/mod.json")));
        var robot = new Robot();
        robot.setAutoDelay(60);
        float originalScale = com.formdev.flatlaf.util.UIScale.getZoomFactor();
        try (var remote = new FilePlatformFixture.Remote(root.resolve("remote"))) {
            Files.writeString(remote.files.resolve("actual-root-file.txt"), "controlled fixture data");
            for (var theme : new ThemeMode[]{ThemeMode.LIGHT, ThemeMode.DARK}) {
                for (int scale : new int[]{100, 150}) {
                    String variant = theme + "-" + scale;
                    var db = root.resolve(variant);
                    try (var initial = PlatformRuntime.open(db, FilePlatformFixture.PROTECTION)) {
                        initial.security().setupAndUnlock("shortcut-password".toCharArray(), "shortcut-password".toCharArray());
                        var catalog = new PlatformModCatalog(initial.persistence().database()).refresh(actualWorkspace, false);
                        assertTrue(catalog.rejections().isEmpty(), catalog.rejections().toString());
                        initial.started();
                        initial.modIntents().apply(initial.modIntents().review(manifest.id(),
                            new PlatformModIntents.Choice(true, Optional.of("1.0.0")), Optional.empty()), false);
                    }
                    try (var runtime = PlatformRuntime.open(db, FilePlatformFixture.PROTECTION)) {
                        runtime.security().unlock("shortcut-password".toCharArray());
                        int port = remote.listener.server().getPort();
                        try (var operation = runtime.security().enterOperation(); var credentials = runtime.security().openCredentials()) {
                            credentials.save("controlled-credential", "fixture-secret".toCharArray());
                        }
                        runtime.persistence().servers().saveServerProfile(new ServerProfile("server", "127.0.0.1", port, "alice",
                            "controlled-credential", "本机验证服务器").stored());
                        var key = remote.listener.server().getKeyPairProvider().loadKeys(null).iterator().next().getPublic();
                        runtime.persistence().servers().saveServer(new StoredServerIdentity("server", "127.0.0.1", port,
                            KeyUtils.getFingerPrint(BuiltinDigests.sha256, key)));
                        var models = runtime.models();
                        var frame = onEdt(() -> FilePlatformFixture.window(runtime, MessageCatalog.forLanguageTag("zh-CN"),
                            new DesktopDisplayConfig("zh-CN", theme, scale, false), theme,
                            FailureReportStore.disabled(), models));
                        try {
                            runtime.started();
                            onEdt(() -> {
                                frame.setVisible(true);
                                frame.toFront();
                                return null;
                            });
                            var report = runtime.startDevelopmentMods(actualWorkspace, frame.pages());
                            assertTrue(report.problems().isEmpty(), report.problems().toString());
                            assertEquals(Set.of(manifest.id()), report.running());
                            onEdt(() -> {
                                showServers(frame);
                                return null;
                            });
                            await(() -> ((JTable) named(frame, "servers.table")).getRowCount() == 1
                                && named(frame, "servers.more").isEnabled());
                            assertTrue(descendants(frame).noneMatch(component -> "files.workspace".equals(component.getName())));
                            var serverPane = (JComponent) named(frame, "platform.servers");
                            var more = (JButton) named(frame, "servers.more");
                            click(robot, more);
                            var popup = onEdt(() -> findMenu(frame));
                            assertNotNull(popup);
                            await(() -> popup.isVisible());
                            assertEquals(3, popup.getComponentCount());
                            assertEquals("文件管理", ((JMenuItem) popup.getComponent(0)).getText());
                            assertTrue(popup.getComponent(1) instanceof JPopupMenu.Separator);
                            assertTrue(((JMenuItem) popup.getComponent(0)).isEnabled());
                            screenshot(robot, serverPane, "servers-more-" + variant);
                            robot.keyPress(KeyEvent.VK_DOWN);
                            robot.keyRelease(KeyEvent.VK_DOWN);
                            robot.keyPress(KeyEvent.VK_ENTER);
                            robot.keyRelease(KeyEvent.VK_ENTER);
                            await(() -> descendants(frame).anyMatch(component -> "files.workspace".equals(component.getName())));
                            await(() -> ((JTable) named(frame, "files.left.table")).getRowCount() == 1);
                            assertEquals("/", onEdt(() -> ((JTextField) named(frame, "files.left.path")).getText()));
                            assertEquals("actual-root-file.txt", onEdt(() -> ((JTable) named(frame, "files.left.table")).getValueAt(0, 0)));
                            var originalRoot = onEdt(() -> named(frame, "files.workspace"));
                            screenshot(robot, (JComponent) originalRoot, "files-root-" + variant);
                            onEdt(() -> {
                                showServers(frame);
                                return null;
                            });
                            click(robot, more);
                            click(robot, (JComponent) popup.getComponent(0));
                            await(() -> descendants(frame).anyMatch(component -> "files.workspace".equals(component.getName())
                                && component.isShowing()));
                            assertSame(originalRoot, onEdt(() -> named(frame, "files.workspace")));
                            if (theme == ThemeMode.LIGHT && scale == 100) {
                                var originalItem = popup.getComponent(0);
                                for (var next : new ThemeMode[]{ThemeMode.DARK, ThemeMode.LIGHT, ThemeMode.DARK, ThemeMode.LIGHT}) {
                                    onEdt(() -> {
                                        gold.debug.windowstolinux.app.ui.display.DesktopThemeService.apply(next, next == ThemeMode.DARK ? 150 : 100);
                                        ((gold.debug.windowstolinux.app.ui.server.PlatformServersPane) serverPane).localize();
                                        frame.pages().environment(new gold.debug.windowstolinux.appapi.ui.page.UiEnvironment(
                                            java.util.Locale.SIMPLIFIED_CHINESE, next.name(), false));
                                        showServers(frame);
                                        return null;
                                    });
                                    click(robot, more);
                                    assertSame(originalItem, onEdt(() -> popup.getComponent(0)));
                                    assertEquals(UIManager.getColor("PopupMenu.background"), onEdt(popup::getBackground));
                                    assertEquals(1, ((JMenuItem) originalItem).getActionListeners().length);
                                    assertSame(originalRoot, onEdt(() -> named(frame, "files.workspace")));
                                    onEdt(() -> {
                                        popup.setVisible(false);
                                        return null;
                                    });
                                }
                            }
                            onEdt(() -> {
                                showServers(frame);
                                ((JTable) named(frame, "servers.table")).clearSelection();
                                return null;
                            });
                            assertFalse(onEdt(() -> more.isEnabled()));
                            assertFalse(onEdt(() -> popup.getComponent(0).isEnabled()));
                            screenshot(robot, serverPane, "servers-no-selection-" + variant);
                            assertFalse(runtime.tasks().snapshots().stream().anyMatch(task -> task.ownerId().equals("platform")
                                && task.type().contains("check")));
                        } finally {
                            releaseFrame(frame, runtime);
                        }
                    }
                }
            }
        } finally {
            com.formdev.flatlaf.util.UIScale.setZoomFactor(originalScale);
        }
    }

    private static void showServers(gold.debug.windowstolinux.app.ui.window.PlatformFrame frame) {
        ((JButton) named(frame, "navigation.platform.servers")).doClick(0);
    }

    private static JPopupMenu findMenu(java.awt.Component root) {
        return Arrays.stream(javax.swing.MenuSelectionManager.defaultManager().getSelectedPath())
            .filter(JPopupMenu.class::isInstance).map(JPopupMenu.class::cast).findFirst().orElse(null);
    }

    private static void screenshot(Robot robot, JComponent target, String name) throws Exception {
        var bounds = onEdt(() -> new Rectangle(target.getLocationOnScreen(), target.getSize()));
        var image = robot.createScreenCapture(bounds);
        var directory = Path.of(System.getProperty("ui.extensions.capture", "target/ui-extensions-preview"));
        Files.createDirectories(directory);
        assertTrue(ImageIO.write(image, "png", directory.resolve(name + ".png").toFile()));
    }
}
