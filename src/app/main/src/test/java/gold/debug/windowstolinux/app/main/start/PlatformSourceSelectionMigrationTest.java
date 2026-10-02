package gold.debug.windowstolinux.app.main.start;

import gold.debug.windowstolinux.appapi.task.ApiException;
import gold.debug.windowstolinux.appapi.task.OperationResult;
import gold.debug.windowstolinux.appapi.task.Outcome;
import gold.debug.windowstolinux.appapi.task.ProblemCode;
import gold.debug.windowstolinux.appapi.task.TaskRequest;
import gold.debug.windowstolinux.appapi.task.TaskState;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import gold.debug.windowstolinux.app.service.task.PlatformTaskAdmission;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Original local-directory identification dimensions through actual host picker and task capabilities. / 经实际宿主选择器及任务能力保留原本地目录识别维度。 */
class PlatformSourceSelectionMigrationTest {
    @TempDir
    Path root;

    @Test
    void selectedDirectoryAndFileUriPathsRejectMissingOrRegularFileSourcesWithoutTextReinterpretation() throws Exception {
        Path directory = Files.createDirectories(root.resolve("selected directory"));
        Path file = Files.writeString(root.resolve("file.txt"), "fixture");
        var selectedPath = new AtomicReference<Path>(directory);
        try (var runtime = PlatformRuntime.open(root.resolve("database"), PlatformStartupTest.PROTECTION)) {
            runtime.security().setupAndUnlock(PlatformTaskAdmissionIntegrationTest.PASSWORD.toCharArray(), PlatformTaskAdmissionIntegrationTest.PASSWORD.toCharArray());
            runtime.setSourcePicker((title, task, cancel) -> selectedPath.get());
            runtime.started();
            var source = runtime.capabilities().source(PlatformTaskAdmission.PLATFORM_OWNER, runtime.version());
            var binding = runtime.tasks().bind(PlatformTaskAdmission.PLATFORM_OWNER, runtime.version(), false);
            var original = binding.submit(new TaskRequest("platform.source.selection.migration", "Actual directory consent", List.of(), true), task -> {
                for (Path path : List.of(directory, Path.of(directory.toUri()))) {
                    selectedPath.set(path);
                    var selected = source.selectDirectory("Directory selection", task);
                    assertEquals(directory.toRealPath().toUri(), source.location(selected, task));
                    assertEquals(directory.getFileName().toString(), selected.displayName());
                    assertTrue(source.list(selected, "", 100, task).isEmpty());
                }
                for (Path invalid : List.of(root.resolve("missing"), file)) {
                    selectedPath.set(invalid);
                    var rejected = assertThrows(ApiException.class, () -> source.selectDirectory("Directory selection", task));
                    assertEquals(ProblemCode.IO_FAILURE, rejected.problem().code());
                    assertEquals("SOURCE_UNAVAILABLE", rejected.problem().eventCode());
                }
                assertEquals(ProblemCode.INVALID_INPUT, assertThrows(ApiException.class, () -> source.selectDirectory("", task)).problem().code());
                return new OperationResult<Void>(Outcome.SUCCEEDED, Optional.empty(), Optional.empty());
            });
            assertEquals(Outcome.SUCCEEDED, original.completion().toCompletableFuture().get(15, TimeUnit.SECONDS).outcome());
            assertEquals(TaskState.ENDED, original.snapshot().state());
            assertEquals(PlatformTaskAdmission.PLATFORM_OWNER, original.snapshot().ownerId());
            assertEquals(runtime.version(), original.snapshot().ownerVersion());
            assertEquals(0, PlatformTaskAdmissionIntegrationTest.count(runtime.persistence().database(), "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL"));
        }
    }
}
