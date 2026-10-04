package gold.debug.windowstolinux.app.main.start;

import static gold.debug.windowstolinux.app.main.start.PlatformTaskAdmissionIntegrationTest.*;
import static org.junit.jupiter.api.Assertions.*;

import java.awt.*;
import java.nio.file.Path;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import javax.swing.*;

import gold.debug.windowstolinux.app.service.task.PlatformInteractionService;
import gold.debug.windowstolinux.app.ui.display.ThemePalette;
import gold.debug.windowstolinux.app.ui.error.FailureReportStore;
import gold.debug.windowstolinux.app.ui.language.MessageCatalog;
import gold.debug.windowstolinux.app.ui.task.TaskInteractionPresenter;
import gold.debug.windowstolinux.app.ui.window.PlatformFrame;
import gold.debug.windowstolinux.appapi.task.action.ActionIntent;
import gold.debug.windowstolinux.appapi.task.model.TaskSnapshot;
import gold.debug.windowstolinux.appapi.task.model.TaskState;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.appapi.task.run.CancelToken;
import gold.debug.windowstolinux.appapi.ui.page.UiEnvironment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlatformInteractionTest {
    @TempDir
    Path root;

    static ActionIntent intent() {
        return new ActionIntent("example.action", List.of(), "local target", "revision-1", "exact reviewed content 中文",
            "Effect description");
    }

    static class Prompts implements PlatformInteractionService.Prompts {
        public gold.debug.windowstolinux.appapi.linux.transfer.TransferConflictChoice transferConflict(
            gold.debug.windowstolinux.appapi.linux.transfer.TransferConflict conflict,
            gold.debug.windowstolinux.appapi.server.model.ServerId server, TaskSnapshot task,
            CancelToken cancellation) {
            return gold.debug.windowstolinux.appapi.linux.transfer.TransferConflictChoice.SKIP;
        }

        public gold.debug.windowstolinux.appapi.linux.transfer.TransferStallChoice transferStalled(
            gold.debug.windowstolinux.appapi.linux.transfer.TransferStall stall,
            gold.debug.windowstolinux.appapi.server.model.ServerId server, TaskSnapshot task,
            CancelToken cancellation) {
            return gold.debug.windowstolinux.appapi.linux.transfer.TransferStallChoice.TERMINATE;
        }

        public String transferProgress(gold.debug.windowstolinux.appapi.linux.transfer.TransferProgress progress) {
            return progress.stage().name();
        }

        public boolean approve(ActionIntent intent, TaskSnapshot task, CancelToken cancellation) {
            return true;
        }

        public char[] text(String title, String prompt, boolean secret, TaskSnapshot task,
                           CancelToken cancellation) {
            return null;
        }
    }

    @Test
    void promptOutlivingTheAuthorCallbackKeepsTheTaskFinishingUntilItActuallyReturns() throws Exception {
        try (var runtime = PlatformRuntime.open(root, PlatformStartupTest.PROTECTION)) {
            initialize(runtime, false);
            var entered = new CountDownLatch(1);
            var released = new CountDownLatch(1);
            runtime.setInteractionPresenter(new Prompts() {
                public boolean approve(ActionIntent intent, TaskSnapshot task, CancelToken cancellation) {
                    entered.countDown();
                    try {
                        assertTrue(released.await(8, TimeUnit.SECONDS));
                    } catch (InterruptedException failure) {
                        throw new AssertionError(failure);
                    }
                    return true;
                }
            });
            try (var capabilities = runtime.capabilities().remoteCapabilities(OWNER, VERSION);
                 var worker = Executors.newVirtualThreadPerTaskExecutor()) {
                var prompt = new AtomicReference<Future<?>>();
                var task = runtime.tasks().bind(OWNER, VERSION, false).submit(request(), context -> {
                    prompt.set(worker.submit(() -> assertThrows(ApiException.class,
                        () -> capabilities.interaction().approve(intent(), context))));
                    assertTrue(entered.await(8, TimeUnit.SECONDS));
                    return success(null);
                });
                try {
                    state(task, TaskState.FINISHING);
                    assertFalse(task.completion().toCompletableFuture().isDone());
                    assertEquals(1, runtime.security().securityState().activeTasks());
                } finally {
                    released.countDown();
                }
                prompt.get().get(8, TimeUnit.SECONDS);
                assertEquals(TaskResult.SUCCEEDED,
                    task.completion().toCompletableFuture().get(8, TimeUnit.SECONDS).outcome());
            }
        }
    }

    @Test
    void failedPromptReleaseRemainsRecoverableAfterTheDialogHasReturned() throws Exception {
        try (var runtime = PlatformRuntime.open(root, PlatformStartupTest.PROTECTION)) {
            initialize(runtime, false);
            runtime.setInteractionPresenter(new Prompts());
            var db = runtime.persistence().database();
            sql(db, "ALTER TABLE platform_task_resource ADD CONSTRAINT fixture_no_release CHECK(released_at IS NULL)");
            try (var capabilities = runtime.capabilities().remoteCapabilities(OWNER, VERSION)) {
                var binding = runtime.tasks().bind(OWNER, VERSION, false);
                var task = binding.submit(request(), context -> capabilities.interaction().approve(intent(), context));
                state(task, TaskState.UNKNOWN);
                assertEquals(1, count(db,
                    "SELECT COUNT(*) FROM platform_task_resource WHERE resource_kind='CUSTOM' AND released_at IS NULL"));
                sql(db, "ALTER TABLE platform_task_resource DROP CONSTRAINT fixture_no_release");
                try (var recovery = binding.registerRecovery("fixture", (previous, context) -> {
                    capabilities.closeTaskResources(context);
                    return success(null);
                })) {
                    assertEquals(TaskState.ENDED, runtime.tasks().reconcile(task.id()).toCompletableFuture()
                        .get(8, TimeUnit.SECONDS).state());
                }
                assertEquals(0, count(db, "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL"));
                assertEquals(0, runtime.security().securityState().activeTasks());
            }
        }
    }

    @Test
    void secretInputCrossingALockGenerationIsDiscardedAndEveryBufferIsWiped() throws Exception {
        try (var runtime = PlatformRuntime.open(root, PlatformStartupTest.PROTECTION)) {
            initialize(runtime, false);
            var entered = new CountDownLatch(1);
            var returnFirst = new CountDownLatch(1);
            var calls = new AtomicInteger();
            char[] discarded = "discarded-private-value".toCharArray();
            char[] accepted = "accepted-private-value".toCharArray();
            runtime.setInteractionPresenter(new Prompts() {
                public char[] text(String title, String prompt, boolean secret, TaskSnapshot task,
                                   CancelToken cancellation) {
                    assertTrue(secret);
                    if (calls.incrementAndGet() == 1) {
                        entered.countDown();
                        try {
                            assertTrue(returnFirst.await(8, TimeUnit.SECONDS));
                        } catch (InterruptedException failure) {
                            throw new AssertionError(failure);
                        }
                        return discarded;
                    }
                    return accepted;
                }
            });
            try (var capabilities = runtime.capabilities().remoteCapabilities(OWNER, VERSION)) {
                var task = runtime.tasks().bind(OWNER, VERSION, false).submit(request(),
                    context -> capabilities.interaction().requestText("Secret", "Test input", true, context));
                try {
                    assertTrue(entered.await(8, TimeUnit.SECONDS));
                    runtime.security().lock();
                    runtime.security().unlock(PASSWORD.toCharArray());
                } finally {
                    returnFirst.countDown();
                }
                assertEquals("accepted-private-value",
                    task.completion().toCompletableFuture().get(8, TimeUnit.SECONDS).value().orElseThrow());
                assertEquals(2, calls.get());
                assertArrayEquals(new char[discarded.length], discarded);
                assertArrayEquals(new char[accepted.length], accepted);
                assertEquals(0, count(runtime.persistence().database(), "SELECT COUNT(*) FROM platform_task"));
                try (var connection = runtime.persistence().database().openConnection();
                     var statement = connection.createStatement();
                     var rows = statement.executeQuery(
                         "SELECT message,details FROM runtime_log WHERE category='platform_task' AND task_id='"
                             + task.id() + "'")) {
                    assertTrue(rows.next());
                    for (int column = 1; column <= 2; column++)
                        assertFalse(rows.getString(column).contains("private-value"));
                    assertFalse(rows.next());
                }
            }
        }
    }

    @Test
    void cancellationWhileLockedStopsWaitingWithoutOpeningAPrompt() throws Exception {
        try (var runtime = PlatformRuntime.open(root, PlatformStartupTest.PROTECTION)) {
            initialize(runtime, false);
            var calls = new AtomicInteger();
            runtime.setInteractionPresenter(new Prompts() {
                public boolean approve(ActionIntent intent, TaskSnapshot task, CancelToken cancellation) {
                    calls.incrementAndGet();
                    return true;
                }
            });
            var entered = new CountDownLatch(1);
            var continueWork = new CountDownLatch(1);
            try (var capabilities = runtime.capabilities().remoteCapabilities(OWNER, VERSION)) {
                var task = runtime.tasks().bind(OWNER, VERSION, false).submit(request(), context -> {
                    entered.countDown();
                    assertTrue(continueWork.await(8, TimeUnit.SECONDS));
                    return capabilities.interaction().approve(intent(), context);
                });
                try {
                    assertTrue(entered.await(8, TimeUnit.SECONDS));
                    runtime.security().lock();
                    continueWork.countDown();
                    Thread.sleep(100);
                    task.requestCancel();
                    assertEquals(TaskResult.CANCELLED,
                        task.completion().toCompletableFuture().get(8, TimeUnit.SECONDS).outcome());
                    assertEquals(0, calls.get());
                    assertEquals(0, runtime.security().securityState().activeTasks());
                } finally {
                    continueWork.countDown();
                }
            }
        }
    }

    @Test
    void realOwnedReviewShowsAllFieldsAndCancellingTaskDisposesTheDialog() throws Exception {
        assertFalse(GraphicsEnvironment.isHeadless(), "Window acceptance requires the local desktop");
        try (var runtime = PlatformRuntime.open(root, PlatformStartupTest.PROTECTION)) {
            initialize(runtime, false);
            var frame = PlatformWindowTest.onEdt(() -> {
                var messages = MessageCatalog.forLanguageTag("zh-CN");
                var window = new PlatformFrame(runtime.security(), messages, ThemePalette.light(),
                    new UiEnvironment(Locale.SIMPLIFIED_CHINESE, "LIGHT", false), FailureReportStore.disabled());
                window.setVisible(true);
                runtime.setInteractionPresenter(new TaskInteractionPresenter(window, messages));
                return window;
            });
            try (var capabilities = runtime.capabilities().remoteCapabilities(OWNER, VERSION)) {
                var first = runtime.tasks().bind(OWNER, VERSION, false).submit(request(),
                    context -> capabilities.interaction().approve(intent(), context));
                PlatformWindowTest.await(() -> dialog(frame) != null);
                PlatformWindowTest.onEdt(() -> {
                    var dialog = dialog(frame);
                    var area = (JTextArea) PlatformWindowTest.named(dialog, "task.review.exactAction");
                    assertTrue(area.getText().contains(intent().exactAction()));
                    assertTrue(area.getText().contains(intent().revision()));
                    assertTrue(area.getText().contains(OWNER));
                    assertEquals("取消", dialog.getRootPane().getDefaultButton().getText());
                    PlatformWindowTest.capture(dialog, "platform-task-review.png");
                    PlatformWindowTest.descendants(dialog)
                        .filter(component -> component instanceof JButton button
                            && button.getText().equals("允许此次操作"))
                        .map(JButton.class::cast).findFirst().orElseThrow().doClick(0);
                    return null;
                });
                assertEquals(TaskResult.SUCCEEDED,
                    first.completion().toCompletableFuture().get(8, TimeUnit.SECONDS).outcome());
                var second = runtime.tasks().bind(OWNER, VERSION, false).submit(request(),
                    context -> capabilities.interaction().approve(intent(), context));
                PlatformWindowTest.await(() -> dialog(frame) != null);
                second.requestCancel();
                assertEquals(TaskResult.CANCELLED,
                    second.completion().toCompletableFuture().get(8, TimeUnit.SECONDS).outcome());
                PlatformWindowTest.await(() -> dialog(frame) == null);
            } finally {
                PlatformWindowTest.onEdt(() -> {
                    for (var owned : frame.getOwnedWindows())
                        owned.dispose();
                    frame.dispose();
                    return null;
                });
            }
        }
    }

    static JDialog dialog(JFrame frame) {
        return Arrays.stream(frame.getOwnedWindows())
            .filter(window -> window instanceof JDialog && window.isShowing()
                && "task.interaction.dialog".equals(window.getName()))
            .map(JDialog.class::cast).findFirst().orElse(null);
    }
}
