package gold.debug.windowstolinux.app.main.start;

import static gold.debug.windowstolinux.app.main.start.PlatformTaskAdmissionIntegrationTest.*;
import static gold.debug.windowstolinux.app.main.start.PlatformWindowTest.*;
import static org.junit.jupiter.api.Assertions.*;

import java.awt.*;
import java.nio.file.Path;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.swing.*;

import gold.debug.windowstolinux.app.service.task.PlatformTaskManagement;
import gold.debug.windowstolinux.app.ui.display.*;
import gold.debug.windowstolinux.app.ui.error.FailureReportStore;
import gold.debug.windowstolinux.app.ui.language.MessageCatalog;
import gold.debug.windowstolinux.app.ui.mod.UiExtensionAccess;
import gold.debug.windowstolinux.app.ui.task.TaskInteractionPresenter;
import gold.debug.windowstolinux.app.ui.window.PlatformFrame;
import gold.debug.windowstolinux.appapi.task.action.ActionIntent;
import gold.debug.windowstolinux.appapi.task.control.*;
import gold.debug.windowstolinux.appapi.task.model.TaskRequest;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.appapi.ui.extension.UiExtensionPoint;
import gold.debug.windowstolinux.appapi.ui.intervention.*;
import gold.debug.windowstolinux.appapi.ui.page.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Exercises the actual host window and footer using local original tasks. / 用本地原任务操作真实宿主窗口和底栏。 */
class PlatformInterventionWindowTest {
    @TempDir
    Path root;

    @Test
    void authorityAndExistingDatabaseImpactRemainVisibleWhenTechnicalBindingsAreCollapsed() throws Exception {
        onEdt(() -> {
            var owner = new JFrame();
            try {
                var presenter = new TaskInteractionPresenter(owner, MessageCatalog.forLanguageTag("zh-CN"));
                var summary = TaskInteractionPresenter.class.getDeclaredMethod("simpleReviewSummary",
                    ActionIntent.class, Map.class, String.class);
                summary.setAccessible(true);
                var full = new ActionIntent("deployment.workflow.authority", List.of("internal-server"), "internal-target",
                    "internal-revision", "approval=FULL_CONTROL\nrememberAuthorization=true", "RAW_AUTHORITY_DETAILS");
                var ordinary = new ActionIntent("deployment.workflow.authority", List.of("internal-server"), "internal-target",
                    "internal-revision", "approval=MANUAL_REVIEW\nrememberAuthorization=false", "RAW_AUTHORITY_DETAILS");
                var downtime = new ActionIntent("db.downtime", List.of("internal-server"), "internal-database",
                    "internal-revision", "RAW_DATABASE_ACTION", "RAW_DATABASE_INSTANCE").withSemantics(
                    new gold.debug.windowstolinux.appapi.task.action.ActionSemantics("db.downtime", Set.of(
                        new gold.debug.windowstolinux.appapi.task.action.ActionTarget("internal-server", "deployment-database", "internal-database"))));
                var schema = new ActionIntent("db.existing-schema", List.of("internal-server"), "internal-database",
                    "internal-revision", "RAW_DATABASE_ACTION", "RAW_DATABASE_INSTANCE").withSemantics(
                    new gold.debug.windowstolinux.appapi.task.action.ActionSemantics("db.existing-schema", Set.of(
                        new gold.debug.windowstolinux.appapi.task.action.ActionTarget("internal-server", "deployment-database", "internal-database"))));
                for (var example : Map.of(full, "未决选择也将交由程序决定", ordinary, "仍会请求人工介入",
                    downtime, "可能暂时无法访问", schema, "修改现有数据库的结构或内容").entrySet()) {
                    var panel = (JPanel) summary.invoke(presenter, example.getKey(), Map.of("internal-server", "演示服务器"), "");
                    String text = descendants(panel).map(value -> value instanceof JTextArea area ? area.getText()
                        : value instanceof JLabel label ? label.getText() : "").collect(java.util.stream.Collectors.joining("\n"));
                    assertTrue(text.contains(example.getValue()), text);
                    assertTrue(text.contains("演示服务器"), text);
                    assertFalse(text.contains("internal-"), text);
                    assertFalse(text.contains("RAW_"), text);
                    if (example.getKey() == full) assertTrue(text.contains("不会授权其他任务"));
                    if (example.getKey() == ordinary) assertFalse(text.contains("未决选择也将交由程序决定"));
                }
                assertEquals("approval=FULL_CONTROL\nrememberAuthorization=true", full.exactAction());
                assertEquals("RAW_DATABASE_ACTION", downtime.exactAction());
            } finally {
                owner.dispose();
            }
            return null;
        });
    }

    @ParameterizedTest
    @CsvSource({
        "Database administrator credential,intervention.databaseAdministratorPassword,true",
        "Existing database credential,intervention.databasePassword,false"
    })
    void hostedDatabasePasswordShowsPurposeAndDefersTheOriginalSecretQuestion(String title, String purposeKey,
                                                                              boolean nativeClose) throws Exception {
        try (var runtime = PlatformRuntime.open(root, PlatformStartupTest.PROTECTION)) {
            initialize(runtime, false);
            var queue = runtime.tasks().interventions();
            var management = new PlatformTaskManagement(runtime.tasks(), runtime.security(),
                id -> CompletableFuture.failedFuture(new AssertionError("No recovery in this fixture")), () -> {
            });
            var catalog = MessageCatalog.forLanguageTag("zh-CN");
            var frame = onEdt(() -> {
                DesktopThemeService.apply(ThemeMode.LIGHT, 100);
                DesktopMotion.suspended(true);
                var window = new PlatformFrame(runtime.security(), catalog, ThemePalette.light(),
                    new UiEnvironment(Locale.SIMPLIFIED_CHINESE, "LIGHT", false), FailureReportStore.disabled());
                window.installLogs(runtime.logs());
                window.installTasks(management);
                window.installInterventions(queue);
                DesktopMotion.preference(false);
                window.setSize(880, 630);
                window.setVisible(true);
                return window;
            });
            var selected = new AtomicReference<UUID>();
            var pageRoot = new AtomicReference<JPanel>();
            var capability = new AtomicReference<UiInterventionService>();
            var access = new UiExtensionAccess() {
                public String version() {
                    return VERSION;
                }

                public boolean active() {
                    return true;
                }

                public void checkContextType(Class<?> type, boolean owned) {
                    assertEquals(UUID.class, type);
                }

                public void checkTarget(UiExtensionPoint<?> target, Optional<String> version) {
                }

                public Object copy(Object value) {
                    return value;
                }
            };
            var scope = frame.pages().begin(OWNER, VERSION, false, access);
            var received = new AtomicReference<char[]>();
            try {
                scope.register(new PageSpec("workspace", "fixture.host-secret", "宿主密码核验演示", false), () -> new ModPage() {
                    private boolean locked;

                    public JComponent create(UiEnvironment environment) {
                        var panel = new JPanel(new BorderLayout());
                        panel.add(new JLabel("原任务 · 等待补充数据库密码"), BorderLayout.NORTH);
                        pageRoot.set(panel);
                        panel.addHierarchyListener(event -> visible());
                        return panel;
                    }

                    private void visible() {
                        if (selected.get() == null) return;
                        try {
                            capability.get().visible(selected.get(), !locked && pageRoot.get().isShowing());
                        } catch (Exception ended) {
                        }
                    }

                    public void navigate(UiNavigationRequest<?> request) {
                        selected.set((UUID) request.context().orElseThrow());
                        visible();
                    }

                    public void environmentChanged(UiEnvironment environment) {
                        locked = environment.locked();
                        visible();
                    }

                    public void close() {
                        locked = true;
                        visible();
                    }
                });
                scope.commit();
                capability.set(scope.interventions());
                var host = new TaskInteractionPresenter(frame, catalog, queue);
                var binding = runtime.tasks().bind(OWNER, VERSION, false);
                String prompt = "fixture-server-id-42 / fixture-instance-id-17";
                String fakePassword = "仅供测试-宿主口令🔒42";
                try (var controller = binding.registerController("host-secret-fixture", new TaskController() {
                    public TaskControlSnapshot snapshot(UUID id) {
                        return new TaskControlSnapshot(TaskControlSnapshot.State.WAITING_USER, false, false, true, "");
                    }

                    public void request(UUID id, TaskCommand command) throws gold.debug.windowstolinux.appapi.task.result.ApiException {
                        assertEquals(TaskCommand.INTERVENE, command);
                        scope.open("workspace", new UiNavigationRequest<>("intervene-task", UUID.class, Optional.of(id)));
                    }
                })) {
                    var task = binding.<Void>submit(new TaskRequest("host-secret-fixture", "本地数据库密码问答", List.of(), true), context -> {
                        scope.open("workspace", new UiNavigationRequest<>("intervene-task", UUID.class, Optional.of(context.id())));
                        char[] value = host.text(title, prompt, true, binding.inspect(context), context.cancelToken());
                        received.set(value);
                        return value == null
                            ? new gold.debug.windowstolinux.appapi.task.result.OperationResult<>(TaskResult.CANCELLED, Optional.empty(), Optional.empty())
                            : success(null);
                    });
                    try {
                        await(() -> dialog(frame) != null);
                        var originalWindow = onEdt(() -> dialog(frame));
                        var originalRequest = queue.pending().getFirst().requestId();
                        var oldField = new AtomicReference<JPasswordField>();
                        onEdt(() -> {
                            String shown = visiblePromptText(originalWindow);
                            assertTrue(shown.contains(catalog.text(purposeKey)), shown);
                            assertFalse(shown.contains("fixture-server-id-42"), shown);
                            assertFalse(shown.contains("fixture-instance-id-17"), shown);
                            var details = (JToggleButton) named(originalWindow, "task.intervention.details");
                            assertNotNull(details);
                            details.doClick(0);
                            assertTrue(visiblePromptText(originalWindow).contains(prompt));
                            details.doClick(0);
                            assertFalse(visiblePromptText(originalWindow).contains(prompt));
                            var field = (JPasswordField) named(originalWindow, "task.input.value");
                            assertNotNull(field);
                            oldField.set(field);
                            field.setText("fictional-input-before-deferral");
                            if (nativeClose) originalWindow.dispatchEvent(new java.awt.event.WindowEvent(originalWindow,
                                java.awt.event.WindowEvent.WINDOW_CLOSING));
                            else ((JButton) named(originalWindow, "task.intervention.later")).doClick(0);
                            assertPasswordEmpty(field);
                            assertFalse(originalWindow.isShowing());
                            return null;
                        });
                        assertNull(received.get());
                        assertFalse(task.completion().toCompletableFuture().isDone());
                        assertEquals(1, queue.count(task.id()));
                        assertEquals(originalRequest, queue.pending().getFirst().requestId());
                        onEdt(() -> {
                            ((JButton) named(frame, "platform.pendingInterventions")).doClick(0);
                            return null;
                        });
                        await(() -> dialog(frame) != null);
                        var reopened = onEdt(() -> dialog(frame));
                        var newField = new AtomicReference<JPasswordField>();
                        onEdt(() -> {
                            var field = (JPasswordField) named(reopened, "task.input.value");
                            assertNotNull(field);
                            assertNotSame(oldField.get(), field);
                            assertPasswordEmpty(field);
                            assertTrue(visiblePromptText(reopened).contains(catalog.text(purposeKey)));
                            assertFalse(visiblePromptText(reopened).contains(prompt));
                            newField.set(field);
                            field.setText(fakePassword);
                            ((JButton) named(reopened, "task.intervention.accept")).doClick(0);
                            assertPasswordEmpty(field);
                            return null;
                        });
                        assertEquals(TaskResult.SUCCEEDED, task.completion().toCompletableFuture().get(10, TimeUnit.SECONDS).outcome());
                        char[] expected = fakePassword.toCharArray();
                        try {
                            assertArrayEquals(expected, received.get());
                        } finally {
                            Arrays.fill(expected, '\0');
                        }
                        assertEquals(0, queue.count(task.id()));
                        await(() -> dialog(frame) == null);
                        onEdt(() -> {
                            assertPasswordEmpty(oldField.get());
                            assertPasswordEmpty(newField.get());
                            return null;
                        });
                    } finally {
                        if (!task.completion().toCompletableFuture().isDone()) task.requestCancel();
                        task.completion().toCompletableFuture().get(10, TimeUnit.SECONDS);
                    }
                }
            } finally {
                char[] value = received.get();
                if (value != null) Arrays.fill(value, '\0');
                scope.close();
                onEdt(() -> {
                    frame.dispose();
                    DesktopMotion.suspended(false);
                    DesktopMotion.preference(true);
                    DesktopThemeService.apply(ThemeMode.LIGHT, 100);
                    return null;
                });
            }
        }
    }

    @Test
    void footerCountsOriginalTasksRatherThanQuestionsAndMultipleTasksOpenTheInventory() throws Exception {
        try (var runtime = PlatformRuntime.open(root, PlatformStartupTest.PROTECTION)) {
            initialize(runtime, false);
            var queue = runtime.tasks().interventions();
            var capability = queue.scoped(OWNER, VERSION, () -> true);
            var management = new PlatformTaskManagement(runtime.tasks(), runtime.security(),
                id -> CompletableFuture.failedFuture(new AssertionError("No recovery in this fixture")), () -> {
            });
            var frame = onEdt(() -> {
                DesktopThemeService.apply(ThemeMode.LIGHT, 150);
                DesktopMotion.suspended(true);
                var window = new PlatformFrame(runtime.security(), MessageCatalog.forLanguageTag("zh-CN"),
                    ThemePalette.light(), new UiEnvironment(Locale.SIMPLIFIED_CHINESE, "LIGHT", false), FailureReportStore.disabled());
                window.installLogs(runtime.logs());
                window.installTasks(management);
                window.installInterventions(queue);
                DesktopMotion.preference(false);
                window.setMinimumSize(new Dimension(800, 500));
                window.setSize(880, 630);
                window.setVisible(true);
                return window;
            });
            var release = new CountDownLatch(1);
            var accepted = new AtomicInteger();
            var tasks = new ArrayList<gold.debug.windowstolinux.appapi.task.run.TaskHandle<Void>>();
            try {
                var binding = runtime.tasks().bind(OWNER, VERSION, false);
                for (int questions : List.of(2, 1)) {
                    tasks.add(binding.submit(new TaskRequest("footer-fixture", "本地部署演示", List.of(), true), task -> {
                        for (int index = 0; index < questions; index++)
                            capability.request(task, new UiInterventionSpec("人工介入", "需要确认应用设置", "继续"),
                                () -> form(accepted));
                        assertTrue(release.await(10, TimeUnit.SECONDS));
                        return success(null);
                    }));
                }
                await(() -> queue.pending().size() == 3);
                await(() -> ((JButton) named(frame, "platform.pendingInterventions")).getText().equals("有 2 个任务需要人工介入"));
                assertEquals(2, capability.snapshot(tasks.getFirst().id()).pendingCount());
                assertEquals(0, accepted.get());
                onEdt(() -> {
                    ((JButton) named(frame, "platform.pendingInterventions")).doClick(0);
                    assertSame(named(frame, "platform.tasks"), ((JTabbedPane) named(frame, "platform.pages")).getSelectedComponent());
                    assertEquals(30, named(frame, "platform.statusBar").getHeight());
                    capture(frame, "intervention-multiple-tasks-LIGHT-150.png");
                    return null;
                });
                tasks.getLast().requestCancel();
                await(() -> queue.pending().size() == 2);
                await(() -> ((JButton) named(frame, "platform.pendingInterventions")).getText().equals("有 1 个任务需要人工介入"));
                assertEquals(0, accepted.get(), "Navigation and cancelling another task must not answer these questions");
            } finally {
                release.countDown();
                for (var task : tasks) {
                    if (!task.completion().toCompletableFuture().isDone()) task.requestCancel();
                    task.completion().toCompletableFuture().get(10, TimeUnit.SECONDS);
                }
                onEdt(() -> {
                    frame.dispose();
                    DesktopMotion.suspended(false);
                    DesktopMotion.preference(true);
                    DesktopThemeService.apply(ThemeMode.LIGHT, 100);
                    return null;
                });
            }
        }
    }

    @ParameterizedTest
    @CsvSource({"LIGHT,100", "LIGHT,150", "DARK,100", "DARK,150"})
    void foregroundAndBackgroundQuestionsReuseOneWindowWithoutGrantingNavigationAuthority(String themeName, int scale) throws Exception {
        try (var runtime = PlatformRuntime.open(root, PlatformStartupTest.PROTECTION)) {
            initialize(runtime, false);
            var queue = runtime.tasks().interventions();
            var management = new PlatformTaskManagement(runtime.tasks(), runtime.security(),
                id -> CompletableFuture.failedFuture(new AssertionError("No reconciliation in this fixture")), () -> {
            });
            var catalog = MessageCatalog.forLanguageTag("zh-CN");
            var frame = onEdt(() -> {
                var theme = ThemeMode.valueOf(themeName);
                DesktopThemeService.apply(theme, scale);
                DesktopMotion.suspended(true);
                var window = new PlatformFrame(runtime.security(), catalog,
                    theme == ThemeMode.DARK ? ThemePalette.dark() : ThemePalette.light(),
                    new UiEnvironment(Locale.SIMPLIFIED_CHINESE, themeName, false), FailureReportStore.disabled());
                window.installLogs(runtime.logs());
                window.installTasks(management);
                window.installInterventions(queue);
                DesktopMotion.preference(false);
                window.setMinimumSize(new Dimension(800, 500));
                window.setSize(880, 630);
                window.setVisible(true);
                return window;
            });
            var pageTask = new AtomicReference<UUID>();
            var pageRoot = new AtomicReference<JPanel>();
            var uiRef = new AtomicReference<UiInterventionService>();
            var access = new UiExtensionAccess() {
                public String version() {
                    return VERSION;
                }

                public boolean active() {
                    return true;
                }

                public void checkContextType(Class<?> type, boolean owned) {
                    assertEquals(UUID.class, type);
                }

                public void checkTarget(UiExtensionPoint<?> target, Optional<String> version) {
                }

                public Object copy(Object value) {
                    return value;
                }
            };
            var scope = frame.pages().begin(OWNER, VERSION, false, access);
            var holdHost = new CountDownLatch(1);
            var accepted = new AtomicInteger();
            var firstRef = new AtomicReference<UiInterventionSession<Boolean>>();
            var finalRef = new AtomicReference<UiInterventionSession<Boolean>>();
            try {
                scope.register(new PageSpec("workspace", "fixture.deployment", "部署演示", false), () -> new ModPage() {
                    private boolean locked;

                    public JComponent create(UiEnvironment environment) {
                        var panel = new JPanel(new BorderLayout());
                        panel.add(new JLabel("部署演示 · 正在检查项目"), BorderLayout.NORTH);
                        pageRoot.set(panel);
                        panel.addHierarchyListener(event -> visible());
                        return panel;
                    }

                    private void visible() {
                        if (pageTask.get() == null) return;
                        try {
                            uiRef.get().visible(pageTask.get(), !locked && pageRoot.get().isShowing());
                        } catch (Exception ended) {
                        }
                    }

                    public void environmentChanged(UiEnvironment environment) {
                        locked = environment.locked();
                        visible();
                    }

                    public void navigate(UiNavigationRequest<?> request) {
                        pageTask.set((UUID) request.context().orElseThrow());
                        visible();
                    }

                    public void close() {
                        locked = true;
                        visible();
                    }
                });
                scope.commit();
                var ui = scope.interventions();
                uiRef.set(ui);
                var host = new TaskInteractionPresenter(frame, catalog, queue);
                host.serverNames(() -> Map.of("server", "演示服务器"));
                var binding = runtime.tasks().bind(OWNER, VERSION, false);
                try (var controller = binding.registerController("window-fixture", new TaskController() {
                    public TaskControlSnapshot snapshot(UUID task) {
                        return new TaskControlSnapshot(TaskControlSnapshot.State.WAITING_USER, false, false, true, "");
                    }

                    public void request(UUID task, TaskCommand command) throws gold.debug.windowstolinux.appapi.task.result.ApiException {
                        assertEquals(TaskCommand.INTERVENE, command);
                        scope.open("workspace", new UiNavigationRequest<>("intervene-task", UUID.class, Optional.of(task)));
                    }
                })) {
                    var task = binding.submit(new TaskRequest("window-fixture", "部署演示", List.of(), true), context -> {
                        var first = ui.request(context, new UiInterventionSpec("人工介入", "需要补充应用名称", "继续", true),
                            () -> form(accepted));
                        firstRef.set(first);
                        assertTrue(first.completion().toCompletableFuture().get(10, TimeUnit.SECONDS));
                        assertTrue(holdHost.await(10, TimeUnit.SECONDS));
                        assertTrue(host.approve(new ActionIntent("example.action", List.of("server"), "internal-target",
                                "revision-1", "echo exact-review-only", "technical effect"),
                            binding.inspect(context), context.cancelToken(), "这会短暂停止当前应用，请确认已经安排好使用时间。"));
                        var last = ui.request(context, new UiInterventionSpec("确认部署", "演示服务器 · 确认后继续", "确认部署"),
                            () -> form(accepted));
                        finalRef.set(last);
                        assertTrue(last.completion().toCompletableFuture().get(10, TimeUnit.SECONDS));
                        return success(null);
                    });
                    try {
                        await(() -> firstRef.get() != null && queue.count(task.id()) == 1);
                        assertFalse(onEdt(() -> Arrays.stream(frame.getOwnedWindows()).anyMatch(Window::isShowing)));
                        await(() -> ((JButton) named(frame, "platform.pendingInterventions")).isVisible());
                        onEdt(() -> {
                            assertEquals("有 1 个任务需要人工介入", ((JButton) named(frame, "platform.pendingInterventions")).getText());
                            assertEquals(30, named(frame, "platform.statusBar").getHeight());
                            capture(frame, "intervention-footer-" + themeName + "-" + scale + ".png");
                            ((JButton) named(frame, "platform.pendingInterventions")).doClick(0);
                            return null;
                        });
                        await(() -> dialog(frame) != null);
                        var firstWindow = onEdt(() -> dialog(frame));
                        onEdt(() -> {
                            ((JButton) named(firstWindow, "task.intervention.later")).doClick(0);
                            return null;
                        });
                        assertFalse(firstRef.get().completion().toCompletableFuture().isDone());
                        assertEquals(0, accepted.get());
                        onEdt(() -> {
                            ((JButton) named(frame, "platform.pendingInterventions")).doClick(0);
                            return null;
                        });
                        await(() -> dialog(frame) != null);
                        var shared = onEdt(() -> dialog(frame));
                        onEdt(() -> {
                            ((JButton) named(shared, "task.intervention.accept")).doClick(0);
                            return null;
                        });
                        assertEquals(1, accepted.get());
                        await(() -> !((JButton) named(shared, "task.intervention.accept")).isEnabled());
                        assertTrue(onEdt(shared::isShowing));
                        holdHost.countDown();
                        await(() -> ((JButton) named(shared, "task.intervention.accept")).isEnabled());
                        assertSame(shared, onEdt(() -> dialog(frame)));
                        onEdt(() -> {
                            String visibleText = descendants(shared).filter(Component::isShowing)
                                .map(value -> value instanceof JTextArea area ? area.getText() : value instanceof JLabel label ? label.getText() : "")
                                .collect(java.util.stream.Collectors.joining("\n"));
                            assertTrue(visibleText.contains("演示服务器"));
                            assertTrue(visibleText.contains("这会短暂停止当前应用"));
                            assertFalse(visibleText.contains("internal-target"));
                            assertFalse(visibleText.contains("echo exact-review-only"));
                            gold.debug.windowstolinux.app.ui.control.DesktopDialogs.constrain(shared,
                                new Rectangle(40, 40, 780, 520), shared.getSize());
                            assertTrue(shared.getWidth() <= 780);
                            for (String action : List.of("task.intervention.accept", "task.intervention.later", "task.intervention.cancel")) {
                                var button = (JButton) named(shared, action);
                                var bounds = SwingUtilities.convertRectangle(button.getParent(), button.getBounds(), shared);
                                assertTrue(bounds.x >= 0 && bounds.y >= 0 && bounds.getMaxX() <= shared.getWidth()
                                    && bounds.getMaxY() <= shared.getHeight(), action);
                            }
                            capture(shared, "intervention-summary-" + themeName + "-" + scale + ".png");
                            ((JButton) named(shared, "task.intervention.accept")).doClick(0);
                            return null;
                        });
                        await(() -> finalRef.get() != null && ((JButton) named(shared, "task.intervention.accept")).getText().equals("确认部署"));
                        assertSame(shared, onEdt(() -> dialog(frame)));
                        onEdt(() -> {
                            ((JButton) named(shared, "task.intervention.accept")).doClick(0);
                            return null;
                        });
                        assertEquals(TaskResult.SUCCEEDED, task.completion().toCompletableFuture().get(10, TimeUnit.SECONDS).outcome());
                        assertEquals(2, accepted.get());
                        await(() -> dialog(frame) == null && !named(frame, "platform.pendingInterventions").isVisible());
                    } finally {
                        holdHost.countDown();
                        if (!task.completion().toCompletableFuture().isDone()) task.requestCancel();
                        task.completion().toCompletableFuture().get(10, TimeUnit.SECONDS);
                    }
                }
            } finally {
                scope.close();
                onEdt(() -> {
                    frame.dispose();
                    DesktopMotion.suspended(false);
                    DesktopMotion.preference(true);
                    DesktopThemeService.apply(ThemeMode.LIGHT, 100);
                    return null;
                });
            }
        }
    }

    private static UiInterventionForm<Boolean> form(AtomicInteger accepted) {
        return new UiInterventionForm<>() {
            private final JTextField value = new JTextField("演示应用", 28);

            public JComponent component() {
                return value;
            }

            public Boolean submit() {
                accepted.incrementAndGet();
                return Boolean.TRUE;
            }

            public void close() {
                value.setText("");
            }
        };
    }

    private static String visiblePromptText(Component root) {
        return descendants(root).filter(Component::isShowing)
            .map(value -> value instanceof JTextArea area ? area.getText() : value instanceof JLabel label ? label.getText() : "")
            .collect(java.util.stream.Collectors.joining("\n"));
    }

    private static void assertPasswordEmpty(JPasswordField field) {
        char[] value = field.getPassword();
        try {
            assertEquals(0, value.length, "Closed or accepted input must clear its document");
        } finally {
            Arrays.fill(value, '\0');
        }
    }

    private static JDialog dialog(PlatformFrame frame) {
        return Arrays.stream(frame.getOwnedWindows()).filter(Window::isShowing)
            .filter(value -> "task.intervention.dialog".equals(value.getName())).map(JDialog.class::cast).findFirst().orElse(null);
    }
}
