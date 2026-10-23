package gold.debug.windowstolinux.mod.deployment.ui.agent;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.imageio.ImageIO;
import javax.swing.*;

import gold.debug.windowstolinux.app.db.server.StoredServerProfile;
import gold.debug.windowstolinux.app.service.server.ServerProfile;
import gold.debug.windowstolinux.app.ui.mod.ModPageRegistry;
import gold.debug.windowstolinux.appapi.task.model.TaskSnapshot;
import gold.debug.windowstolinux.appapi.task.run.CancelToken;
import gold.debug.windowstolinux.appapi.ui.*;
import gold.debug.windowstolinux.appapi.ui.page.PageSpec;
import gold.debug.windowstolinux.appapi.ui.page.UiEnvironment;
import gold.debug.windowstolinux.mod.deployment.DeploymentRuntimeFixture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Uses the real lazy CLASS page and original host task while switching language, locking and cancelling. / 切换语言、锁定和取消时使用真实惰性 CLASS 页面及原宿主任务。 */
class DeploymentAgentPageTest extends DeploymentRuntimeFixture {
    private final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);

    private final AtomicReference<TaskSnapshot> selected = new AtomicReference<>();

    private final AtomicReference<CancelToken> original = new AtomicReference<>();

    private final AtomicBoolean cancelled = new AtomicBoolean();

    private JFrame window;

    @Test
    void goalViewKeepsBusyAcrossFailedPredecessorWatchesActualChildAndCancelsThatChild() throws Exception {
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            var context = runtime.modContexts().get(OWNER).context();
            var request = new gold.debug.windowstolinux.mod.deployment.service.agent.contract.DeploymentAgentInputs.Request(
                Map.of(), "MANUAL_REVIEW", false, 1024, 10, 1024);
            var goalClass = gold.debug.windowstolinux.mod.deployment.service.agent.DeploymentAgentGoal.class;
            var submitType = Class.forName(goalClass.getName() + "$Submit");
            var constructor = goalClass.getDeclaredConstructor(gold.debug.windowstolinux.appapi.mod.ModContext.class,
                gold.debug.windowstolinux.appapi.server.model.ServerId.class, request.getClass(), submitType);
            constructor.setAccessible(true);
            var submit = java.lang.reflect.Proxy.newProxyInstance(submitType.getClassLoader(), new Class<?>[]{submitType},
                (proxy, method, args) -> {
                    throw new AssertionError("Observation fixture cannot create a deployment");
                });
            var goal = (gold.debug.windowstolinux.mod.deployment.service.agent.DeploymentAgentGoal) constructor.newInstance(context,
                new gold.debug.windowstolinux.appapi.server.model.ServerId("server"), request, submit);
            UUID originalId = UUID.randomUUID();
            var instant = java.time.Instant.now();
            // Only predecessor observation is controlled here; actual FAILED publication is covered by WorkflowTest.
            var original = new gold.debug.windowstolinux.appapi.task.run.TaskHandle<gold.debug.windowstolinux.mod.deployment.service.agent.tools.DeploymentAgentDeliveryPort.Published>() {
                public UUID id() {
                    return originalId;
                }

                public TaskSnapshot snapshot() {
                    return new TaskSnapshot(originalId, OWNER, VERSION, "deployment-agent",
                        gold.debug.windowstolinux.appapi.task.model.TaskState.ENDED, Optional.of(gold.debug.windowstolinux.appapi.task.result.TaskResult.FAILED),
                        "Preserved failed predecessor", instant, Optional.of(instant), List.of());
                }

                public java.util.concurrent.CompletionStage<gold.debug.windowstolinux.appapi.task.result.OperationResult<gold.debug.windowstolinux.mod.deployment.service.agent.tools.DeploymentAgentDeliveryPort.Published>> completion() {
                    return new CompletableFuture<>();
                }

                public void requestCancel() {
                    throw new AssertionError("Ended predecessor must not be cancelled");
                }
            };
            var attach = goalClass.getDeclaredMethod("attach", gold.debug.windowstolinux.appapi.task.run.TaskHandle.class);
            attach.setAccessible(true);
            attach.invoke(goal, original);
            var active = new CountDownLatch(1);
            var cancelledActual = new AtomicBoolean();
            var child = context.tasks().<gold.debug.windowstolinux.mod.deployment.service.agent.tools.DeploymentAgentDeliveryPort.Published>submit(
                new gold.debug.windowstolinux.appapi.task.model.TaskRequest("goal-observation-child", "Actual cancellable current child", List.of(), true), task -> {
                    active.countDown();
                    long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
                    while (!task.cancelToken().requested() && System.nanoTime() < until) Thread.sleep(10);
                    cancelledActual.set(task.cancelToken().requested());
                    return new gold.debug.windowstolinux.appapi.task.result.OperationResult<>(gold.debug.windowstolinux.appapi.task.result.TaskResult.CANCELLED,
                        Optional.empty(), Optional.of(new gold.debug.windowstolinux.appapi.task.result.Problem(
                        gold.debug.windowstolinux.appapi.task.result.ProblemCode.CANCELLED, "CONTROLLED_CURRENT_CHILD_CANCELLED", "Actual cancellation observed", Map.of(), Optional.empty())));
                });
            assertTrue(active.await(15, TimeUnit.SECONDS));
            attach.invoke(goal, child);
            var page = new DeploymentAgentPage(new gold.debug.windowstolinux.mod.deployment.service.agent.DeploymentAgentPageService(context,
                new gold.debug.windowstolinux.mod.deployment.service.agent.DeploymentAgentWorkflow(context)));
            var component = new AtomicReference<JComponent>();
            var busyField = DeploymentAgentPage.class.getDeclaredField("busy");
            busyField.setAccessible(true);
            var goalField = DeploymentAgentPage.class.getDeclaredField("goal");
            goalField.setAccessible(true);
            var currentField = DeploymentAgentPage.class.getDeclaredField("current");
            currentField.setAccessible(true);
            var watch = DeploymentAgentPage.class.getDeclaredMethod("watchGoal", goalClass);
            watch.setAccessible(true);
            try {
                SwingUtilities.invokeAndWait(() -> {
                    component.set(page.create(new UiEnvironment(Locale.SIMPLIFIED_CHINESE, "LIGHT", false)));
                    try {
                        busyField.set(page, true);
                        goalField.set(page, goal);
                        watch.invoke(page, goal);
                    } catch (ReflectiveOperationException failed) {
                        throw new AssertionError(failed);
                    }
                });
                SwingUtilities.invokeAndWait(() -> {
                });
                assertEquals(child.id(), ((gold.debug.windowstolinux.appapi.task.run.TaskHandle<?>) currentField.get(page)).id());
                assertTrue(busyField.getBoolean(page));
                assertEquals(gold.debug.windowstolinux.appapi.task.result.TaskResult.FAILED, original.snapshot().outcome().orElseThrow());
                SwingUtilities.invokeAndWait(() -> button(component.get(), "cancel", Locale.SIMPLIFIED_CHINESE).doClick(0));
                child.completion().toCompletableFuture().get(15, TimeUnit.SECONDS);
                assertTrue(cancelledActual.get());
                long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                while (busyField.getBoolean(page) && System.nanoTime() < until) {
                    SwingUtilities.invokeAndWait(() -> {
                    });
                    Thread.sleep(20);
                }
                assertFalse(busyField.getBoolean(page));
                assertEquals(child.id(), goal.completion().toCompletableFuture().get(5, TimeUnit.SECONDS).finalTask());
                assertEquals(gold.debug.windowstolinux.appapi.task.result.TaskResult.FAILED, original.snapshot().outcome().orElseThrow());
            } finally {
                SwingUtilities.invokeAndWait(page::close);
            }
        }
    }

    @Override
    protected gold.debug.windowstolinux.app.main.start.PlatformRuntime open() throws Exception {
        var runtime = gold.debug.windowstolinux.app.main.start.PlatformRuntime.open(root.resolve("data"), PROTECTION);
        if (runtime.security().securityState().configured())
            runtime.security().unlock(PASSWORD.toCharArray());
        else
            runtime.security().setupAndUnlock(PASSWORD.toCharArray(), PASSWORD.toCharArray());
        runtime.setInteractionPresenter(interactionPrompts());
        runtime.setSourcePicker((title, snapshot, token) -> {
            original.set(token);
            selected.set(snapshot);
            entered.countDown();
            try {
                assertTrue(release.await(20, TimeUnit.SECONDS));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError(interrupted);
            }
            cancelled.set(token.requested());
            return root.resolve("input");
        });
        runtime.started();
        return runtime;
    }

    @Test
    void lazyOwnerPagePreservesDraftAndRevokesItsUnansweredSourcePickerAcrossLock() throws Exception {
        Files.createDirectories(root.resolve("input"));
        Files.writeString(root.resolve("input/README.md"), "Controlled original source\n");
        enable();
        try (var runtime = open()) {
            // Fixture metadata satisfies the existing pre-source model admission; no model call is made. / 夹具元数据满足既有来源前模型准入，不调用模型。
            try (var operation = runtime.security().enterOperation();
                 var credentials = runtime.security().openCredentials()) {
                credentials.save("ai/visual-fixture", "fixture-only".toCharArray());
            }
            runtime.persistence().aiProfiles().saveVerified(
                new gold.debug.windowstolinux.app.db.ai.StoredAiModelProfile("visual-model",
                    "https://example.invalid/v1/chat/completions", "fixture", "ai/visual-fixture"),
                "Visual fixture", java.time.Instant.now());
            runtime.persistence().aiProfiles().purposes().save(
                gold.debug.windowstolinux.app.ai.model.AiPurposeType.DEPLOYMENT,
                List.of(new gold.debug.windowstolinux.app.ai.model.AiPurposeAssignment("visual-model")));
            runtime.persistence().aiProfiles().purposes().save(
                gold.debug.windowstolinux.app.ai.model.AiPurposeType.APPROVAL,
                List.of(new gold.debug.windowstolinux.app.ai.model.AiPurposeAssignment("visual-model")));
            runtime.servers()
                .saveServerProfile(ServerProfile.fromStored(
                        new StoredServerProfile("visual-agent", "127.0.0.1", 22, "root", "fixture-password")),
                    "fixture-password".toCharArray());
            var mounted = new AtomicReference<JComponent>();
            var pages = new ModPageRegistry(new ModPageRegistry.PageHost() {
                public void show(ModPageRegistry.PageKey key, PageSpec spec, JComponent component) {
                    mounted.set(component);
                }

                public void focus(ModPageRegistry.PageKey key) {
                }

                public void remove(ModPageRegistry.PageKey key) {
                    mounted.set(null);
                }

                public void environment(UiEnvironment environment) {
                }
            }, new UiEnvironment(Locale.SIMPLIFIED_CHINESE, "LIGHT", false));
            var report = runtime.startDevelopmentMods(workspace(), pages);
            assertTrue(report.problems().isEmpty(), report.problems().toString());
            var key = new ModPageRegistry.PageKey(OWNER, "workspace");
            assertTrue(pages.contributions().stream().anyMatch(value -> value.key().equals(key)));
            assertNull(mounted.get(), "Agent registration must remain lazy");
            var previousWorkers = Thread.getAllStackTraces().keySet();
            pages.navigateView(key, "agent-deployment");
            JComponent first = mounted.get();
            assertNotNull(first);
            var ownedWorkers = pageWorkersSince(previousWorkers);
            var mode = (JComboBox<?>) named(first, "deployment.preference.approval");
            var source = (JComboBox<?>) named(first, "deployment.agent.source");
            SwingUtilities.invokeAndWait(() -> {
                window = new JFrame();
                window.setContentPane(first);
                window.setSize(1000, 720);
                ((JButton) named(first, "deployment.advanced")).doClick(0);
            });
            var advanced = window.getOwnedWindows()[0];
            var project = (JTextField) named(advanced, "deployment.agent.projectName");
            var url = (JTextField) named(advanced, "deployment.agent.gitUrl");
            var requirements = (JTextArea) ((JScrollPane) named(advanced, "deployment.agent.requirements"))
                .getViewport().getView();
            var start = button(first, "start", Locale.SIMPLIFIED_CHINESE);
            var cancel = button(first, "cancel", Locale.SIMPLIFIED_CHINESE);
            var servers = (JComboBox<?>) named(first, "deployment.agent.server");
            awaitServerRows(servers);
            SwingUtilities.invokeAndWait(() -> {
                assertNull(servers.getSelectedItem(), "Discovery must not choose the autonomous target");
                assertFalse(start.isEnabled());
                servers.setSelectedIndex(0);
            });
            awaitEnabled(mode, true);
            SwingUtilities.invokeAndWait(() -> {
                ((JButton) named(first, "deployment.server.clear")).doClick(0);
                assertNull(servers.getSelectedItem());
                assertFalse(start.isEnabled());
                assertTrue(((JTextArea) named(first, "deployment.server.summary")).getText().contains("明确选择"));
                servers.setSelectedIndex(0);
            });
            assertFalse(start.isEnabled(), "An explicit target does not choose autonomous source metadata");
            SwingUtilities.invokeAndWait(() -> {
                source.setSelectedItem("GIT");
                project.setText("unsubmitted-project");
                url.setText("https://example.invalid/repository.git");
                requirements.setText("goal=retained human choice");
                mode.setSelectedItem("FULL_CONTROL");
            });
            pages.environment(new UiEnvironment(Locale.ENGLISH, "LIGHT", false));
            capture(first);
            SwingUtilities.invokeAndWait(() -> {
                assertEquals("Start autonomous deployment", start.getText());
                assertEquals("FULL_CONTROL", mode.getSelectedItem());
                assertEquals("unsubmitted-project", project.getText());
                assertEquals("https://example.invalid/repository.git", url.getText());
                assertEquals("goal=retained human choice", requirements.getText());
                source.setSelectedItem("LOCAL");
                mode.setSelectedItem("MANUAL_REVIEW");
            });
            awaitEnabled(start, false);
            SwingUtilities.invokeAndWait(() -> {
                ((JButton) named(first, "deployment.local.choose")).doClick();
                assertFalse(start.isEnabled());
            });
            assertTrue(entered.await(15, TimeUnit.SECONDS));
            assertEquals(OWNER, selected.get().ownerId());
            assertEquals(VERSION, selected.get().ownerVersion());
            assertEquals("deployment-source-selection", selected.get().type());
            pages.environment(new UiEnvironment(Locale.SIMPLIFIED_CHINESE, "LIGHT", true));
            SwingUtilities.invokeAndWait(() -> {
                assertFalse(start.isEnabled());
                assertFalse(cancel.isEnabled());
            });
            assertTrue(original.get().requested(), "Locking revokes only the unanswered short picker");
            pages.environment(new UiEnvironment(Locale.ENGLISH, "LIGHT", false));
            awaitEnabled(cancel, false);
            SwingUtilities.invokeAndWait(() -> {
                assertFalse(start.isEnabled());
            });
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
            while (!original.get().requested() && System.nanoTime() < until)
                Thread.sleep(20);
            assertTrue(original.get().requested());
            release.countDown();
            until = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            while (scalar(runtime,
                "SELECT COUNT(*) FROM platform_task WHERE task_id='" + selected.get().id() + "'") != 0
                && System.nanoTime() < until)
                Thread.sleep(20);
            assertEquals(0,
                scalar(runtime, "SELECT COUNT(*) FROM platform_task WHERE task_id='" + selected.get().id() + "'"),
                diagnostic(runtime, selected.get().id()));
            assertTrue(cancelled.get());
            awaitEnabled(start, false);
            assertEquals("", ((JTextField) named(first, "deployment.local.location")).getText(), "Cancelled late metadata cannot refill source");
            pages.closeView(key);
            assertNull(mounted.get());
            pages.navigateView(key, "agent-deployment");
            assertNotSame(first, mounted.get());
            ownedWorkers.addAll(pageWorkersSince(previousWorkers));
            awaitEnabled(button(mounted.get(), "start", Locale.ENGLISH), false);
            until = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            while (scalar(runtime, "SELECT COUNT(*) FROM platform_task WHERE owner_id='" + OWNER + "'") != 0
                && System.nanoTime() < until)
                Thread.sleep(20);
            assertEquals(0, scalar(runtime, "SELECT COUNT(*) FROM platform_task WHERE owner_id='" + OWNER + "'"));
            pages.closeView(key);
            awaitClosedPageWorkers(runtime, ownedWorkers);
        } finally {
            release.countDown();
            SwingUtilities.invokeAndWait(() -> {
                if (window != null)
                    window.dispose();
            });
        }
    }

    @Test
    void originalAgentCardRequiresClearBeforeLocalGitReplacementAndNeverFetchesPreselectedMetadata() throws Exception {
        release.countDown();
        Path firstDirectory = Files.createDirectories(root.resolve("input"));
        Path secondDirectory = Files.createDirectories(root.resolve("second-input"));
        Files.writeString(firstDirectory.resolve("README.md"), "First original source\n");
        Files.writeString(secondDirectory.resolve("README.md"), "Second original source\n");
        enable();
        try (var runtime = open()) {
            runtime.servers().saveServerProfile(ServerProfile.fromStored(
                    new StoredServerProfile("visual-agent", "127.0.0.1", 22, "root", "fixture-password")),
                "fixture-password".toCharArray());
            var mounted = new AtomicReference<JComponent>();
            var pages = new ModPageRegistry(new ModPageRegistry.PageHost() {
                public void show(ModPageRegistry.PageKey key, PageSpec spec, JComponent component) {
                    mounted.set(component);
                }

                public void focus(ModPageRegistry.PageKey key) {
                }

                public void remove(ModPageRegistry.PageKey key) {
                    mounted.set(null);
                }

                public void environment(UiEnvironment environment) {
                }
            }, new UiEnvironment(Locale.ENGLISH, "LIGHT", false));
            assertTrue(runtime.startDevelopmentMods(workspace(), pages).problems().isEmpty());
            var key = new ModPageRegistry.PageKey(OWNER, "workspace");
            pages.navigateView(key, "agent-deployment");
            JComponent page = mounted.get();
            var card = (JComponent) named(page, "deployment.agent.sourceCard");
            var location = (JTextField) named(page, "deployment.local.location");
            var servers = (JComboBox<?>) named(page, "deployment.agent.server");
            var start = button(page, "start", Locale.ENGLISH);
            awaitServerRows(servers);
            SwingUtilities.invokeAndWait(() -> {
                assertNull(servers.getSelectedItem());
                servers.setSelectedIndex(0);
                assertFalse(start.isEnabled());
                assertFalse(card.getTransferHandler().importData(new TransferHandler.TransferSupport(card,
                    files(firstDirectory, secondDirectory))));
                assertTrue(card.getTransferHandler().importData(new TransferHandler.TransferSupport(card,
                    files(firstDirectory))));
            });
            awaitLocation(location, firstDirectory.toRealPath().toUri().toString());
            awaitEnabled(start, true);
            SwingUtilities.invokeAndWait(() -> {
                ((JButton) named(page, "deployment.server.clear")).doClick(0);
                assertNull(servers.getSelectedItem());
                assertFalse(start.isEnabled());
                servers.setSelectedIndex(0);
                assertTrue(start.isEnabled(), "Both actual source preselection and explicit target are required");
                assertFalse(card.getTransferHandler().importData(new TransferHandler.TransferSupport(card,
                    new java.awt.datatransfer.StringSelection("https://example.invalid/ignored.git"))));
                assertEquals(firstDirectory.toUri().toString(), location.getText());
                ((JButton) named(page, "deployment.local.clear")).doClick(0);
                assertFalse(start.isEnabled());
                assertFalse(card.getTransferHandler().importData(new TransferHandler.TransferSupport(card,
                    new java.awt.datatransfer.StringSelection("https://secret-sentinel@example.invalid/repository.git"))));
                assertTrue(card.getTransferHandler().importData(new TransferHandler.TransferSupport(card,
                    new java.awt.datatransfer.StringSelection("https://example.invalid/original.git"))));
                assertEquals("GIT", ((JComboBox<?>) named(page, "deployment.agent.source")).getSelectedItem());
                assertEquals("https://example.invalid/original.git", ((JTextField) named(page, "deployment.agent.sourceRemote")).getText());
                assertFalse(card.getTransferHandler().importData(new TransferHandler.TransferSupport(card, files(secondDirectory))));
            });
            pages.environment(new UiEnvironment(Locale.SIMPLIFIED_CHINESE, "LIGHT", false));
            SwingUtilities.invokeAndWait(() -> {
                assertEquals("https://example.invalid/original.git", ((JTextField) named(page, "deployment.agent.sourceRemote")).getText());
                ((JButton) named(page, "deployment.agent.sourceClear")).doClick(0);
                assertTrue(card.getTransferHandler().importData(new TransferHandler.TransferSupport(card,
                    new java.awt.datatransfer.StringSelection(secondDirectory.toAbsolutePath().toString()))));
            });
            awaitLocation(location, secondDirectory.toRealPath().toUri().toString());
            assertEquals(0, scalar(runtime, "SELECT COUNT(*) FROM platform_task WHERE task_type='deployment-agent'"));
            assertEquals(0, scalar(runtime, "SELECT COUNT(*) FROM platform_task_resource WHERE resource_kind IN ('FILE','PROCESS','SERVICE') AND released_at IS NULL"));
            assertEquals("First original source\n", Files.readString(firstDirectory.resolve("README.md")));
            assertEquals("Second original source\n", Files.readString(secondDirectory.resolve("README.md")));
            pages.closeView(key);
        }
    }

    @ParameterizedTest(name = "closeConfirmedView={0}")
    @ValueSource(booleans = {false, true})
    void preselectedOriginalRootSurvivesLockOrCloseAndOnlyItsExplicitCancelSettlesResources(boolean closeView) throws Exception {
        release.countDown();
        Files.createDirectories(root.resolve("input"));
        Files.writeString(root.resolve("input/README.md"), "Protected original source\n");
        var rootEntered = new CountDownLatch(1);
        var rootRelease = new CountDownLatch(1);
        var rootSubmitted = new CountDownLatch(1);
        var originalRoot = new AtomicReference<gold.debug.windowstolinux.appapi.task.run.TaskHandle<?>>();
        var originalContext = new AtomicReference<gold.debug.windowstolinux.appapi.task.run.TaskContext>();
        enable();
        try (var runtime = open()) {
            try (var operation = runtime.security().enterOperation(); var credentials = runtime.security().openCredentials()) {
                credentials.save("ai/visual-fixture", "fixture-only".toCharArray());
            }
            runtime.persistence().aiProfiles().saveVerified(new gold.debug.windowstolinux.app.db.ai.StoredAiModelProfile(
                "visual-model", "https://example.invalid/v1/chat/completions", "fixture", "ai/visual-fixture"), "Visual fixture", java.time.Instant.now());
            runtime.persistence().aiProfiles().purposes().save(gold.debug.windowstolinux.app.ai.model.AiPurposeType.DEPLOYMENT,
                List.of(new gold.debug.windowstolinux.app.ai.model.AiPurposeAssignment("visual-model")));
            runtime.persistence().aiProfiles().purposes().save(gold.debug.windowstolinux.app.ai.model.AiPurposeType.APPROVAL,
                List.of(new gold.debug.windowstolinux.app.ai.model.AiPurposeAssignment("visual-model")));
            runtime.servers().saveServerProfile(ServerProfile.fromStored(
                new StoredServerProfile("visual-agent", "127.0.0.1", 22, "root", "fixture-password")), "fixture-password".toCharArray());
            start(runtime, true);
            var actual = runtime.modContexts().get(OWNER).context();
            var file = gold.debug.windowstolinux.mod.deployment.file.DeploymentFileServiceFixture.create(
                gold.debug.windowstolinux.appapi.file.FileService.class.getClassLoader(), (proxy, method, arguments) -> {
                    Object value;
                    try {
                        value = gold.debug.windowstolinux.mod.deployment.file.DeploymentFileServiceFixture.invoke(method, actual.file(), arguments);
                    } catch (java.lang.reflect.InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                    if (method.getName().equals("selectDirectory") && arguments[0] instanceof Path) {
                        var task = (gold.debug.windowstolinux.appapi.task.run.TaskContext) arguments[1];
                        assertEquals("deployment-agent", actual.tasks().inspect(task).type());
                        assertTrue(originalContext.compareAndSet(null, task));
                        rootEntered.countDown();
                        assertTrue(rootRelease.await(20, TimeUnit.SECONDS));
                    }
                    return value;
                });
            var tasks = (gold.debug.windowstolinux.appapi.task.TaskService) java.lang.reflect.Proxy.newProxyInstance(
                gold.debug.windowstolinux.appapi.task.TaskService.class.getClassLoader(),
                new Class<?>[]{gold.debug.windowstolinux.appapi.task.TaskService.class}, (proxy, method, arguments) -> {
                    try {
                        Object value = method.invoke(actual.tasks(), arguments);
                        if (method.getName().equals("submit") && arguments[0] instanceof gold.debug.windowstolinux.appapi.task.model.TaskRequest request
                            && request.type().equals("deployment-agent")) {
                            originalRoot.set((gold.debug.windowstolinux.appapi.task.run.TaskHandle<?>) value);
                            rootSubmitted.countDown();
                        }
                        return value;
                    } catch (java.lang.reflect.InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                });
            var bound = (gold.debug.windowstolinux.appapi.mod.ModContext) java.lang.reflect.Proxy.newProxyInstance(
                gold.debug.windowstolinux.appapi.mod.ModContext.class.getClassLoader(), new Class<?>[]{gold.debug.windowstolinux.appapi.mod.ModContext.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("file")) return file;
                    if (method.getName().equals("tasks")) return tasks;
                    try {
                        return method.invoke(actual, arguments);
                    } catch (java.lang.reflect.InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                });
            var workflow = new gold.debug.windowstolinux.mod.deployment.service.agent.DeploymentAgentWorkflow(bound);
            var page = new DeploymentAgentPage(new gold.debug.windowstolinux.mod.deployment.service.agent.DeploymentAgentPageService(bound, workflow));
            var mounted = new AtomicReference<JComponent>();
            var previousWorkers = Thread.getAllStackTraces().keySet();
            var ownedWorkers = new HashSet<Thread>();
            try {
                SwingUtilities.invokeAndWait(() -> mounted.set(page.create(new UiEnvironment(Locale.ENGLISH, "LIGHT", false))));
                ownedWorkers.addAll(pageWorkersSince(previousWorkers));
                JComponent rootPage = mounted.get();
                var servers = (JComboBox<?>) named(rootPage, "deployment.agent.server");
                var policy = (JComboBox<?>) named(rootPage, "deployment.preference.approval");
                var remember = (JCheckBox) named(rootPage, "deployment.preference.rememberAuthorization");
                var startButton = button(rootPage, "start", Locale.ENGLISH);
                awaitServerRows(servers);
                awaitEnabled(policy, true);
                SwingUtilities.invokeAndWait(() -> {
                    policy.setSelectedItem("MANUAL_REVIEW");
                    servers.setSelectedIndex(0);
                    ((JButton) named(rootPage, "deployment.local.choose")).doClick(0);
                });
                awaitLocation((JTextField) named(rootPage, "deployment.local.location"), root.resolve("input").toRealPath().toUri().toString());
                awaitEnabled(startButton, true);
                SwingUtilities.invokeAndWait(() -> startButton.doClick(0));
                assertTrue(rootEntered.await(15, TimeUnit.SECONDS));
                assertTrue(rootSubmitted.await(5, TimeUnit.SECONDS));
                var handle = Objects.requireNonNull(originalRoot.get());
                assertEquals(originalContext.get().id(), handle.id());
                assertEquals("deployment-agent", handle.snapshot().type());
                assertEquals(OWNER, handle.snapshot().ownerId());
                assertEquals(VERSION, handle.snapshot().ownerVersion());
                SwingUtilities.invokeAndWait(() -> {
                    assertFalse(policy.isEnabled());
                    assertFalse(remember.isEnabled());
                    policy.setSelectedItem("FULL_CONTROL");
                    remember.setSelected(false);
                    assertEquals("MANUAL_REVIEW", policy.getSelectedItem(), "The accepted original root freezes both policies");
                    assertTrue(remember.isSelected());
                });
                if (closeView) {
                    SwingUtilities.invokeAndWait(page::close);
                    assertFalse(originalContext.get().cancelToken().requested(), "Closing a confirmed view leaves its original root in the background");
                    handle.requestCancel();
                } else {
                    SwingUtilities.invokeAndWait(() -> page.environmentChanged(new UiEnvironment(Locale.ENGLISH, "LIGHT", true)));
                    assertFalse(originalContext.get().cancelToken().requested(), "Locking a confirmed view does not cancel its original root");
                    SwingUtilities.invokeAndWait(() -> page.environmentChanged(new UiEnvironment(Locale.ENGLISH, "LIGHT", false)));
                    var cancelButton = button(rootPage, "cancel", Locale.ENGLISH);
                    awaitEnabled(cancelButton, true);
                    SwingUtilities.invokeAndWait(() -> cancelButton.doClick(0));
                }
                long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
                while (!originalContext.get().cancelToken().requested() && System.nanoTime() < until) Thread.sleep(20);
                assertTrue(originalContext.get().cancelToken().requested());
                rootRelease.countDown();
                var outcome = handle.completion().toCompletableFuture().get(20, TimeUnit.SECONDS);
                assertEquals(gold.debug.windowstolinux.appapi.task.result.TaskResult.CANCELLED, outcome.outcome(), outcome.toString());
                assertEquals(gold.debug.windowstolinux.appapi.task.model.TaskState.ENDED, handle.snapshot().state());
                assertEquals(0, scalar(runtime, "SELECT COUNT(*) FROM platform_task_resource WHERE task_id='" + handle.id() + "' AND released_at IS NULL"));
            } finally {
                rootRelease.countDown();
                SwingUtilities.invokeAndWait(page::close);
                awaitClosedPageWorkers(runtime, ownedWorkers);
            }
        } finally {
            rootRelease.countDown();
        }
    }

    /** Captures only workers created by this test's newly mounted original page. / 仅捕获本测试新挂载原页面创建的工作线程。
     * @param previous threads present before mounting / 挂载前已存在的线程
     * @return actual new page worker identities / 实际新页面工作线程身份 */
    private static Set<Thread> pageWorkersSince(Set<Thread> previous) {
        var result = new HashSet<>(Thread.getAllStackTraces().keySet());
        result.removeAll(previous);
        result.removeIf(thread -> !thread.getName().equals("deployment-agent-page"));
        return result;
    }

    /** Waits for already shut down original page workers and their authentic short tasks, without altering business work. / 等待已关闭的原页面线程及其真实短任务，不改变业务工作。
     * @param runtime original fixture runtime / 原夹具运行时
     * @param workers exact workers observed after this test mounted its pages / 本测试挂载页面后观察到的精确线程
     * @throws Exception if original work does not return / 原工作未返回时 */
    private void awaitClosedPageWorkers(gold.debug.windowstolinux.app.main.start.PlatformRuntime runtime,
                                        Set<Thread> workers) throws Exception {
        assertFalse(workers.isEmpty(), "The actual original page worker must have been observed");
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        for (var worker : workers) {
            long remaining = until - System.nanoTime();
            if (remaining > 0) worker.join(Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining)));
            assertFalse(worker.isAlive(), "The original closed page still has in-flight queries or diagnostics");
        }
        while (scalar(runtime, "SELECT COUNT(*) FROM platform_task WHERE owner_id='" + OWNER + "'") != 0
            && System.nanoTime() < until) Thread.sleep(20);
        assertEquals(0, scalar(runtime, "SELECT COUNT(*) FROM platform_task WHERE owner_id='" + OWNER + "'"),
            "The original short tasks must settle before closing their host");
        assertEquals(0, scalar(runtime, "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL"));
    }

    /** Supplies one actual native file-list gesture. / 提供一个实际原生文件列表交互。
     * @param paths explicit user locations / 明确用户位置
     * @return actual Swing transferable / 实际Swing可转交值 */
    private static java.awt.datatransfer.Transferable files(Path... paths) {
        return new java.awt.datatransfer.Transferable() {
            public java.awt.datatransfer.DataFlavor[] getTransferDataFlavors() {
                return new java.awt.datatransfer.DataFlavor[]{java.awt.datatransfer.DataFlavor.javaFileListFlavor};
            }

            public boolean isDataFlavorSupported(java.awt.datatransfer.DataFlavor flavor) {
                return flavor.equals(java.awt.datatransfer.DataFlavor.javaFileListFlavor);
            }

            public Object getTransferData(java.awt.datatransfer.DataFlavor flavor) {
                return Arrays.stream(paths).map(Path::toFile).toList();
            }
        };
    }

    /** Waits for metadata from actual host completion, never a timeout release. / 等待实际宿主完成的元数据，不按超时释放。
     * @param input original metadata display / 原元数据显示
     * @param expected canonical URI / 规范URI
     * @throws Exception if original metadata does not arrive / 原元数据未到达时 */
    private static void awaitLocation(JTextField input, String expected) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        var observed = new AtomicReference<String>();
        do {
            SwingUtilities.invokeAndWait(() -> observed.set(input.getText()));
            if (expected.equals(observed.get())) return;
            Thread.sleep(20);
        } while (System.nanoTime() < until);
        assertEquals(expected, observed.get());
    }

    private String diagnostic(gold.debug.windowstolinux.app.main.start.PlatformRuntime runtime, UUID task)
        throws Exception {
        var details = new StringBuilder();
        try (var connection = runtime.persistence().database().openConnection()) {
            for (String query : List.of("SELECT state,outcome,progress_text FROM platform_task WHERE task_id=?",
                "SELECT resource_kind,resource_id FROM platform_task_resource WHERE task_id=? AND released_at IS NULL"))
                try (var statement = connection.prepareStatement(query)) {
                    statement.setObject(1, task);
                    try (var rows = statement.executeQuery()) {
                        while (rows.next())
                            for (int i = 1; i <= rows.getMetaData().getColumnCount(); i++)
                                details.append(rows.getMetaData().getColumnName(i)).append('=')
                                    .append(rows.getString(i)).append(' ');
                    }
                }
        }
        return details.toString();
    }

    private void capture(JComponent page) throws Exception {
        String directory = System.getProperty("agent.page.screenshot.dir");
        if (directory == null)
            return;
        assertFalse(GraphicsEnvironment.isHeadless());
        var failure = new AtomicReference<Throwable>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                if (window == null)
                    window = new JFrame("Actual CLASS autonomous deployment");
                window.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
                window.setContentPane(page);
                window.pack();
                window.setSize(1000, 720);
                window.setVisible(true);
                window.validate();
                var image = new BufferedImage(window.getWidth(), window.getHeight(), BufferedImage.TYPE_INT_ARGB);
                Graphics2D graphics = image.createGraphics();
                try {
                    window.printAll(graphics);
                } finally {
                    graphics.dispose();
                }
                Path file = Path.of(directory).resolve("agent-class-page-en.png");
                Files.createDirectories(file.getParent());
                assertTrue(ImageIO.write(image, "png", file.toFile()));
            } catch (Throwable error) {
                failure.set(error);
            }
        });
        if (failure.get() != null)
            throw new AssertionError("Actual visible CLASS page capture failed", failure.get());
    }

    private static Component named(Container root, String name) {
        return descendants(root).stream().filter(c -> name.equals(c.getName())).findFirst().orElseThrow();
    }

    private static JButton button(JComponent root, String key, Locale locale) {
        String expected = ResourceBundle
            .getBundle("gold.debug.windowstolinux.mod.deployment.ui.agent.deployment-agent-page", locale).getString(key);
        return descendants(root).stream()
            .filter(value -> value instanceof JButton button && button.getText().equals(expected))
            .map(JButton.class::cast).findFirst().orElseThrow();
    }

    private static List<Component> descendants(Container root) {
        var result = new ArrayList<Component>();
        for (Component child : root.getComponents()) {
            result.add(child);
            if (child instanceof Container container)
                result.addAll(descendants(container));
        }
        return result;
    }

    private static void awaitEnabled(JComponent button, boolean expected) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(12);
        var observed = new AtomicBoolean();
        do {
            SwingUtilities.invokeAndWait(() -> observed.set(button.isEnabled()));
            if (observed.get() == expected)
                return;
            Thread.sleep(20);
        } while (System.nanoTime() < until);
        assertEquals(expected, observed.get());
    }

    private static void awaitServerRows(JComboBox<?> choices) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(12);
        var count = new AtomicInteger();
        do {
            SwingUtilities.invokeAndWait(() -> count.set(choices.getItemCount()));
            if (count.get() > 0) return;
            Thread.sleep(20);
        } while (System.nanoTime() < until);
        assertTrue(count.get() > 0, "Actual original server query must complete");
    }
}
