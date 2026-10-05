package gold.debug.windowstolinux.mod.files.service;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import gold.debug.windowstolinux.mod.files.service.FilesRuntimeFixture;
import gold.debug.windowstolinux.app.windows.file.WindowsLinks;
import gold.debug.windowstolinux.appapi.file.model.FileInfo;
import gold.debug.windowstolinux.appapi.file.model.FilePath;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.ProblemCode;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.mod.files.file.model.FilesClipboard;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FilesLocalIntegrationTest {
    @TempDir
    Path root;

    @Test
    void actualPickerUriCreationNamesAndCollisionsUseOriginalPlatformTasksWithoutSchema() throws Exception {
        try (var fixture = new FilesRuntimeFixture(root, false)) {
            Path selected = Files.createDirectory(fixture.local().resolve("user-selected"));
            fixture.choose(selected);
            var directory = fixture.result(fixture.files().chooseLocal("Choose a real directory")).value().orElseThrow();
            assertEquals(FilePath.local(selected.toRealPath()), directory.location());
            assertTrue(directory.entries().isEmpty());
            assertEquals(TaskResult.SUCCEEDED, fixture.result(fixture.files().create(directory.location(), "folder", true)).outcome());
            assertEquals(TaskResult.SUCCEEDED, fixture.result(fixture.files().create(directory.location(), "完整名称.custom", false)).outcome());
            assertEquals(0, Files.size(selected.resolve("完整名称.custom")));
            assertEquals(TaskResult.FAILED, fixture.result(fixture.files().create(directory.location(), "完整名称.custom", false)).outcome());
            assertEquals(0, Files.size(selected.resolve("完整名称.custom")));
            assertEquals(ProblemCode.INVALID_INPUT, assertThrows(ApiException.class,
                () -> fixture.files().create(directory.location(), "../escape", false)).problem().code());
            assertEquals(List.of("folder", "完整名称.custom"), fixture.result(fixture.files().browse(directory.location())).value().orElseThrow()
                .entries().stream().map(entry -> entry.requested().name()).toList());
            fixture.noResources();
        }
    }

    @Test
    void copyAndMoveBothDirectionsPreserveActualConflictDestinationAndPreparationHasNoEffects() throws Exception {
        try (var fixture = new FilesRuntimeFixture(root, false)) {
            Path left = Files.createDirectory(fixture.local().resolve("left"));
            Path right = Files.createDirectory(fixture.local().resolve("right"));
            Files.writeString(left.resolve("data.txt"), "original content");
            Files.writeString(right.resolve("data.txt"), "existing content");
            fixture.conflict(0);
            var original = entry(fixture, left, "data.txt");
            var clipboard = new FilesClipboard(FilesClipboard.Mode.COPY, List.of(original));
            assertEquals("original content", Files.readString(left.resolve("data.txt")));
            assertEquals("existing content", Files.readString(right.resolve("data.txt")));
            var copy = fixture.result(fixture.files().transfer(clipboard, FilePath.local(right), List.of()));
            assertEquals(TaskResult.SUCCEEDED, copy.outcome());
            var destination = copy.value().orElseThrow().reports().getFirst().items().getFirst().target().orElseThrow();
            assertNotEquals(FilePath.local(right.resolve("data.txt")), destination);
            assertEquals("original content", Files.readString(Path.of(destination.path())));
            assertEquals("existing content", Files.readString(right.resolve("data.txt")));
            var copied = entry(fixture, right, destination.name());
            var moved = fixture.result(fixture.files().transfer(new FilesClipboard(FilesClipboard.Mode.MOVE, List.of(copied)), FilePath.local(left), List.of()));
            assertEquals(TaskResult.SUCCEEDED, moved.outcome());
            assertFalse(Files.exists(Path.of(destination.path())));
            assertTrue(Files.exists(left.resolve(destination.name())));
            var back = entry(fixture, left, destination.name());
            assertEquals(TaskResult.SUCCEEDED, fixture.result(fixture.files().transfer(
                new FilesClipboard(FilesClipboard.Mode.MOVE, List.of(back)), FilePath.local(right), List.of())).outcome());
            assertFalse(Files.exists(left.resolve(destination.name())));
            assertEquals("original content", Files.readString(right.resolve(destination.name())));
            fixture.noResources();
        }
    }

    @Test
    void realDirectoryJunctionCopyMoveRenameAndDeleteOperateOnLinkItselfAndKeepTarget() throws Exception {
        try (var fixture = new FilesRuntimeFixture(root, false)) {
            Path target = Files.createDirectory(fixture.local().resolve("target"));
            Files.writeString(target.resolve("keep.txt"), "target must survive");
            Path left = Files.createDirectory(fixture.local().resolve("left"));
            Path right = Files.createDirectory(fixture.local().resolve("right"));
            WindowsLinks.create(left.resolve("junction"), target);
            assertTrue(WindowsLinks.target(left.resolve("junction")).isPresent());
            var link = entry(fixture, left, "junction");
            assertEquals(FileInfo.Kind.LINK, link.kind());
            assertEquals(TaskResult.SUCCEEDED, fixture.result(fixture.files().transfer(
                new FilesClipboard(FilesClipboard.Mode.COPY, List.of(link)), FilePath.local(right), List.of())).outcome());
            assertTrue(Files.exists(left.resolve("junction"), LinkOption.NOFOLLOW_LINKS));
            assertEquals("target must survive", Files.readString(right.resolve("junction/keep.txt")));
            var copied = entry(fixture, right, "junction");
            assertEquals(TaskResult.SUCCEEDED, fixture.result(fixture.files().rename(copied, "renamed")).outcome());
            assertFalse(Files.exists(right.resolve("junction"), LinkOption.NOFOLLOW_LINKS));
            assertEquals("target must survive", Files.readString(right.resolve("renamed/keep.txt")));
            var renamed = entry(fixture, right, "renamed");
            assertEquals(TaskResult.SUCCEEDED, fixture.result(fixture.files().transfer(
                new FilesClipboard(FilesClipboard.Mode.MOVE, List.of(renamed)), FilePath.local(left), List.of())).outcome());
            assertFalse(Files.exists(right.resolve("renamed"), LinkOption.NOFOLLOW_LINKS));
            assertEquals("target must survive", Files.readString(left.resolve("renamed/keep.txt")));
            assertEquals(TaskResult.SUCCEEDED, fixture.result(fixture.files().delete(List.of(entry(fixture, left, "renamed")))).outcome());
            assertFalse(Files.exists(left.resolve("renamed"), LinkOption.NOFOLLOW_LINKS));
            assertEquals("target must survive", Files.readString(target.resolve("keep.txt")));
            fixture.noResources();
        }
    }

    @Test
    void relativeDirectoryRequestAndActualBrokenJunctionKeepOriginalTargetsAndNeverDeleteTargetContent() throws Exception {
        try (var fixture = new FilesRuntimeFixture(root, false)) {
            Path target = Files.createDirectory(fixture.local().resolve("target"));
            Files.writeString(target.resolve("keep.txt"), "keep actual directory content");
            WindowsLinks.create(fixture.local().resolve("relative"), Path.of("target"));
            Path missing = Files.createDirectory(fixture.local().resolve("missing-target"));
            WindowsLinks.create(fixture.local().resolve("broken"), Path.of("missing-target"));
            Files.delete(missing);
            Path destination = Files.createDirectory(fixture.local().resolve("destination"));
            var link = entry(fixture, fixture.local(), "relative");
            var broken = entry(fixture, fixture.local(), "broken");
            assertEquals(FileInfo.Kind.LINK, link.kind());
            assertEquals(FileInfo.Kind.LINK, broken.kind());
            assertEquals(TaskResult.SUCCEEDED, fixture.result(fixture.files().transfer(
                new FilesClipboard(FilesClipboard.Mode.COPY, List.of(link, broken)), FilePath.local(destination), List.of())).outcome());
            assertEquals("keep actual directory content", Files.readString(destination.resolve("relative/keep.txt")));
            assertTrue(WindowsLinks.target(destination.resolve("broken")).isPresent());
            assertFalse(Files.exists(destination.resolve("broken")));
            assertEquals(TaskResult.SUCCEEDED, fixture.result(fixture.files().delete(
                List.of(entry(fixture, destination, "relative"), entry(fixture, destination, "broken")))).outcome());
            assertEquals("keep actual directory content", Files.readString(target.resolve("keep.txt")));
            assertTrue(Files.exists(fixture.local().resolve("relative"), LinkOption.NOFOLLOW_LINKS));
            fixture.noResources();
        }
    }

    @Test
    void nativeFileSymlinkCreationWithoutHostPrivilegeIsRefusedAndLeavesActualTargetUntouched() throws Exception {
        try (var fixture = new FilesRuntimeFixture(root, false)) {
            Path target = Files.writeString(fixture.local().resolve("target.txt"), "actual target remains");
            Path link = fixture.local().resolve("relative-file-link");
            var rejected = assertThrows(java.nio.file.FileSystemException.class, () -> WindowsLinks.create(link, Path.of("target.txt")));
            assertEquals(link.toString(), rejected.getFile());
            assertNotNull(rejected.getReason());
            assertFalse(Files.exists(link, LinkOption.NOFOLLOW_LINKS));
            assertEquals("actual target remains", Files.readString(target));
            fixture.noResources();
        }
    }

    @Test
    void explicitlyIncludedOwnJunctionIsActuallyRetargetedAndSuccessfulMaintenanceJournalIsReturned() throws Exception {
        try (var fixture = new FilesRuntimeFixture(root, false)) {
            Path target = Files.createDirectory(fixture.local().resolve("original"));
            Files.writeString(target.resolve("keep.txt"), "original directory content");
            WindowsLinks.create(fixture.local().resolve("managed"), target);
            Path destination = Files.createDirectory(fixture.local().resolve("destination"));
            var original = entry(fixture, fixture.local(), "original");
            var link = entry(fixture, fixture.local(), "managed");
            var result = fixture.result(fixture.files().transfer(new FilesClipboard(FilesClipboard.Mode.MOVE, List.of(original)),
                FilePath.local(destination), List.of(link)));
            assertEquals(TaskResult.SUCCEEDED, result.outcome());
            assertFalse(Files.exists(target));
            assertEquals("original directory content", Files.readString(destination.resolve("original/keep.txt")));
            assertEquals("original directory content", Files.readString(fixture.local().resolve("managed/keep.txt")));
            var move = result.value().orElseThrow().operations().stream().filter(operation -> operation.type().equals("MOVE_FILE")).findFirst().orElseThrow();
            assertEquals(result.value().orElseThrow().reports().getFirst().operationId(), move.id());
            assertTrue(move.steps().stream().anyMatch(step -> step.phase().equals("PLANNED_LINK") && step.source().equals(link.actual())));
            assertTrue(move.steps().stream().anyMatch(step -> step.phase().equals("LINK_UPDATED") && step.source().equals(link.actual())));
            fixture.noResources();
        }
    }

    @Test
    void replacedSelectedIdentityRefusesNewCopyAndKeepsReplacementUntouched() throws Exception {
        try (var fixture = new FilesRuntimeFixture(root, false)) {
            Path source = Files.writeString(fixture.local().resolve("selected.txt"), "old selected");
            Path destination = Files.createDirectory(fixture.local().resolve("destination"));
            var original = entry(fixture, fixture.local(), "selected.txt");
            Files.move(source, fixture.local().resolve("old-object.txt"));
            Files.writeString(source, "replacement must survive");
            var result = fixture.result(fixture.files().transfer(new FilesClipboard(FilesClipboard.Mode.COPY, List.of(original)),
                FilePath.local(destination), List.of()));
            assertEquals(TaskResult.FAILED, result.outcome());
            assertEquals("FILES_SOURCE_CHANGED", result.problem().orElseThrow().eventCode());
            assertTrue(result.value().orElseThrow().reports().isEmpty());
            assertTrue(result.value().orElseThrow().operations().isEmpty());
            assertEquals("replacement must survive", Files.readString(source));
            assertFalse(Files.exists(destination.resolve("selected.txt")));
            fixture.noResources();
        }
    }

    @Test
    void secondItemApprovalCancellationKeepsFirstActualCopyAndStopsTheRemainingBatch() throws Exception {
        try (var fixture = new FilesRuntimeFixture(root, false)) {
            Path destination = Files.createDirectory(fixture.local().resolve("destination"));
            for (String name : List.of("one", "two", "three")) Files.writeString(fixture.local().resolve(name), name);
            var approvals = new AtomicInteger();
            fixture.approval((intent, task) -> !intent.type().equals("file-copy-file") || approvals.incrementAndGet() != 2);
            var sources = List.of(entry(fixture, fixture.local(), "one"), entry(fixture, fixture.local(), "two"), entry(fixture, fixture.local(), "three"));
            var handle = fixture.files().transfer(new FilesClipboard(FilesClipboard.Mode.COPY, sources), FilePath.local(destination), List.of());
            var result = fixture.result(handle);
            assertEquals(TaskResult.CANCELLED, result.outcome());
            assertEquals(1, result.value().orElseThrow().reports().size());
            assertEquals(TaskResult.SUCCEEDED, result.value().orElseThrow().reports().getFirst().outcome());
            assertEquals("one", Files.readString(destination.resolve("one")));
            assertFalse(Files.exists(destination.resolve("two")));
            assertFalse(Files.exists(destination.resolve("three")));
            assertEquals(2, approvals.get());
            assertTrue(fixture.files().observation(handle.id()).orElseThrow().callbackReturned());
            assertFalse(fixture.files().diagnostics(handle.id()).records().isEmpty());
            fixture.noResources();
        }
    }

    @Test
    void secondSourceIdentityChangedAfterApprovalFailsAndKeepsFirstActualCopyWithoutThirdEffect() throws Exception {
        try (var fixture = new FilesRuntimeFixture(root, false)) {
            Path destination = Files.createDirectory(fixture.local().resolve("destination"));
            for (String name : List.of("one", "two", "three")) Files.writeString(fixture.local().resolve(name), name);
            var approvals = new AtomicInteger();
            fixture.approval((intent, task) -> {
                if (intent.type().equals("file-copy-file") && approvals.incrementAndGet() == 2) {
                    try {
                        Files.move(fixture.local().resolve("two"), fixture.local().resolve("two-original"));
                        Files.writeString(fixture.local().resolve("two"), "replacement must remain");
                    } catch (java.io.IOException failure) {
                        throw new AssertionError(failure);
                    }
                }
                return true;
            });
            var sources = List.of(entry(fixture, fixture.local(), "one"), entry(fixture, fixture.local(), "two"), entry(fixture, fixture.local(), "three"));
            var handle = fixture.files().transfer(new FilesClipboard(FilesClipboard.Mode.COPY, sources), FilePath.local(destination), List.of());
            var result = fixture.result(handle);
            assertEquals(TaskResult.FAILED, result.outcome());
            assertEquals("FILE_IO", result.problem().orElseThrow().eventCode());
            assertEquals(1, result.value().orElseThrow().reports().size());
            assertEquals(TaskResult.SUCCEEDED, result.value().orElseThrow().reports().getFirst().outcome());
            assertEquals(1, result.value().orElseThrow().operations().stream().filter(operation -> operation.type().equals("COPY_FILE")).count());
            assertEquals("one", Files.readString(destination.resolve("one")));
            assertFalse(Files.exists(destination.resolve("two")));
            assertFalse(Files.exists(destination.resolve("three")));
            assertEquals("replacement must remain", Files.readString(fixture.local().resolve("two")));
            assertEquals("two", Files.readString(fixture.local().resolve("two-original")));
            assertEquals("three", Files.readString(fixture.local().resolve("three")));
            assertEquals(2, approvals.get());
            assertEquals(1, fixture.files().observation(handle.id()).orElseThrow().outcome().reports().size());
            fixture.noResources();
        }
    }

    @Test
    void cancellationDuringSecondApprovalKeepsFirstActualMoveAndNeverStartsThird() throws Exception {
        try (var fixture = new FilesRuntimeFixture(root, false)) {
            Path destination = Files.createDirectory(fixture.local().resolve("destination"));
            for (String name : List.of("one", "two", "three")) Files.writeString(fixture.local().resolve(name), name);
            var approvals = new AtomicInteger();
            var waiting = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            fixture.approval((intent, task) -> {
                if (intent.type().equals("file-move-file") && approvals.incrementAndGet() == 2) {
                    waiting.countDown();
                    try {
                        assertTrue(release.await(8, TimeUnit.SECONDS));
                    } catch (InterruptedException failure) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(failure);
                    }
                }
                return true;
            });
            var sources = List.of(entry(fixture, fixture.local(), "one"), entry(fixture, fixture.local(), "two"), entry(fixture, fixture.local(), "three"));
            var handle = fixture.files().transfer(new FilesClipboard(FilesClipboard.Mode.MOVE, sources), FilePath.local(destination), List.of());
            try {
                assertTrue(waiting.await(8, TimeUnit.SECONDS));
                assertEquals(1, fixture.files().observation(handle.id()).orElseThrow().outcome().reports().size());
                handle.requestCancel();
            } finally {
                release.countDown();
            }
            assertEquals(TaskResult.CANCELLED, fixture.result(handle).outcome());
            assertEquals("one", Files.readString(destination.resolve("one")));
            assertFalse(Files.exists(fixture.local().resolve("one")));
            assertEquals("two", Files.readString(fixture.local().resolve("two")));
            assertEquals("three", Files.readString(fixture.local().resolve("three")));
            assertFalse(Files.exists(destination.resolve("two")));
            assertFalse(Files.exists(destination.resolve("three")));
            assertEquals(2, approvals.get());
            fixture.noResources();
        }
    }

    static FileInfo entry(FilesRuntimeFixture fixture, Path directory, String name) throws Exception {
        return fixture.result(fixture.files().browse(FilePath.local(directory))).value().orElseThrow().entries().stream()
            .filter(value -> value.requested().name().equals(name)).findFirst().orElseThrow();
    }
}
