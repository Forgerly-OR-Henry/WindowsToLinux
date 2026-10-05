package gold.debug.windowstolinux.mod.files.ui;

import static gold.debug.windowstolinux.mod.files.ui.FilesInteractionTest.*;
import static org.junit.jupiter.api.Assertions.*;

import java.awt.*;
import java.awt.event.KeyEvent;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.*;

import gold.debug.windowstolinux.mod.files.service.FilesRuntimeFixture;
import gold.debug.windowstolinux.app.windows.file.WindowsLinks;
import gold.debug.windowstolinux.appapi.task.run.TaskHandle;
import gold.debug.windowstolinux.mod.files.file.model.FilesOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Actual junction and original partial-result behavior through Robot-owned UI input. / 通过 Robot 所属界面输入验证实际联接点及原部分结果行为。 */
class FilesLinksInteractionTest {
    @TempDir
    Path temporary;

    @Test
    void selectedLinkCopyMoveRenameDeleteOperateOnlyTheLinkItself() throws Exception {
        assertFalse(GraphicsEnvironment.isHeadless());
        try (var fixture = new FilesRuntimeFixture(temporary, false)) {
            var original = Files.createDirectory(fixture.local().resolve("original"));
            Files.writeString(original.resolve("keep.txt"), "actual original");
            var source = Files.createDirectory(fixture.local().resolve("source"));
            var destination = Files.createDirectory(fixture.local().resolve("destination"));
            WindowsLinks.create(source.resolve("link"), original);
            var workspace = new FilesWorkspace(fixture.files());
            var frame = frame(workspace);
            var robot = new Robot();
            robot.setAutoDelay(65);
            try {
                click(robot, named(frame, "files.second"));
                choose(robot, fixture, frame, "left", source);
                choose(robot, fixture, frame, "right", destination);
                await(() -> table(frame, "left").getRowCount() == 1);
                assertEquals("Link", edt(() -> table(frame, "left").getValueAt(0, 1)));
                cell(robot, table(frame, "left"), 0);
                keys(robot, KeyEvent.VK_CONTROL, KeyEvent.VK_C);
                click(robot, table(frame, "right"));
                keys(robot, KeyEvent.VK_CONTROL, KeyEvent.VK_V);
                await(() -> Files.exists(destination.resolve("link"), LinkOption.NOFOLLOW_LINKS));
                assertTrue(WindowsLinks.target(destination.resolve("link")).isPresent());
                assertEquals("actual original", Files.readString(destination.resolve("link/keep.txt")));
                await(() -> table(frame, "right").getRowCount() == 1 && table(frame, "right").isEnabled());
                cell(robot, table(frame, "right"), 0);
                robot.keyPress(KeyEvent.VK_F2);
                robot.keyRelease(KeyEvent.VK_F2);
                await(() -> findDialog("files.dialog.name") != null);
                type(robot, "copied");
                click(robot, findDialog("files.dialog.ok"));
                await(() -> Files.exists(destination.resolve("copied"), LinkOption.NOFOLLOW_LINKS));
                await(() -> table(frame, "left").isEnabled());
                cell(robot, table(frame, "left"), 0);
                keys(robot, KeyEvent.VK_CONTROL, KeyEvent.VK_X);
                assertTrue(WindowsLinks.target(source.resolve("link")).isPresent());
                click(robot, table(frame, "right"));
                keys(robot, KeyEvent.VK_CONTROL, KeyEvent.VK_V);
                await(() -> !Files.exists(source.resolve("link"), LinkOption.NOFOLLOW_LINKS) && Files.exists(destination.resolve("link"), LinkOption.NOFOLLOW_LINKS));
                await(() -> table(frame, "right").getRowCount() == 2 && table(frame, "right").isEnabled());
                cell(robot, table(frame, "right"), row(table(frame, "right"), "link"));
                robot.keyPress(KeyEvent.VK_DELETE);
                robot.keyRelease(KeyEvent.VK_DELETE);
                await(() -> findDialog("files.dialog.ok") != null);
                click(robot, findDialog("files.dialog.ok"));
                await(() -> !Files.exists(destination.resolve("link"), LinkOption.NOFOLLOW_LINKS));
                assertEquals("actual original", Files.readString(original.resolve("keep.txt")));
                assertEquals("actual original", Files.readString(destination.resolve("copied/keep.txt")));
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
    void explicitObservedLinkInclusionDisplaysActualSuccessfulLinkUpdatedStep() throws Exception {
        assertFalse(GraphicsEnvironment.isHeadless());
        try (var fixture = new FilesRuntimeFixture(temporary, false)) {
            var original = Files.createDirectory(fixture.local().resolve("original"));
            Files.writeString(original.resolve("keep.txt"), "original directory");
            WindowsLinks.create(fixture.local().resolve("managed"), original);
            var destination = Files.createDirectory(fixture.local().resolve("destination"));
            var workspace = new FilesWorkspace(fixture.files());
            var frame = frame(workspace);
            var robot = new Robot();
            robot.setAutoDelay(65);
            try {
                click(robot, named(frame, "files.second"));
                choose(robot, fixture, frame, "left", fixture.local());
                choose(robot, fixture, frame, "right", destination);
                cell(robot, table(frame, "left"), row(table(frame, "left"), "original"));
                keys(robot, KeyEvent.VK_CONTROL, KeyEvent.VK_X);
                click(robot, table(frame, "right"));
                keys(robot, KeyEvent.VK_CONTROL, KeyEvent.VK_V);
                await(() -> checkbox() != null);
                assertFalse(edt(() -> checkbox().isSelected()));
                click(robot, checkbox());
                click(robot, findDialog("files.dialog.ok"));
                await(() -> ((JTextArea) named(frame, "files.feedback")).getText().contains("LINK_UPDATED"));
                assertFalse(Files.exists(original));
                assertEquals("original directory", Files.readString(destination.resolve("original/keep.txt")));
                assertEquals("original directory", Files.readString(fixture.local().resolve("managed/keep.txt")));
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
    void actualChangedLinkShowsPartialUnknownBeforeCompletionAndNeverReplaysTheSecondItem() throws Exception {
        assertFalse(GraphicsEnvironment.isHeadless());
        try (var fixture = new FilesRuntimeFixture(temporary, false)) {
            var original = Files.createDirectory(fixture.local().resolve("a-original"));
            Files.writeString(original.resolve("keep.txt"), "actual first directory");
            Files.writeString(fixture.local().resolve("z-not-started"), "second retained");
            var other = Files.createDirectory(fixture.local().resolve("other"));
            Files.writeString(other.resolve("different.txt"), "different target");
            var managed = fixture.local().resolve("managed");
            WindowsLinks.create(managed, original);
            var destination = Files.createDirectory(fixture.local().resolve("destination"));
            var tampered = new AtomicBoolean();
            fixture.approval((intent, state) -> {
                if (intent.type().equals("file-move-file") && tampered.compareAndSet(false, true)) try {
                    Files.delete(managed);
                    WindowsLinks.create(managed, other);
                } catch (Exception failure) {
                    throw new AssertionError(failure);
                }
                return true;
            });
            var workspace = new FilesWorkspace(fixture.files());
            var frame = frame(workspace);
            var robot = new Robot();
            robot.setAutoDelay(65);
            TaskHandle<FilesOutcome> originalTask = null;
            try {
                click(robot, named(frame, "files.second"));
                choose(robot, fixture, frame, "left", fixture.local());
                choose(robot, fixture, frame, "right", destination);
                cell(robot, table(frame, "left"), row(table(frame, "left"), "a-original"));
                robot.keyPress(KeyEvent.VK_CONTROL);
                cell(robot, table(frame, "left"), row(table(frame, "left"), "z-not-started"));
                robot.keyRelease(KeyEvent.VK_CONTROL);
                keys(robot, KeyEvent.VK_CONTROL, KeyEvent.VK_X);
                click(robot, table(frame, "right"));
                keys(robot, KeyEvent.VK_CONTROL, KeyEvent.VK_V);
                await(() -> checkbox() != null);
                click(robot, checkbox());
                click(robot, findDialog("files.dialog.ok"));
                await(() -> ((JTextArea) named(frame, "files.feedback")).getText().contains("UNKNOWN"));
                await(() -> ((JTextArea) named(frame, "files.feedback")).getText().contains("PLANNED_LINK"));
                originalTask = mutation(workspace);
                var observed = fixture.returned(originalTask);
                assertFalse(originalTask.completion().toCompletableFuture().isDone());
                assertTrue(observed.outcome().reports().getFirst().items().stream().anyMatch(item -> item.outcome() == gold.debug.windowstolinux.appapi.task.result.TaskResult.SUCCEEDED));
                assertFalse(Files.exists(original));
                assertEquals("actual first directory", Files.readString(destination.resolve("a-original/keep.txt")));
                assertEquals("second retained", Files.readString(fixture.local().resolve("z-not-started")));
                assertFalse(Files.exists(destination.resolve("z-not-started")));
                assertEquals("different target", Files.readString(managed.resolve("different.txt")));
                assertFalse(edt(() -> ((JTextArea) named(frame, "files.feedback")).getText()).contains("LINK_UPDATED"));
                Files.delete(managed);
                WindowsLinks.create(managed, destination.resolve("a-original"));
                fixture.reconcile(originalTask);
                assertEquals(gold.debug.windowstolinux.appapi.task.result.TaskResult.FAILED, fixture.result(originalTask).outcome());
                assertFalse(Files.exists(destination.resolve("z-not-started")));
                fixture.awaitIdle();
            } finally {
                if (originalTask != null && !originalTask.completion().toCompletableFuture().isDone()) {
                    Files.delete(managed);
                    WindowsLinks.create(managed, destination.resolve("a-original"));
                    fixture.reconcile(originalTask);
                }
                edt(() -> {
                    workspace.close();
                    frame.dispose();
                    return null;
                });
            }
        }
    }

    static JCheckBox checkbox() {
        for (var window : Window.getWindows())
            if (window instanceof JDialog && window.isShowing()) {
                var box = checkboxIn(window);
                if (box != null) return box;
            }
        return null;
    }

    static JCheckBox checkboxIn(Container root) {
        for (var child : root.getComponents()) {
            if (child instanceof JCheckBox box) return box;
            if (child instanceof Container nested) {
                var box = checkboxIn(nested);
                if (box != null) return box;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    static TaskHandle<FilesOutcome> mutation(FilesWorkspace workspace) throws Exception {
        return edt(() -> {
            var tasksField = FilesWorkspace.class.getDeclaredField("tasks");
            tasksField.setAccessible(true);
            var tasks = tasksField.get(workspace);
            var listField = FilesTasks.class.getDeclaredField("mutations");
            listField.setAccessible(true);
            var values = (List<?>) listField.get(tasks);
            var mutation = values.getLast();
            var field = mutation.getClass().getDeclaredField("handle");
            field.setAccessible(true);
            return (TaskHandle<FilesOutcome>) field.get(mutation);
        });
    }
}
