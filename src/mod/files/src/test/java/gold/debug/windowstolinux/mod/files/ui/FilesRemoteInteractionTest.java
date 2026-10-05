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
