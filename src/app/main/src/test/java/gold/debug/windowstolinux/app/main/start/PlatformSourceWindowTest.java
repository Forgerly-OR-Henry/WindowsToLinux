package gold.debug.windowstolinux.app.main.start;

import static gold.debug.windowstolinux.app.main.start.PlatformServerRefTest.completed;
import static gold.debug.windowstolinux.app.main.start.PlatformServersWindowTest.dialog;
import static gold.debug.windowstolinux.app.main.start.PlatformTaskAdmissionIntegrationTest.*;
import static gold.debug.windowstolinux.app.main.start.PlatformWindowTest.*;
import static org.junit.jupiter.api.Assertions.*;

import java.awt.*;
import java.nio.file.*;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import javax.swing.*;

import gold.debug.windowstolinux.app.ui.display.*;
import gold.debug.windowstolinux.app.ui.error.FailureReportStore;
import gold.debug.windowstolinux.app.ui.language.MessageCatalog;
import gold.debug.windowstolinux.app.ui.task.TaskInteractionPresenter;
import gold.debug.windowstolinux.app.ui.window.PlatformFrame;
import gold.debug.windowstolinux.appapi.file.source.FileReadRequest;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;
import gold.debug.windowstolinux.appapi.task.result.Problem;
import gold.debug.windowstolinux.appapi.task.result.ProblemCode;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.appapi.task.run.TaskHandle;
import gold.debug.windowstolinux.appapi.ui.page.UiEnvironment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlatformSourceWindowTest {
    @TempDir
    Path root;

    @Test
    void realOwnedChooserConfirmsDirectoryAndTaskCancellationDisposesItBeforeRelease() throws Exception {
        assertFalse(GraphicsEnvironment.isHeadless());
        var selected = Files.createDirectory(root.resolve("中文 源码"));
        Files.writeString(selected.resolve("file"), "fixture");
        try (var runtime = PlatformRuntime.open(root.resolve("data"), PlatformStartupTest.PROTECTION)) {
            initialize(runtime, false);
            var frame = onEdt(() -> {
                DesktopThemeService.install(ThemeMode.LIGHT);
                var messages = MessageCatalog.forLanguageTag("zh-CN");
                var window = new PlatformFrame(runtime.security(), messages, ThemePalette.light(),
                    new UiEnvironment(Locale.SIMPLIFIED_CHINESE, "LIGHT", false), FailureReportStore.disabled());
                var presenter = new TaskInteractionPresenter(window, messages);
                runtime.setInteractionPresenter(presenter);
                runtime.setSourcePicker(presenter);
                window.setVisible(true);
                return window;
            });
            TaskHandle<?> pending = null;
            var callbackThrowable = new java.util.concurrent.atomic.AtomicReference<Throwable>();
            var cancellationProblem = new java.util.concurrent.atomic.AtomicReference<Problem>();
            try {
                var source = runtime.capabilities().source(OWNER, VERSION);
                var binding = runtime.tasks().bind(OWNER, VERSION, false);
                var read = binding.submit(request(), task -> {
                    var selection = source.selectDirectory("选择源码目录", task);
                    return success(source.read(selection, new FileReadRequest("file", 7), task));
                });
                pending = read;
                await(() -> dialog("task.interaction.dialog") != null);
                onEdt(() -> {
                    var window = dialog("task.interaction.dialog");
                    assertSame(frame, window.getOwner());
                    var chooser = (JFileChooser) named(window, "source.directory");
                    chooser.setCurrentDirectory(selected.getParent().toFile());
                    chooser.setSelectedFile(selected.toFile());
                    return null;
                });
                await(() -> descendants(named(dialog("task.interaction.dialog"), "source.directory"))
                    .filter(JList.class::isInstance).map(JList.class::cast)
                    .anyMatch(list -> java.util.stream.IntStream.range(0, list.getModel().getSize())
                        .mapToObj(list.getModel()::getElementAt).anyMatch(selected.toFile()::equals)));
                onEdt(() -> {
                    var window = dialog("task.interaction.dialog");
                    capture(window, "platform-source-selection.png");
                    descendants(window).filter(JOptionPane.class::isInstance).map(JOptionPane.class::cast).findFirst()
                        .orElseThrow().setValue(JOptionPane.OK_OPTION);
                    return null;
                });
                assertEquals("fixture", new String(completed(read).content()));
                await(() -> dialog("task.interaction.dialog") == null);
                var cancel = binding.submit(request(), task -> {
                    try {
                        var cancelled = assertThrows(ApiException.class, () -> source.selectDirectory("取消目录选择", task));
                        cancellationProblem.set(cancelled.problem());
                        assertEquals(ProblemCode.CANCELLED, cancelled.problem().code());
                        return new OperationResult<>(TaskResult.CANCELLED, java.util.Optional.empty(), java.util.Optional.of(cancelled.problem()));
                    } catch (RuntimeException | Error failure) {
                        callbackThrowable.set(failure);
                        throw failure;
                    }
                });
                pending = cancel;
                await(() -> dialog("task.interaction.dialog") != null);
                cancel.requestCancel();
                assertEquals(TaskResult.CANCELLED,
                    completedWithDiagnostics(runtime, cancel, callbackThrowable, cancellationProblem).outcome());
                assertNull(onEdt(() -> dialog("task.interaction.dialog")));
                assertEquals(0, count(runtime.persistence().database(),
                    "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL"));
            } finally {
                if (pending != null && !pending.completion().toCompletableFuture().isDone())
                    pending.requestCancel();
                onEdt(() -> {
                    for (Window window : frame.getOwnedWindows())
                        window.dispose();
                    frame.dispose();
                    return null;
                });
                if (pending != null)
                    completedWithDiagnostics(runtime, pending, callbackThrowable, cancellationProblem);
            }
        }
    }

    private static OperationResult<?> completedWithDiagnostics(PlatformRuntime runtime, TaskHandle<?> task,
                                                               java.util.concurrent.atomic.AtomicReference<Throwable> callbackThrowable,
                                                               java.util.concurrent.atomic.AtomicReference<Problem> cancellationProblem) throws Exception {
        try {
            return task.completion().toCompletableFuture().get(8, TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException timeout) {
            var failure = new AssertionError("Original source chooser task=" + task.snapshot() + "; cancellationProblem=" + cancellationProblem.get() + "; activeResources=" + count(runtime.persistence().database(), "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL"), timeout);
            if (callbackThrowable.get() != null) failure.addSuppressed(callbackThrowable.get());
            throw failure;
        }
    }
}
