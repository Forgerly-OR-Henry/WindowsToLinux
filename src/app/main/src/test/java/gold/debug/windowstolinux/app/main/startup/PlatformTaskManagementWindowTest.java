package gold.debug.windowstolinux.app.main.startup;

import static gold.debug.windowstolinux.app.main.startup.PlatformTaskAdmissionIntegrationTest.*;
import static gold.debug.windowstolinux.app.main.startup.PlatformWindowTest.*;
import static org.junit.jupiter.api.Assertions.*;

import java.awt.*;
import java.nio.file.Path;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import javax.swing.*;

import gold.debug.windowstolinux.app.service.contract.spi.TaskManagement;
import gold.debug.windowstolinux.app.service.task.PlatformTaskManagement;
import gold.debug.windowstolinux.app.ui.component.DesktopTaskExecutor;
import gold.debug.windowstolinux.app.ui.diagnostic.FailureReportStore;
import gold.debug.windowstolinux.app.ui.display.*;
import gold.debug.windowstolinux.app.ui.i18n.MessageCatalog;
import gold.debug.windowstolinux.app.ui.shell.PlatformFrame;
import gold.debug.windowstolinux.appapi.task.*;
import gold.debug.windowstolinux.appapi.ui.UiEnvironment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlatformTaskManagementWindowTest {
    @TempDir
    Path root;

    static OperationResult<Void> unknown() {
        return new OperationResult<>(Outcome.UNKNOWN, Optional.empty(), Optional.of(new Problem(ProblemCode.UNAVAILABLE,
                "FIXTURE_UNCONFIRMED", "Original result is unconfirmed", Map.of(), Optional.empty())));
    }

    static PlatformTaskManagement management(PlatformRuntime runtime) throws ApiException {
        runtime.setInteractionPresenter(new PlatformRemoteCapabilitiesTest.Reviews());
        var recovery = runtime.recovery();
        return new PlatformTaskManagement(runtime.tasks(), runtime.security(), id -> recovery.reconcile(id, true),
                runtime::releaseModRecovery);
    }

    static PlatformFrame window(PlatformRuntime runtime, TaskManagement service) throws Exception {
        assertFalse(GraphicsEnvironment.isHeadless());
        return onEdt(() -> {
            DesktopThemeService.install(ThemeMode.LIGHT);
            var frame = new PlatformFrame(runtime.security(), MessageCatalog.forLanguageTag("zh-CN"),
                    ThemePalette.light(), new UiEnvironment(Locale.SIMPLIFIED_CHINESE, "LIGHT", false),
                    FailureReportStore.disabled());
            frame.installLogs(runtime.logs());
            frame.installTasks(service);
            ((JTabbedPane) named(frame, "platform.pages")).setSelectedComponent(named(frame, "platform.tasks"));
            frame.setVisible(true);
            return frame;
        });
    }

    static void idle(PlatformFrame frame) throws Exception {
        await(() -> named(frame, "tasks.reload").isEnabled() && !DesktopTaskExecutor.hasActiveTasks());
    }

    static void select(PlatformFrame frame) throws Exception {
        idle(frame);
        onEdt(() -> {
            var table = (JTable) named(frame, "tasks.table");
            assertEquals(1, table.getRowCount());
            table.setRowSelectionInterval(0, 0);
            return null;
        });
    }

    static void click(PlatformFrame frame, String name) throws Exception {
        onEdt(() -> {
            ((JButton) named(frame, name)).doClick(0);
            return null;
        });
    }

    static JDialog confirm(PlatformFrame frame, String name) throws Exception {
        SwingUtilities.invokeLater(() -> ((JButton) named(frame, name)).doClick(0));
        await(() -> Arrays.stream(frame.getOwnedWindows())
                .anyMatch(w -> w.isShowing() && "tasks.confirmation".equals(w.getName())));
        return onEdt(() -> (JDialog) Arrays.stream(frame.getOwnedWindows())
                .filter(w -> w.isShowing() && "tasks.confirmation".equals(w.getName())).findFirst().orElseThrow());
    }

    static JOptionPane option(JDialog dialog) throws Exception {
        return onEdt(() -> (JOptionPane) descendants(dialog).filter(JOptionPane.class::isInstance).findFirst()
                .orElseThrow());
    }

    static void answer(JDialog dialog, int value) throws Exception {
        var option = option(dialog);
        onEdt(() -> {
            option.setValue(value);
            return null;
        });
        await(() -> !dialog.isDisplayable());
    }

    static void dispose(PlatformFrame frame) throws Exception {
        onEdt(() -> {
            frame.dispose();
            return null;
        });
        await(() -> !frame.isDisplayable() && !DesktopTaskExecutor.hasActiveTasks());
    }

    @Test
    void originalVersionAndResourceAreVisibleAndExplicitRecoveryArchivesIntoExistingLogs() throws Exception {
        try (var runtime = PlatformRuntime.open(root, PlatformStartupTest.PROTECTION)) {
            initialize(runtime, false);
            var tasks = runtime.tasks().bind(OWNER, VERSION, false);
            var lease = new AtomicReference<ResourceLease>();
            var task = tasks.submit(request(), context -> {
                lease.set(context.retain("file", "original-file", "原文件尚待核对"));
                return unknown();
            });
            state(task, TaskState.UNKNOWN);
            var frame = window(runtime, management(runtime));
            var recovered = new AtomicInteger();
            try {
                select(frame);
                onEdt(() -> {
                    var table = (JTable) named(frame, "tasks.table");
                    assertEquals(OWNER, table.getValueAt(0, 0));
                    assertEquals(VERSION, table.getValueAt(0, 1));
                    assertEquals("原版本恢复未登记", table.getValueAt(0, 4));
                    assertFalse(named(frame, "tasks.reconcile").isEnabled());
                    assertFalse(named(frame, "tasks.cancel").isEnabled());
                    assertTrue(((JTextArea) named(frame, "tasks.details")).getText().contains("original-file"));
                    capture(frame, "platform-task-missing-recovery.png");
                    return null;
                });
                try (var recovery = tasks.registerRecovery("fixture", (before, context) -> {
                    recovered.incrementAndGet();
                    assertEquals(task.id(), context.id());
                    lease.get().close();
                    return success(null);
                })) {
                    click(frame, "tasks.reload");
                    select(frame);
                    var dialog = confirm(frame, "tasks.reconcile");
                    onEdt(() -> {
                        String text = descendants(dialog).filter(JTextArea.class::isInstance).map(JTextArea.class::cast)
                                .map(JTextArea::getText).findFirst().orElseThrow();
                        assertTrue(text.contains(task.id().toString()));
                        assertTrue(text.contains(OWNER + " @ " + VERSION));
                        capture(dialog, "platform-task-reconcile-confirmation.png");
                        return null;
                    });
                    assertEquals(0, recovered.get());
                    answer(dialog, JOptionPane.OK_OPTION);
                    idle(frame);
                    assertEquals(1, recovered.get());
                    assertEquals(Outcome.SUCCEEDED,
                            task.completion().toCompletableFuture().get(5, TimeUnit.SECONDS).outcome());
                    assertEquals(0, count(runtime.persistence().database(), "SELECT COUNT(*) FROM platform_task"));
                    assertEquals(1, count(runtime.persistence().database(),
                            "SELECT COUNT(*) FROM runtime_log WHERE category='platform_task'"));
                    onEdt(() -> {
                        assertEquals(0, ((JTable) named(frame, "tasks.table")).getRowCount());
                        return null;
                    });
                    click(frame, "tasks.history");
                    await(() -> named(frame, "platform.logs").isShowing());
                }
            } finally {
                dispose(frame);
                if (!task.completion().toCompletableFuture().isDone()) {
                    try (var recovery = tasks.registerRecovery("fixture", (before, context) -> {
                        lease.get().close();
                        return success(null);
                    })) {
                        runtime.tasks().reconcile(task.id()).toCompletableFuture().get(5, TimeUnit.SECONDS);
                    }
                }
            }
        }
    }

    @Test
    void confirmedCancellationKeepsOriginalWorkUntilItActuallyReturns() throws Exception {
        try (var runtime = PlatformRuntime.open(root, PlatformStartupTest.PROTECTION)) {
            initialize(runtime, false);
            var release = new CountDownLatch(1);
            var task = runtime.tasks().bind(OWNER, VERSION, false).submit(request(), context -> {
                assertTrue(release.await(20, TimeUnit.SECONDS));
                assertTrue(context.cancellation().requested());
                return new OperationResult<Void>(Outcome.CANCELLED, Optional.empty(), Optional.empty());
            });
            state(task, TaskState.RUNNING);
            var frame = window(runtime, management(runtime));
            try {
                select(frame);
                answer(confirm(frame, "tasks.cancel"), JOptionPane.OK_OPTION);
                idle(frame);
                assertEquals(TaskState.CANCEL_REQUESTED, task.snapshot().state());
                assertFalse(task.completion().toCompletableFuture().isDone());
                assertEquals(1, count(runtime.persistence().database(), "SELECT COUNT(*) FROM platform_task"));
                onEdt(() -> {
                    assertFalse(named(frame, "tasks.cancel").isEnabled());
                    assertFalse(named(frame, "tasks.reconcile").isEnabled());
                    assertTrue(((JLabel) named(frame, "tasks.status")).getText().contains("已请求取消"));
                    return null;
                });
                release.countDown();
                assertEquals(Outcome.CANCELLED,
                        task.completion().toCompletableFuture().get(5, TimeUnit.SECONDS).outcome());
                click(frame, "tasks.reload");
                idle(frame);
                onEdt(() -> {
                    assertEquals(0, ((JTable) named(frame, "tasks.table")).getRowCount());
                    return null;
                });
            } finally {
                release.countDown();
                dispose(frame);
            }
        }
    }

    @Test
    void lockingOrDisposingTheActualWindowInvalidatesAnAlreadyOpenConfirmation() throws Exception {
        try (var runtime = PlatformRuntime.open(root, PlatformStartupTest.PROTECTION)) {
            initialize(runtime, false);
            var tasks = runtime.tasks().bind(OWNER, VERSION, false);
            var task = tasks.submit(request(), context -> unknown());
            state(task, TaskState.UNKNOWN);
            var calls = new AtomicInteger();
            try (var registration = tasks.registerRecovery("fixture", (before, context) -> {
                calls.incrementAndGet();
                return success(null);
            })) {
                var frame = window(runtime, management(runtime));
                try {
                    select(frame);
                    var dialog = confirm(frame, "tasks.reconcile");
                    var stale = option(dialog);
                    runtime.security().lock();
                    await(() -> !dialog.isDisplayable());
                    onEdt(() -> {
                        stale.setValue(JOptionPane.OK_OPTION);
                        assertEquals(0, ((JTable) named(frame, "tasks.table")).getRowCount());
                        assertEquals("", ((JTextArea) named(frame, "tasks.details")).getText());
                        return null;
                    });
                    runtime.security().unlock(PASSWORD.toCharArray());
                    select(frame);
                    var closing = confirm(frame, "tasks.reconcile");
                    var closingOption = option(closing);
                    dispose(frame);
                    onEdt(() -> {
                        closingOption.setValue(JOptionPane.OK_OPTION);
                        return null;
                    });
                    assertFalse(closing.isDisplayable());
                    assertEquals(0, calls.get());
                    assertEquals(TaskState.UNKNOWN, task.snapshot().state());
                } finally {
                    dispose(frame);
                    if (!runtime.security().securityState().unlocked())
                        runtime.security().unlock(PASSWORD.toCharArray());
                    runtime.tasks().reconcile(task.id()).toCompletableFuture().get(5, TimeUnit.SECONDS);
                }
            }
        }
    }

    @Test
    void metadataOnlyCompletionOffersArchiveWithoutReplayingTheOriginalCallback() throws Exception {
        try (var runtime = PlatformRuntime.open(root, PlatformStartupTest.PROTECTION)) {
            initialize(runtime, false);
            var db = runtime.persistence().database();
            sql(db, "ALTER TABLE runtime_log ADD CONSTRAINT task_window_archive CHECK(category<>'platform_task')");
            var calls = new AtomicInteger();
            var task = runtime.tasks().bind(OWNER, VERSION, false).submit(request(), context -> {
                calls.incrementAndGet();
                return success("original");
            });
            state(task, TaskState.FINISHING);
            sql(db, "ALTER TABLE runtime_log DROP CONSTRAINT task_window_archive");
            var frame = window(runtime, management(runtime));
            try {
                select(frame);
                onEdt(() -> {
                    assertEquals("补齐归档", ((JButton) named(frame, "tasks.reconcile")).getText());
                    assertEquals("仅待平台归档", ((JTable) named(frame, "tasks.table")).getValueAt(0, 4));
                    return null;
                });
                var dialog = confirm(frame, "tasks.reconcile");
                onEdt(() -> {
                    assertTrue(descendants(dialog).filter(JTextArea.class::isInstance).map(JTextArea.class::cast)
                            .anyMatch(text -> text.getText().contains("不会调用业务或业务恢复回调")));
                    return null;
                });
                answer(dialog, JOptionPane.OK_OPTION);
                idle(frame);
                assertEquals(1, calls.get());
                var result = task.completion().toCompletableFuture().get(5, TimeUnit.SECONDS);
                assertEquals(Outcome.SUCCEEDED, result.outcome());
                assertTrue(result.value().isEmpty());
                assertEquals(0, count(db, "SELECT COUNT(*) FROM platform_task"));
            } finally {
                dispose(frame);
                if (!task.completion().toCompletableFuture().isDone())
                    runtime.tasks().reconcile(task.id()).toCompletableFuture().get(5, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void originalHostRescueRequiresItsCommandConfirmationAndUsesTheActualRecoveryService() throws Exception {
        UUID id;
        try (var db = gold.debug.windowstolinux.app.db.persistence.connection.PlatformDatabase.open(root,
                PlatformStartupTest.PROTECTION)) {
            var persistence = new gold.debug.windowstolinux.app.db.PlatformPersistence(db);
            try (var security = new gold.debug.windowstolinux.app.service.security.DesktopSecurityService(
                    persistence.security())) {
                security.setupAndUnlock(PASSWORD.toCharArray(), PASSWORD.toCharArray());
            }
            var ledger = new gold.debug.windowstolinux.app.db.task.TaskLedger(db);
            id = ledger.enqueue(UUID.randomUUID(), "@platform", "0.0.1-original",
                    new TaskRequest(gold.debug.windowstolinux.app.service.task.PlatformTaskAdmission.RESCUE_TYPE,
                            "Original rescue fixture", List.of(), true),
                    List.of(new gold.debug.windowstolinux.app.db.task.TaskResource("MOD", "@platform", "@platform",
                            false)));
            assertTrue(ledger.tryStart(id));
            // Raw historical ledger fixture has no live callback or physical resource. / 原历史账本夹具没有活动回调或物理资源。
        }
        try (var runtime = PlatformRuntime.open(root, PlatformStartupTest.PROTECTION)) {
            runtime.security().unlock(PASSWORD.toCharArray());
            var service = management(runtime);
            assertThrows(ApiException.class, () -> service.reconcile(id, false));
            var frame = window(runtime, service);
            try {
                select(frame);
                var dialog = confirm(frame, "tasks.reconcile");
                onEdt(() -> {
                    assertTrue(descendants(dialog).filter(JTextArea.class::isInstance).map(JTextArea.class::cast)
                            .anyMatch(text -> text.getText().contains("终端命令已结束")
                                    && text.getText().contains("0.0.1-original")));
                    capture(dialog, "platform-task-rescue-confirmation.png");
                    return null;
                });
                answer(dialog, JOptionPane.CANCEL_OPTION);
                assertEquals(1, service.list().size());
                answer(confirm(frame, "tasks.reconcile"), JOptionPane.OK_OPTION);
                idle(frame);
                assertTrue(service.list().isEmpty());
                assertTrue(runtime.recovery().pending().isEmpty());
                assertEquals(1, count(runtime.persistence().database(),
                        "SELECT COUNT(*) FROM runtime_log WHERE category='platform_task' AND event_code='PLATFORM_TASK_FAILED'"));
            } finally {
                dispose(frame);
                if (!service.list().isEmpty())
                    service.reconcile(id, true).toCompletableFuture().get(5, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void lateInventoryFromBeforeLockCannotRestorePrivateRowsAfterUnlockOrClose() throws Exception {
        try (var runtime = PlatformRuntime.open(root, PlatformStartupTest.PROTECTION)) {
            initialize(runtime, false);
            var tasks = runtime.tasks().bind(OWNER, VERSION, false);
            var task = tasks.submit(request(), context -> unknown());
            state(task, TaskState.UNKNOWN);
            var original = management(runtime).list();
            try (var registration = tasks.registerRecovery("fixture", (before, context) -> success(null))) {
                for (boolean close : List.of(false, true)) {
                    var entered = new CountDownLatch(1);
                    var release = new CountDownLatch(1);
                    var reads = new AtomicInteger();
                    TaskManagement delayed = new TaskManagement() {
                        @Override
                        public List<Entry> list() {
                            assertFalse(SwingUtilities.isEventDispatchThread());
                            if (reads.incrementAndGet() > 1)
                                return List.of();
                            entered.countDown();
                            try {
                                assertTrue(release.await(15, TimeUnit.SECONDS));
                            } catch (InterruptedException failure) {
                                throw new AssertionError(failure);
                            }
                            return original;
                        }

                        @Override
                        public TaskSnapshot requestCancel(UUID id) {
                            throw new AssertionError();
                        }

                        @Override
                        public CompletionStage<TaskSnapshot> reconcile(UUID id, boolean confirmed) {
                            throw new AssertionError();
                        }
                    };
                    var frame = window(runtime, delayed);
                    try {
                        assertTrue(entered.await(5, TimeUnit.SECONDS));
                        if (close)
                            onEdt(() -> {
                                frame.dispose();
                                return null;
                            });
                        else {
                            runtime.security().lock();
                            await(() -> !frame.getContentPane().isVisible());
                            runtime.security().unlock(PASSWORD.toCharArray());
                            await(() -> frame.getContentPane().isVisible());
                        }
                        release.countDown();
                        if (close)
                            await(() -> !DesktopTaskExecutor.hasActiveTasks());
                        else
                            idle(frame);
                        onEdt(() -> {
                            assertEquals(0, ((JTable) named(frame, "tasks.table")).getRowCount());
                            assertEquals("", ((JTextArea) named(frame, "tasks.details")).getText());
                            return null;
                        });
                        assertEquals(close ? 1 : 2, reads.get());
                    } finally {
                        release.countDown();
                        dispose(frame);
                    }
                }
                runtime.tasks().reconcile(task.id()).toCompletableFuture().get(5, TimeUnit.SECONDS);
            }
        }
    }
}
