package gold.debug.windowstolinux.app.main.start;

import static gold.debug.windowstolinux.app.main.start.PlatformServersWindowTest.dialog;
import static gold.debug.windowstolinux.app.main.start.PlatformTaskAdmissionIntegrationTest.count;
import static gold.debug.windowstolinux.app.main.start.PlatformTaskAdmissionIntegrationTest.sql;
import static gold.debug.windowstolinux.app.main.start.PlatformWindowTest.*;
import static org.junit.jupiter.api.Assertions.*;

import java.awt.*;
import java.awt.event.WindowEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.swing.*;

import gold.debug.windowstolinux.app.ai.model.AiPurposeType;
import gold.debug.windowstolinux.app.service.ai.PlatformModelService;
import gold.debug.windowstolinux.app.ui.display.*;
import gold.debug.windowstolinux.app.ui.error.FailureReportStore;
import gold.debug.windowstolinux.app.ui.language.MessageCatalog;
import gold.debug.windowstolinux.app.ui.window.PlatformFrame;
import gold.debug.windowstolinux.appapi.task.model.TaskState;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.appapi.task.run.TaskHandle;
import gold.debug.windowstolinux.appapi.ui.page.UiEnvironment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlatformModelsWindowTest {
    @TempDir
    Path root;

    /** Pauses the original HTTP lease's actual durable release, without replacing transport or task work. / 暂停原HTTP占用的实际持久释放，不替换传输或任务工作。 */
    public static final class PauseModelRelease implements org.h2.api.Trigger {
        /** Arms exactly one original release. / 仅启用一次原释放。 */
        static final AtomicBoolean armed = new AtomicBoolean();

        /** Reports the actual worker reaching release. / 报告实际工作线程进入释放。 */
        static volatile CountDownLatch entered = new CountDownLatch(1);

        /** Lets the actual transaction finish normally. / 允许实际事务正常完成。 */
        static volatile CountDownLatch released = new CountDownLatch(1);

        /** Exact original task observed in the durable row. / 持久行中观察到的精确原任务。 */
        static volatile UUID originalTask;

        /** Creates the H2-controlled fixture. / 创建由H2调用的夹具。 */
        public PauseModelRelease() {
        }

        /** Waits only for the matching connection's release transition. / 仅等待匹配连接的释放迁移。
         * @param connection actual transaction / 实际事务
         * @param oldRow original resource / 原资源
         * @param newRow pending release / 待提交释放
         * @throws SQLException if the fixture cannot finish / 夹具不能完成时 */
        @Override
        public void fire(Connection connection, Object[] oldRow, Object[] newRow) throws SQLException {
            if (!"CONNECTION".equals(oldRow[2]) || !oldRow[3].toString().startsWith("ai-http-")
                || oldRow[7] != null || newRow[7] == null || !armed.compareAndSet(true, false))
                return;
            originalTask = (UUID) oldRow[1];
            entered.countDown();
            try {
                if (!released.await(10, TimeUnit.SECONDS))
                    throw new SQLException("The model release fixture was not released");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new SQLException("The model release fixture was interrupted", interrupted);
            }
        }
    }

    static PlatformFrame window(PlatformRuntime runtime, PlatformModelService service) throws Exception {
        assertFalse(GraphicsEnvironment.isHeadless());
        return onEdt(() -> {
            DesktopThemeService.install(ThemeMode.LIGHT);
            var frame = new PlatformFrame(runtime.security(), MessageCatalog.forLanguageTag("zh-CN"),
                ThemePalette.light(), new UiEnvironment(Locale.SIMPLIFIED_CHINESE, "LIGHT", false),
                FailureReportStore.disabled());
            frame.installModels(service);
            ((JTabbedPane) named(frame, "platform.pages")).setSelectedComponent(named(frame, "platform.models"));
            frame.setVisible(true);
            return frame;
        });
    }

    static void form(PlatformFrame frame, PlatformAiServiceTest.Fixture fixture) throws Exception {
        await(() -> named(frame, "ai.open.DEPLOYMENT").isEnabled());
        SwingUtilities.invokeLater(() -> ((JButton) named(frame, "ai.models.add")).doClick(0));
        await(() -> dialog("platform.model.editor") != null);
        onEdt(() -> {
            var editor = dialog("platform.model.editor");
            ((JTextField) named(editor, "model.name")).setText("本机验证模型");
            ((JTextField) named(editor, "model.endpoint")).setText(fixture.uri("/chat").toString());
            ((JTextField) named(editor, "model.model")).setText("fixture-model");
            ((JTextField) named(editor, "model.limit")).setText("128");
            ((JPasswordField) named(editor, "model.key")).setText("fixture-key");
            ((JButton) named(editor, "model.save")).doClick(0);
            assertEquals(0, ((JPasswordField) named(editor, "model.key")).getPassword().length);
            return null;
        });
        assertEquals(0, fixture.calls.get(), "Saving configuration must send no request");
        verifyEditor(dialog("platform.model.editor"));
    }

    static void verifyEditor(JDialog editor) throws Exception {
        await(() -> named(editor, "model.verify").isEnabled());
        onEdt(() -> {
            ((JButton) named(editor, "model.verify")).doClick(0);
            return null;
        });
    }

    @Test
    void actualModelPageVerifiesSavesAndSelectsLanguagePurposeWithoutDeploymentInputs() throws Exception {
        try (var fixture = new PlatformAiServiceTest.Fixture();
             var runtime = PlatformRuntime.open(root, PlatformStartupTest.PROTECTION)) {
            var service = PlatformModelServiceTest.setup(runtime);
            PlatformModelServiceTest.answer(fixture);
            var frame = window(runtime, service);
            try {
                form(frame, fixture);
                await(() -> dialog("platform.model.editor") == null
                    && ((JList<?>) named(frame, "ai.inventory")).getModel().getSize() == 1);
                assertEquals(128,
                    service.outputLimit(service.listAiConfigurations().getFirst().profile().id()).orElseThrow());
                assertTrue(service.listAiPurpose(AiPurposeType.DEPLOYMENT).isEmpty());
                await(() -> named(frame, "ai.open.DEPLOYMENT").isEnabled());
                onEdt(() -> {
                    ((JList<?>) named(frame, "ai.inventory")).setSelectedIndex(0);
                    ((JButton) named(frame, "ai.open.DEPLOYMENT")).doClick(0);
                    descendants(frame).filter(JButton.class::isInstance).map(JButton.class::cast)
                        .filter(button -> button.getText().equals("→")).findFirst().orElseThrow().doClick(0);
                    assertEquals(1, ((JList<?>) named(frame, "ai.purpose")).getModel().getSize());
                    capture(frame, "platform-models.png");
                    ((JButton) named(frame, "ai.purpose.save")).doClick(0);
                    return null;
                });
                await(() -> named(frame, "ai.open.DEPLOYMENT").isEnabled()
                    && descendants(frame).noneMatch(component -> "ai.purpose".equals(component.getName())));
                assertEquals(1, service.listAiPurpose(AiPurposeType.DEPLOYMENT).size());
                assertEquals(1, fixture.calls.get());
                onEdt(() -> {
                    ((JButton) named(frame, "security.lock")).doClick(0);
                    assertTrue(frame.getContentPane().isVisible());
                    assertTrue(frame.getGlassPane().isVisible());
                    return null;
                });
                assertThrows(RuntimeException.class, service::listAiConfigurations);
            } finally {
                onEdt(() -> {
                    frame.dispose();
                    return null;
                });
            }
        }
    }

    @Test
    void closingRunningEditorCancelsAndExposesUnknownToExplicitPageRecovery() throws Exception {
        try (var fixture = new PlatformAiServiceTest.Fixture();
             var runtime = PlatformRuntime.open(root, PlatformStartupTest.PROTECTION)) {
            var service = PlatformModelServiceTest.setup(runtime);
            var release = new CountDownLatch(1);
            fixture.handler.set(exchange -> {
                try {
                    release.await(6, TimeUnit.SECONDS);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
                PlatformModelServiceTest.reply(exchange, "late");
            });
            var frame = window(runtime, service);
            try {
                form(frame, fixture);
                assertNotNull(fixture.requests.poll(5, TimeUnit.SECONDS));
                await(() -> editorTask(dialog("platform.model.editor")) != null);
                var original = onEdt(() -> editorTask(dialog("platform.model.editor")));
                assertEquals(TaskState.RUNNING, original.snapshot().state());
                assertEquals(1, count(runtime.persistence().database(),
                    "SELECT COUNT(*) FROM platform_task_resource WHERE task_id='" + original.id()
                        + "' AND resource_kind='CONNECTION' AND resource_id LIKE 'ai-http-%' AND released_at IS NULL"));
                onEdt(() -> {
                    ((JButton) named(dialog("platform.model.editor"), "model.cancel")).doClick(0);
                    return null;
                });
                await(() -> dialog("platform.model.editor") == null && named(frame, "models.reconcile").isEnabled());
                var retained = service.checks().getFirst();
                assertEquals(original.id(), retained.id());
                assertEquals(TaskState.UNKNOWN, retained.state());
                assertTrue(retained.outcome().isEmpty());
                assertFalse(original.completion().toCompletableFuture().isDone(),
                    "An UNKNOWN editor may close while its original central task remains retained");
                assertEquals(1, count(runtime.persistence().database(),
                    "SELECT COUNT(*) FROM platform_task WHERE task_id='" + original.id() + "' AND state='UNKNOWN'"));
                onEdt(() -> {
                    ((JButton) named(frame, "models.reconcile")).doClick(0);
                    return null;
                });
                await(() -> ((JComboBox<?>) named(frame, "models.checks")).getItemCount() == 0);
                assertEquals(TaskState.ENDED, original.snapshot().state());
                assertEquals(TaskResult.FAILED, original.snapshot().outcome().orElseThrow());
                assertEquals(0, count(runtime.persistence().database(),
                    "SELECT COUNT(*) FROM platform_task_resource WHERE task_id='" + original.id() + "'"));
                assertEquals(1, count(runtime.persistence().database(),
                    "SELECT COUNT(*) FROM runtime_log WHERE task_id='" + original.id()
                        + "' AND event_code='PLATFORM_TASK_FAILED'"));
                assertEquals(1, service.listAiConfigurations().size());
                assertEquals(1, fixture.calls.get());
            } finally {
                release.countDown();
                onEdt(() -> {
                    if (dialog("platform.model.editor") != null)
                        dialog("platform.model.editor").dispose();
                    frame.dispose();
                    return null;
                });
            }
        }
    }

    /** Preserves the legacy dialog failure/cancellation case through actual HTTP, H2 and the current editor. / 经实际HTTP、H2与当前编辑器保留旧对话框失败及取消案例。 */
    @Test
    void failedModelSaveRetainsInputsAndCancelWaitsForWorkerCleanup() throws Exception {
        assertFalse(GraphicsEnvironment.isHeadless());
        try (var fixture = new PlatformAiServiceTest.Fixture();
             var runtime = PlatformRuntime.open(root, PlatformStartupTest.PROTECTION)) {
            var service = PlatformModelServiceTest.setup(runtime);
            fixture.handler.set(exchange -> PlatformAiServiceTest.Fixture.reply(exchange, 400,
                "{\"error\":{\"message\":\"known challenge rejection\"}}"));
            var frame = window(runtime, service);
            boolean triggerInstalled = false;
            try {
                await(() -> named(frame, "ai.open.DEPLOYMENT").isEnabled());
                SwingUtilities.invokeLater(() -> ((JButton) named(frame, "ai.models.add")).doClick(0));
                await(() -> dialog("platform.model.editor") != null);
                var editor = dialog("platform.model.editor");
                onEdt(() -> {
                    ((JTextField) named(editor, "model.name")).setText("existing");
                    ((JTextField) named(editor, "model.endpoint")).setText(fixture.uri("/chat").toString());
                    ((JTextField) named(editor, "model.model")).setText("fixture-model");
                    ((JTextField) named(editor, "model.limit")).setText("128");
                    ((JPasswordField) named(editor, "model.key")).setText("fixture-key");
                    ((JButton) named(editor, "model.save")).doClick(0);
                    assertEquals(0, ((JPasswordField) named(editor, "model.key")).getPassword().length);
                    return null;
                });
                verifyEditor(editor);
                assertNotNull(fixture.requests.poll(5, TimeUnit.SECONDS));
                await(() -> named(editor, "model.save").isEnabled());
                assertEquals(1, count(runtime.persistence().database(),
                    "SELECT COUNT(*) FROM runtime_log WHERE event_code='PLATFORM_TASK_FAILED' AND details LIKE '%platform.ai.probe.v1%'"));
                assertEquals(1, service.listAiConfigurations().size());
                onEdt(() -> {
                    assertTrue(editor.isShowing());
                    assertEquals("existing", ((JTextField) named(editor, "model.name")).getText());
                    assertEquals(fixture.uri("/chat").toString(),
                        ((JTextField) named(editor, "model.endpoint")).getText());
                    assertEquals("fixture-model", ((JTextField) named(editor, "model.model")).getText());
                    assertEquals("128", ((JTextField) named(editor, "model.limit")).getText());
                    assertEquals(0, ((JPasswordField) named(editor, "model.key")).getPassword().length);
                    // End only this view's polling; the editor and accepted central task stay live.
                    // 仅结束该视图轮询，编辑器与已接纳中央任务仍实际存活。
                    ((gold.debug.windowstolinux.app.ui.ai.PlatformModelsPane) named(frame, "platform.models")).close();
                    return null;
                });
                PauseModelRelease.entered = new CountDownLatch(1);
                PauseModelRelease.released = new CountDownLatch(1);
                PauseModelRelease.originalTask = null;
                PauseModelRelease.armed.set(true);
                sql(runtime.persistence().database(), "CREATE TRIGGER pause_model_release BEFORE UPDATE ON platform_task_resource FOR EACH ROW CALL '"
                    + PauseModelRelease.class.getName() + "'");
                triggerInstalled = true;
                onEdt(() -> {
                    ((JPasswordField) named(editor, "model.key")).setText("fixture-key");
                    ((JButton) named(editor, "model.save")).doClick(0);
                    assertEquals(0, ((JPasswordField) named(editor, "model.key")).getPassword().length);
                    return null;
                });
                verifyEditor(editor);
                assertNotNull(fixture.requests.poll(5, TimeUnit.SECONDS));
                assertTrue(PauseModelRelease.entered.await(5, TimeUnit.SECONDS));
                await(() -> editorTask(editor) != null);
                var original = onEdt(() -> editorTask(editor));
                assertEquals(original.id(), PauseModelRelease.originalTask);
                assertEquals(TaskState.RUNNING, original.snapshot().state());
                onEdt(() -> {
                    editor.dispatchEvent(new WindowEvent(editor, WindowEvent.WINDOW_CLOSING));
                    return null;
                });
                onEdt(() -> {
                    assertTrue(editor.isDisplayable(), "Original worker has not finished durable connection cleanup");
                    assertFalse(named(editor, "model.save").isEnabled());
                    assertEquals("existing", ((JTextField) named(editor, "model.name")).getText());
                    assertFalse(original.completion().toCompletableFuture().isDone());
                    return null;
                });
                PauseModelRelease.released.countDown();
                var failed = original.completion().toCompletableFuture().get(8, TimeUnit.SECONDS);
                assertEquals(TaskResult.FAILED, failed.outcome());
                assertEquals("AI_HTTP_REJECTED", failed.problem().orElseThrow().eventCode());
                await(() -> !editor.isDisplayable());
                assertEquals(TaskState.ENDED, original.snapshot().state());
                assertEquals(TaskResult.FAILED, original.snapshot().outcome().orElseThrow());
                assertTrue(service.checks().isEmpty());
                assertEquals(0, count(runtime.persistence().database(),
                    "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL"));
                assertEquals(1, count(runtime.persistence().database(),
                    "SELECT COUNT(*) FROM runtime_log WHERE event_code='PLATFORM_TASK_FAILED' AND task_id='"
                        + original.id() + "'"));
                await(() -> !gold.debug.windowstolinux.app.ui.task.DesktopTaskRunner.hasActiveTasks());
                assertEquals(1, service.listAiConfigurations().size(), "Failed/cancelled probes preserve the independently saved configuration");
                assertEquals(2, fixture.calls.get(), "Closing must not resubmit either failed probe");
            } finally {
                PauseModelRelease.released.countDown();
                PauseModelRelease.armed.set(false);
                if (triggerInstalled)
                    sql(runtime.persistence().database(), "DROP TRIGGER pause_model_release");
                onEdt(() -> {
                    if (dialog("platform.model.editor") != null)
                        dialog("platform.model.editor").dispose();
                    frame.dispose();
                    return null;
                });
            }
        }
    }

    @Test
    void pureVoiceEditorSeparatesSaveFromActualVerificationAndRejectsUnsavedDraft() throws Exception {
        try (var endpoint = new VoiceConfigTest.Endpoint();
             var runtime = PlatformRuntime.open(root, PlatformStartupTest.PROTECTION)) {
            var service = PlatformModelServiceTest.setup(runtime);
            var sample = root.resolve("voice-check.wav");
            Files.write(sample, VoiceServiceTest.audio().audio());
            var frame = window(runtime, service);
            try {
                await(() -> named(frame, "ai.open.VOICE").isEnabled());
                SwingUtilities.invokeLater(() -> ((JButton) named(frame, "ai.models.add")).doClick(0));
                await(() -> dialog("platform.model.editor") != null);
                var editor = dialog("platform.model.editor");
                onEdt(() -> {
                    ((JTextField) named(editor, "model.name")).setText("纯语音模型");
                    ((JTextField) named(editor, "model.fileEndpoint")).setText(endpoint.uri().toString());
                    ((JTextField) named(editor, "model.model")).setText("speech-model");
                    assertTrue(((JTextField) named(editor, "model.limit")).getText().isBlank());
                    ((JPasswordField) named(editor, "model.key")).setText("voice-key");
                    ((JTextField) named(editor, "model.audio")).setText(sample.toString());
                    ((JTextField) named(editor, "model.expected")).setText("local speech");
                    ((JComboBox<?>) named(editor, "model.capability")).setSelectedItem(
                        gold.debug.windowstolinux.app.ai.model.AiCapabilityType.VOICE_FILE);
                    assertFalse(named(editor, "model.verify").isEnabled());
                    ((JButton) named(editor, "model.save")).doClick(0);
                    return null;
                });
                await(() -> named(editor, "model.verify").isEnabled());
                assertEquals(0, endpoint.calls.get());
                var row = service.listAiConfigurations().getFirst();
                assertNull(row.profile().chatCompletionsEndpoint());
                assertFalse(row.verified(AiPurposeType.VOICE));
                onEdt(() -> {
                    var protocol = (JComboBox<?>) named(editor, "model.capability");
                    protocol.setSelectedItem(gold.debug.windowstolinux.app.ai.model.AiCapabilityType.TEXT);
                    assertFalse(named(editor, "model.verify").isEnabled(), "Pure voice configuration has no text protocol");
                    protocol.setSelectedItem(gold.debug.windowstolinux.app.ai.model.AiCapabilityType.VOICE_REALTIME);
                    assertFalse(named(editor, "model.verify").isEnabled(), "Unconfigured realtime protocol cannot be probed");
                    protocol.setSelectedItem(gold.debug.windowstolinux.app.ai.model.AiCapabilityType.VOICE_FILE);
                    assertTrue(named(editor, "model.verify").isEnabled());
                    ((JTextField) named(editor, "model.fileEndpoint")).setText(endpoint.uri() + "/draft");
                    assertFalse(named(editor, "model.verify").isEnabled());
                    ((JTextField) named(editor, "model.fileEndpoint")).setText(endpoint.uri().toString());
                    assertTrue(named(editor, "model.verify").isEnabled());
                    capture(editor, "voice-model-editor.png");
                    return null;
                });
                verifyEditor(editor);
                await(() -> !editor.isDisplayable());
                assertEquals(1, endpoint.calls.get());
                assertTrue(service.listAiConfigurations().getFirst().verified(AiPurposeType.VOICE));
                assertTrue(service.listAiPurpose(AiPurposeType.VOICE).isEmpty());
                await(() -> named(frame, "ai.open.VOICE").isEnabled());
                onEdt(() -> {
                    ((JList<?>) named(frame, "ai.inventory")).setSelectedIndex(0);
                    ((JButton) named(frame, "ai.open.VOICE")).doClick(0);
                    descendants(frame).filter(JButton.class::isInstance).map(JButton.class::cast)
                        .filter(button -> button.getText().equals("→")).findFirst().orElseThrow().doClick(0);
                    assertEquals(1, ((JList<?>) named(frame, "ai.purpose")).getModel().getSize());
                    ((JButton) named(frame, "ai.purpose.save")).doClick(0);
                    return null;
                });
                await(() -> named(frame, "ai.open.VOICE").isEnabled()
                    && descendants(frame).noneMatch(component -> "ai.purpose".equals(component.getName())));
                assertEquals(List.of(new gold.debug.windowstolinux.app.ai.model.AiPurposeAssignment(row.profile().id(), true)),
                    service.listAiPurpose(AiPurposeType.VOICE));
            } finally {
                onEdt(() -> {
                    if (dialog("platform.model.editor") != null) dialog("platform.model.editor").dispose();
                    frame.dispose();
                    return null;
                });
            }
        }
    }

    /** Reads only the editor's actual submitted handle to observe callback completion. / 仅读取编辑器实际提交的句柄以观察回调完成。
     * @param editor current real dialog / 当前实际对话框
     * @return actual handle or not yet admitted / 实际句柄，尚未准入时为空
     * @throws AssertionError if the observed UI field changes / 被观察UI字段变化时 */
    private static TaskHandle<?> editorTask(JDialog editor) {
        if (editor == null)
            return null;
        try {
            var field = editor.getClass().getDeclaredField("task");
            field.setAccessible(true);
            return (TaskHandle<?>) field.get(editor);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }
}
