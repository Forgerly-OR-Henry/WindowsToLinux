package gold.debug.windowstolinux.mod.files.ui;

import static gold.debug.windowstolinux.mod.files.ui.FilesInteractionTest.*;
import static org.junit.jupiter.api.Assertions.*;

import java.awt.GraphicsEnvironment;
import java.awt.Robot;
import java.awt.event.KeyEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.swing.*;

import gold.debug.windowstolinux.mod.files.service.FilesRuntimeFixture;
import gold.debug.windowstolinux.appapi.server.model.ServerInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Robot selection and file management against actual controlled SSH/SFTP. / 面向实际受控 SSH/SFTP 的 Robot 选择及文件管理。 */
class FilesRemoteInteractionTest {
    private static gold.debug.windowstolinux.appapi.ui.page.UiNavigationRequest<gold.debug.windowstolinux.appapi.server.model.ServerId> rootRequest(
        gold.debug.windowstolinux.appapi.server.model.ServerId server) {
        return new gold.debug.windowstolinux.appapi.ui.page.UiNavigationRequest<>("remote-root",
            gold.debug.windowstolinux.appapi.server.model.ServerId.class, java.util.Optional.of(server));
    }

    @Test
    void serverRootNavigationReusesActivePanePreservesOtherPaneClipboardAndCancelledDraft() throws Exception {
        try (var fixture = new FilesRuntimeFixture(temporary, true)) {
            Files.writeString(fixture.remoteFiles().resolve("root-item.txt"), "controlled remote data");
            var workspace = new FilesWorkspace(fixture.files());
            var frame = frame(workspace);
            var robot = new Robot();
            robot.setAutoDelay(60);
            try {
                edt(() -> {
                    workspace.navigate(rootRequest(fixture.server()));
                    return null;
                });
                await(() -> table(frame, "left").getRowCount() == 1);
                assertEquals("/", edt(() -> ((JTextField) named(frame, "files.left.path")).getText()));
                cell(robot, table(frame, "left"), 0);
                edt(() -> {
                    workspace.commands().clip(false);
                    return null;
                });
                assertTrue(edt(workspace::hasClipboard));
                click(robot, named(frame, "files.second"));
                var right = edt(() -> workspace.panes().get(1));
                edt(() -> {
                    workspace.commands().chooseLocal(right);
                    return null;
                });
                await(() -> ((JTextField) named(frame, "files.right.path")).getText().equals(fixture.local().toString()));
                var left = edt(() -> workspace.panes().getFirst());
                edt(() -> {
                    workspace.activate(left);
                    ((JTextField) named(frame, "files.left.path")).setText("/unsubmitted-draft");
                    return null;
                });
                SwingUtilities.invokeLater(() -> {
                    try {
                        workspace.navigate(rootRequest(fixture.server()));
                    } catch (Exception failure) {
                        throw new AssertionError(failure);
                    }
                });
                await(() -> findDialog("files.dialog.cancel") != null);
                click(robot, findDialog("files.dialog.cancel"));
                await(() -> findDialog("files.dialog.cancel") == null);
                assertEquals("/unsubmitted-draft", edt(() -> ((JTextField) named(frame, "files.left.path")).getText()));
                assertTrue(edt(workspace::hasClipboard));
                edt(() -> {
                    ((JTextField) named(frame, "files.left.path")).setText("/");
                    return null;
                });
                var originalTable = table(frame, "left");
                edt(() -> {
                    workspace.environmentChanged(new gold.debug.windowstolinux.appapi.ui.page.UiEnvironment(java.util.Locale.ENGLISH, "dark", false));
                    workspace.activate(right);
                    workspace.navigate(rootRequest(fixture.server()));
                    return null;
                });
                await(() -> table(frame, "right").getRowCount() == 1);
                assertEquals("/", edt(() -> ((JTextField) named(frame, "files.right.path")).getText()));
                assertSame(originalTable, table(frame, "left"));
                assertTrue(edt(workspace::hasClipboard));
                var beforeFailure = edt(() -> ((JTextArea) named(frame, "files.feedback")).getText());
                edt(() -> {
                    workspace.navigate(rootRequest(new gold.debug.windowstolinux.appapi.server.model.ServerId("missing")));
                    return null;
                });
                await(() -> !((JTextArea) named(frame, "files.feedback")).getText().equals(beforeFailure));
                assertEquals(fixture.server(), edt(() -> workspace.active().directory().server().orElseThrow()));
                assertEquals(1, edt(() -> table(frame, "right").getRowCount()));
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
    void oldLocationReadCannotReplaceASelectedServerRoot() throws Exception {
        try (var fixture = new FilesRuntimeFixture(temporary, true)) {
            Files.writeString(fixture.remoteFiles().resolve("latest-root.txt"), "actual root");
            var entered = new java.util.concurrent.CountDownLatch(1);
            var release = new java.util.concurrent.CountDownLatch(1);
            fixture.chooser((task, cancel) -> {
                entered.countDown();
                try {
                    release.await(15, java.util.concurrent.TimeUnit.SECONDS);
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                }
                return fixture.local();
            });
            var workspace = new FilesWorkspace(fixture.files());
            var frame = frame(workspace);
            try {
                edt(() -> {
                    workspace.commands().chooseLocal(workspace.active());
                    return null;
                });
                assertTrue(entered.await(10, java.util.concurrent.TimeUnit.SECONDS));
                edt(() -> {
                    workspace.navigate(rootRequest(fixture.server()));
                    return null;
                });
                await(() -> table(frame, "left").getRowCount() == 1);
                release.countDown();
                fixture.awaitIdle();
                assertEquals(fixture.server(), edt(() -> workspace.active().directory().server().orElseThrow()));
                assertEquals("latest-root.txt", edt(() -> table(frame, "left").getValueAt(0, 0)));
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
    void busyWorkspaceRejectsNavigationWithoutQueuingAndCanNavigateAfterCompletion() throws Exception {
        try (var fixture = new FilesRuntimeFixture(temporary, true)) {
            var workspace = new FilesWorkspace(fixture.files());
            var frame = frame(workspace);
            var robot = new Robot();
            var entered = new java.util.concurrent.CountDownLatch(1);
            var release = new java.util.concurrent.CountDownLatch(1);
            try {
                edt(() -> {
                    workspace.navigate(rootRequest(fixture.server()));
                    return null;
                });
                await(() -> workspace.active().directory() != null);
                fixture.approval((intent, task) -> {
                    entered.countDown();
                    try {
                        return release.await(15, java.util.concurrent.TimeUnit.SECONDS);
                    } catch (InterruptedException failure) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                });
                SwingUtilities.invokeLater(() -> workspace.commands().createItem(false));
                await(() -> findDialog("files.dialog.name") != null);
                edt(() -> {
                    ((JTextField) findDialog("files.dialog.name")).setText("new-item.txt");
                    return null;
                });
                click(robot, findDialog("files.dialog.ok"));
                assertTrue(entered.await(10, java.util.concurrent.TimeUnit.SECONDS));
                var failed = edt(() -> assertThrows(gold.debug.windowstolinux.appapi.task.result.ApiException.class,
                    () -> workspace.navigate(rootRequest(fixture.server()))));
                assertEquals("FILES_NAVIGATION_BUSY", failed.problem().eventCode());
                release.countDown();
                fixture.awaitIdle();
                await(workspace::available);
                edt(() -> {
                    workspace.navigate(rootRequest(fixture.server()));
                    return null;
                });
                await(() -> table(frame, "left").getRowCount() == 1);
                fixture.awaitIdle();
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

    @TempDir
    Path temporary;

    @Test
    void explicitServerAndPathAreRequiredBeforeRealRemoteBrowseCreateAndDelete() throws Exception {
        assertFalse(GraphicsEnvironment.isHeadless());
        try (var fixture = new FilesRuntimeFixture(temporary, true)) {
            Files.writeString(fixture.remoteFiles().resolve("existing.txt"), "actual wire fixture content");
            var workspace = new FilesWorkspace(fixture.files());
            var frame = frame(workspace);
            var robot = new Robot();
            robot.setAutoDelay(65);
            try {
                assertEquals("", edt(() -> ((JTextField) named(frame, "files.left.path")).getText()));
                assertEquals(0, edt(() -> table(frame, "left").getRowCount()));
                click(robot, named(frame, "files.left.remote"));
                await(() -> findDialog("files.dialog.server") != null);
                assertEquals(1, edt(() -> ((JComboBox<?>) findDialog("files.dialog.server")).getItemCount()));
                assertEquals(-1, edt(() -> ((JComboBox<?>) findDialog("files.dialog.server")).getSelectedIndex()));
                assertEquals("", edt(() -> ((JTextField) findDialog("files.dialog.remotePath")).getText()));
                selectServer(robot);
                assertEquals(fixture.server(), edt(() -> ((ServerInfo) ((JComboBox<?>) findDialog("files.dialog.server")).getSelectedItem()).ref()));
                click(robot, findDialog("files.dialog.ok"));
                await(() -> findDialog("files.dialog.server") == null);
                assertEquals("", edt(() -> ((JTextField) named(frame, "files.left.path")).getText()));
                assertEquals(0, edt(() -> table(frame, "left").getRowCount()));
                assertTrue(edt(() -> ((JTextArea) named(frame, "files.feedback")).getText()).contains("Invalid file path"));
                click(robot, named(frame, "files.left.remote"));
                await(() -> findDialog("files.dialog.server") != null);
                assertEquals(-1, edt(() -> ((JComboBox<?>) findDialog("files.dialog.server")).getSelectedIndex()));
                assertEquals("", edt(() -> ((JTextField) findDialog("files.dialog.remotePath")).getText()));
                selectServer(robot);
                click(robot, findDialog("files.dialog.remotePath"));
                type(robot, "/");
                click(robot, findDialog("files.dialog.ok"));
                await(() -> ((JTextField) named(frame, "files.left.path")).getText().equals("/"));
                await(() -> table(frame, "left").getRowCount() == 1);
                assertEquals("existing.txt", edt(() -> table(frame, "left").getValueAt(0, 0)));
                assertEquals(2, edt(() -> table(frame, "left").getColumnCount()));
                click(robot, named(frame, "files.action.file"));
                await(() -> findDialog("files.dialog.name") != null);
                assertEquals("", edt(() -> ((JTextField) findDialog("files.dialog.name")).getText()));
                type(robot, "empty.remote");
                click(robot, findDialog("files.dialog.ok"));
                await(() -> Files.exists(fixture.remoteFiles().resolve("empty.remote")));
                assertEquals(0, Files.size(fixture.remoteFiles().resolve("empty.remote")));
                assertFalse(Files.exists(fixture.local().resolve("empty.remote")));
                await(() -> table(frame, "left").getRowCount() == 2 && table(frame, "left").isEnabled());
                cell(robot, table(frame, "left"), row(table(frame, "left"), "empty.remote"));
                robot.keyPress(KeyEvent.VK_DELETE);
                robot.keyRelease(KeyEvent.VK_DELETE);
                await(() -> findDialog("files.dialog.ok") != null);
                click(robot, findDialog("files.dialog.ok"));
                await(() -> !Files.exists(fixture.remoteFiles().resolve("empty.remote")));
                await(() -> table(frame, "left").getRowCount() == 1);
                assertEquals("actual wire fixture content", Files.readString(fixture.remoteFiles().resolve("existing.txt")));
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

    private static void selectServer(Robot robot) throws Exception {
        click(robot, findDialog("files.dialog.server"));
        robot.keyPress(KeyEvent.VK_DOWN);
        robot.keyRelease(KeyEvent.VK_DOWN);
        robot.keyPress(KeyEvent.VK_ENTER);
        robot.keyRelease(KeyEvent.VK_ENTER);
        robot.waitForIdle();
    }
}
