package gold.debug.windowstolinux.mod.files.service;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import gold.debug.windowstolinux.mod.files.service.FilesRuntimeFixture;
import gold.debug.windowstolinux.app.windows.file.WindowsLinks;
import gold.debug.windowstolinux.appapi.file.model.FilePath;
import gold.debug.windowstolinux.appapi.task.model.TaskState;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.mod.files.file.model.FilesClipboard;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FilesRecoveryIntegrationTest {
    @TempDir
    Path root;

    @Test
    void movedFileAndChangedLinkRemainVisibleBeforeUnknownCompletionAndOriginalReconciliationNeverReplaysBatch() throws Exception {
        try (var fixture = new FilesRuntimeFixture(root, false)) {
            Path original = Files.createDirectory(fixture.local().resolve("original"));
            Files.writeString(original.resolve("keep"), "actual first directory");
            Files.writeString(fixture.local().resolve("not-started"), "second selected file stays");
            Path other = Files.createDirectory(fixture.local().resolve("other"));
            Files.writeString(other.resolve("different"), "different actual target");
            Path managed = fixture.local().resolve("managed");
            WindowsLinks.create(managed, original);
            Path destination = Files.createDirectory(fixture.local().resolve("destination"));
            var sources = List.of(FilesLocalIntegrationTest.entry(fixture, fixture.local(), "original"),
                FilesLocalIntegrationTest.entry(fixture, fixture.local(), "not-started"));
            var link = FilesLocalIntegrationTest.entry(fixture, fixture.local(), "managed");
            var tampered = new AtomicBoolean();
            fixture.approval((intent, task) -> {
                if (intent.type().equals("file-move-file") && tampered.compareAndSet(false, true)) {
                    try {
                        Files.delete(managed);
                        WindowsLinks.create(managed, other);
                    } catch (Exception failure) {
                        throw new AssertionError(failure);
                    }
                }
                return true;
            });
            var handle = fixture.files().transfer(new FilesClipboard(FilesClipboard.Mode.MOVE, sources), FilePath.local(destination), List.of(link));
            var observed = fixture.returned(handle);
            assertEquals(TaskState.UNKNOWN, handle.snapshot().state());
            assertFalse(handle.completion().toCompletableFuture().isDone());
            assertTrue(observed.problem().isPresent());
            assertEquals(TaskResult.UNKNOWN, observed.outcome().reports().getFirst().outcome());
            assertTrue(observed.outcome().reports().getFirst().items().stream().anyMatch(item -> item.outcome() == TaskResult.SUCCEEDED));
            assertTrue(observed.outcome().reports().getFirst().items().stream().anyMatch(item -> item.outcome() == TaskResult.UNKNOWN));
            assertFalse(Files.exists(original));
            assertEquals("actual first directory", Files.readString(destination.resolve("original/keep")));
            assertEquals("second selected file stays", Files.readString(fixture.local().resolve("not-started")));
            assertFalse(Files.exists(destination.resolve("not-started")));
            assertEquals("different actual target", Files.readString(managed.resolve("different")));
            var move = observed.outcome().operations().stream().filter(operation -> operation.type().equals("MOVE_FILE")).findFirst().orElseThrow();
            assertTrue(move.steps().stream().anyMatch(step -> step.phase().equals("PLANNED_LINK")));
            assertFalse(move.steps().stream().anyMatch(step -> step.phase().equals("LINK_UPDATED")));
            assertFalse(fixture.files().diagnostics(handle.id()).records().isEmpty());
            fixture.reconcile(handle);
            assertEquals(TaskState.UNKNOWN, handle.snapshot().state());
            assertFalse(handle.completion().toCompletableFuture().isDone());
            var continuation = fixture.files().continueLinks(move.id(), FilePath.local(fixture.local()));
            assertEquals(TaskResult.FAILED, fixture.result(continuation).outcome());
            assertEquals("different actual target", Files.readString(managed.resolve("different")));
            Files.delete(managed);
            WindowsLinks.create(managed, destination.resolve("original"));
            fixture.reconcile(handle);
            assertEquals(TaskResult.FAILED, fixture.result(handle).outcome(), "Only one of two requested effects ran; recovery must not mark the whole batch successful");
            assertEquals("actual first directory", Files.readString(managed.resolve("keep")));
            assertEquals("second selected file stays", Files.readString(fixture.local().resolve("not-started")));
            assertFalse(Files.exists(destination.resolve("not-started")));
            assertEquals(1, fixture.files().observation(handle.id()).orElseThrow().outcome().operations().stream()
                .filter(operation -> operation.type().equals("MOVE_FILE")).count());
            fixture.noResources();
        }
    }

    @Test
    void unknownForeignOperationCannotTriggerFreshFileMovementOrDeleteSource() throws Exception {
        try (var fixture = new FilesRuntimeFixture(root, false)) {
            Path kept = Files.writeString(fixture.local().resolve("kept"), "original kept");
            var result = fixture.result(fixture.files().continueLinks(UUID.randomUUID(), FilePath.local(fixture.local())));
            assertEquals(TaskResult.FAILED, result.outcome());
            assertTrue(result.value().orElseThrow().reports().isEmpty());
            assertTrue(result.value().orElseThrow().operations().isEmpty());
            assertEquals("original kept", Files.readString(kept));
            assertTrue(Files.exists(kept, LinkOption.NOFOLLOW_LINKS));
            fixture.noResources();
        }
    }
}
