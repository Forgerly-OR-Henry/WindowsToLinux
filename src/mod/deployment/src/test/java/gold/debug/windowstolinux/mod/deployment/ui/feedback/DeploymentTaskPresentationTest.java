package gold.debug.windowstolinux.mod.deployment.ui.feedback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.image.BufferedImage;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JProgressBar;
import javax.swing.JTextArea;
import javax.swing.JToggleButton;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.imageio.ImageIO;

import gold.debug.windowstolinux.app.ui.display.DesktopThemeService;
import gold.debug.windowstolinux.app.ui.display.ThemeMode;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.task.log.LogEvent;
import gold.debug.windowstolinux.appapi.task.log.LogLevel;
import gold.debug.windowstolinux.appapi.task.log.LogReadResult;
import gold.debug.windowstolinux.appapi.task.log.LogService;
import gold.debug.windowstolinux.appapi.task.log.LogTarget;
import gold.debug.windowstolinux.appapi.task.model.TaskSnapshot;
import gold.debug.windowstolinux.appapi.task.model.TaskState;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.appapi.task.run.TaskHandle;
import gold.debug.windowstolinux.mod.deployment.config.DeploymentConfig;
import gold.debug.windowstolinux.mod.deployment.service.feedback.DeploymentDiagnostics;
import gold.debug.windowstolinux.mod.deployment.ui.style.DeploymentPageStyle;
import org.junit.jupiter.api.Test;

class DeploymentTaskPresentationTest {
    @Test
    void onlyFixedWorkflowMessagesBecomePublicStages() {
        assertEquals("stage.build", DeploymentTaskStages.identify("正在构建已核验候选：component-a").orElseThrow().message());
        assertEquals("stage.build", DeploymentTaskStages.identify("Building the verified candidate: component-a").orElseThrow().message());
        assertEquals("stage.publish", DeploymentTaskStages.identify("stage.publishCandidate").orElseThrow().message());
        assertTrue(DeploymentTaskStages.identify("UNTRUSTED_EVENT raw command output").isEmpty());
        assertTrue(DeploymentTaskStages.identify("Building the verified candidate: x\nraw output").isEmpty());
    }

    @Test
    void actualProgressManualDecisionsAndCleanupRemainIndependent() throws Exception {
        try (var fixture = new Fixture("deployment-agent")) {
            fixture.projection.set(Optional.of(new DeploymentTaskFeedback.Presentation(Optional.of(1.0), false, 3, true, false)));
            fixture.refresh(TaskState.WAITING, Optional.empty(), "RAW_MODEL_TEXT");
            fixture.await(() -> fixture.label("interventionCount").equals("有 3 项需要人工介入"));
            assertEquals("99%", fixture.label("percentage"));
            assertTrue(fixture.text("state").contains("已暂停"));
            assertFalse(fixture.text("state").contains("RAW_MODEL_TEXT"));
            assertTrue(fixture.text("details").contains("RAW_MODEL_TEXT"));
            assertTrue(fixture.text("details").contains(fixture.id.toString()));
            assertTrue(fixture.text("details").contains("HOST_RAW_DIAGNOSTIC"));
            assertFalse(fixture.text("state").contains("HOST_RAW_DIAGNOSTIC"));
            assertFalse(fixture.text("state").contains(fixture.id.toString()));
            assertFalse(fixture.detailsVisible());
            SwingUtilities.invokeAndWait(() -> {
                fixture.button("intervention").doClick();
                ((JToggleButton) fixture.named("detailsToggle")).doClick();
            });
            assertEquals(fixture.id, fixture.navigated.get());
            assertTrue(fixture.detailsVisible());

            fixture.projection.set(Optional.of(new DeploymentTaskFeedback.Presentation(Optional.of(.73), false, 2, false, false)));
            fixture.refresh(TaskState.RUNNING, Optional.empty(), "stage.buildComponent");
            fixture.await(() -> fixture.text("state").equals("等待人工介入"));
            assertEquals(TaskState.RUNNING, fixture.snapshot.get().state());
            assertEquals("有 2 项需要人工介入", fixture.label("interventionCount"));

            fixture.projection.set(Optional.of(new DeploymentTaskFeedback.Presentation(Optional.of(1.0), false, 0, false, false)));
            fixture.refresh(TaskState.FINISHING, Optional.empty(), "RAW_MODEL_TEXT");
            fixture.await(() -> fixture.text("state").contains("正在收尾并释放资源"));
            assertEquals("99%", fixture.label("percentage"));
            fixture.refresh(TaskState.CANCEL_REQUESTED, Optional.empty(), "RAW_MODEL_TEXT");
            fixture.await(() -> fixture.text("state").contains("正在停止，等待收尾"));
            assertEquals("99%", fixture.label("percentage"));

            fixture.projection.set(Optional.of(new DeploymentTaskFeedback.Presentation(Optional.of(.73), false, 0, false, true)));
            fixture.refresh(TaskState.UNKNOWN, Optional.empty(), "UNKNOWN_EVENT");
            fixture.await(() -> fixture.label("percentage").equals("73%"));
            assertTrue(fixture.text("state").contains("结果尚未确认"));
            fixture.refresh(TaskState.ENDED, Optional.of(TaskResult.CANCELLED), "UNKNOWN_EVENT");
            fixture.await(() -> fixture.text("state").contains("已取消，收尾已确认"));
            assertEquals("73%", fixture.label("percentage"));
            fixture.refresh(TaskState.ENDED, Optional.of(TaskResult.FAILED), "UNKNOWN_EVENT");
            fixture.await(() -> fixture.text("state").contains("任务未能完成"));
            assertEquals("73%", fixture.label("percentage"));

            fixture.refresh(TaskState.ENDED, Optional.of(TaskResult.SUCCEEDED), "UNKNOWN_EVENT");
            fixture.await(() -> fixture.label("percentage").equals("100%"));
            assertEquals("已成功完成", fixture.text("state"));
        }
    }

    @Test
    void stageEstimatesAreExplicitAndRepeatedPollsDoNotDuplicateTheStage() throws Exception {
        try (var fixture = new Fixture("deployment-publication")) {
            fixture.records.set(List.of(fixture.stage("stage.buildComponent"), fixture.stage("stage.buildComponent"), fixture.stage("stage.freezeSource")));
            fixture.refresh(TaskState.RUNNING, Optional.empty(), "stage.buildComponent");
            fixture.await(() -> fixture.label("percentage").equals("预计 45%"));
            fixture.await(() -> fixture.text("events").contains("正在复制项目文件并准备应用"));
            String first = fixture.text("events");
            assertEquals(2, first.lines().count());
            fixture.refresh(TaskState.RUNNING, Optional.empty(), "stage.buildComponent");
            fixture.await(() -> fixture.label("percentage").equals("预计 45%"));
            assertEquals(first, fixture.text("events"));
            assertTrue(fixture.text("details").contains("DEPLOYMENT_STAGE"));
            assertFalse(first.contains("DEPLOYMENT_STAGE"));
            fixture.projection.set(Optional.of(new DeploymentTaskFeedback.Presentation(Optional.of(.23), false, 0, false, false)));
            fixture.refresh(TaskState.RUNNING, Optional.empty(), "stage.buildComponent");
            fixture.await(() -> fixture.label("percentage").equals("23%"));
        }
        try (var fixture = new Fixture("deployment-agent")) {
            fixture.records.set(List.of(new LogReadResult.Record(UUID.randomUUID(), Instant.now(), new LogEvent(LogLevel.ERROR,
                "DEPLOYMENT_CANDIDATE_TRANSFER_FAILURE", "RAW_TRANSFER_ERROR", Optional.empty(), Optional.of(fixture.id),
                Optional.of(fixture.id), Optional.of(TaskResult.UNKNOWN)), false)));
            fixture.refresh(TaskState.UNKNOWN, Optional.empty(), "unrecognized raw stage");
            fixture.await(() -> fixture.text("state").contains("结果尚未确认"));
            assertEquals("暂无法计算进度", fixture.label("percentage"));
            assertTrue(fixture.progress().isIndeterminate());
            assertTrue(fixture.text("state").contains("暂无法计算进度"));
            assertTrue(fixture.text("state").contains("文件传输尚未确认"));
            assertFalse(fixture.text("state").contains("RAW_TRANSFER_ERROR"));
            assertTrue(fixture.text("details").contains("RAW_TRANSFER_ERROR"));
            fixture.refresh(TaskState.RUNNING, Optional.empty(), "agent.stage.build");
            fixture.await(() -> fixture.text("state").contains("正在复制项目文件并准备应用"));
            assertEquals("暂无法计算进度", fixture.label("percentage"));
        }
    }

    @Test
    void repeatedFeedbackPreservesDocumentsWhileNewStagesLogsAndLanguageRemainVisible() throws Exception {
        try (var fixture = new Fixture("deployment-publication")) {
            var freeze = fixture.stage("stage.freezeSource");
            fixture.records.set(List.of(freeze));
            fixture.refresh(TaskState.RUNNING, Optional.empty(), "stage.freezeSource");
            var changes = new java.util.LinkedHashMap<String, AtomicInteger>();
            fixture.onEdt(() -> {
                for (String suffix : List.of("state", "events", "details")) {
                    var count = new AtomicInteger();
                    changes.put(suffix, count);
                    ((JTextArea) fixture.named(suffix)).getDocument().addDocumentListener(new DocumentListener() {
                        public void insertUpdate(DocumentEvent event) {
                            count.incrementAndGet();
                        }

                        public void removeUpdate(DocumentEvent event) {
                            count.incrementAndGet();
                        }

                        public void changedUpdate(DocumentEvent event) {
                            count.incrementAndGet();
                        }
                    });
                }
                return null;
            });
            fixture.refresh(TaskState.RUNNING, Optional.empty(), "stage.freezeSource");
            fixture.refresh(TaskState.RUNNING, Optional.empty(), "stage.freezeSource");
            changes.forEach((suffix, count) -> assertEquals(0, count.get(), suffix));

            var build = fixture.stage("stage.buildComponent");
            fixture.records.set(List.of(build, freeze));
            fixture.refresh(TaskState.RUNNING, Optional.empty(), "stage.buildComponent");
            assertTrue(fixture.text("events").contains("正在复制项目文件并准备应用"));
            assertEquals(2, fixture.text("events").lines().count());
            changes.forEach((suffix, count) -> assertTrue(count.get() > 0, suffix));

            changes.values().forEach(count -> count.set(0));
            var diagnostic = new LogReadResult.Record(UUID.randomUUID(), Instant.now(), new LogEvent(LogLevel.INFO,
                "FIXTURE_NEW_DIAGNOSTIC", "New original diagnostic", Optional.empty(), Optional.of(fixture.id),
                Optional.of(fixture.id), Optional.empty()), false);
            fixture.records.set(List.of(diagnostic, build, freeze));
            fixture.refresh(TaskState.RUNNING, Optional.empty(), "stage.buildComponent");
            assertTrue(fixture.text("details").contains("New original diagnostic"));
            assertEquals(0, changes.get("state").get());
            assertEquals(0, changes.get("events").get());
            assertTrue(changes.get("details").get() > 0);

            fixture.refresh(TaskState.CANCEL_REQUESTED, Optional.empty(), "stage.buildComponent");
            assertTrue(fixture.text("state").contains("正在停止，等待收尾"));
            assertTrue(fixture.text("details").contains("CANCEL_REQUESTED"));
            changes.values().forEach(count -> count.set(0));
            fixture.onEdt(() -> {
                fixture.feedback.configure(Locale.ENGLISH, false);
                return null;
            });
            fixture.drain();
            assertTrue(fixture.text("events").contains("Copying project files and preparing the application"));
            assertFalse(fixture.text("state").contains("正在停止"));
            assertTrue(fixture.text("details").contains("New original diagnostic"));
            assertTrue(changes.get("state").get() > 0);
            assertTrue(changes.get("events").get() > 0);

            changes.values().forEach(count -> count.set(0));
            fixture.onEdt(() -> {
                fixture.feedback.configure(Locale.ENGLISH, false);
                return null;
            });
            fixture.drain();
            changes.forEach((suffix, count) -> assertEquals(0, count.get(), suffix));
        }
    }

    @Test
    void preparationAndRepeatedComponentStagesNeverMoveTheSameTaskEstimateBackwards() throws Exception {
        try (var fixture = new Fixture("deployment-publication")) {
            String[] stages = {"Freezing the selected source", "Checking the original source-project revision",
                "Closing original preparation resources", "Preparing, uploading and building the original candidate",
                "Publishing verified component: web", "Uploading and building component: backend",
                "Checking the original source-project revision"};
            int[] expected = {8, 30, 35, 45, 70, 70, 70};
            for (int index = 0; index < stages.length; index++) {
                fixture.refresh(TaskState.RUNNING, Optional.empty(), stages[index]);
                assertEquals("预计 " + expected[index] + "%", fixture.label("percentage"));
                assertFalse(fixture.text("state").contains("正在收尾"));
                if (index == 2) assertTrue(fixture.text("state").contains("正在完成部署准备"));
            }
            fixture.newOriginalTask("Freezing the selected source");
            assertEquals("预计 8%", fixture.label("percentage"));
        }
    }

    @Test
    void actualFeedbackCardsFitNarrowLightAndDarkWindowsWithCollapsedAndExpandedDetails() throws Exception {
        var previousTheme = com.formdev.flatlaf.FlatLaf.isLafDark() ? ThemeMode.DARK : ThemeMode.LIGHT;
        int previousZoom = Math.round(com.formdev.flatlaf.util.UIScale.getZoomFactor() * 100);
        Path output = Path.of("target/task-feedback-visuals");
        Files.createDirectories(output);
        try {
            for (var theme : new ThemeMode[]{ThemeMode.LIGHT, ThemeMode.DARK})
                for (int zoom : new int[]{100, 150}) {
                    SwingUtilities.invokeAndWait(() -> DesktopThemeService.install(theme, zoom));
                    try (var fixture = new Fixture("deployment-publication")) {
                        fixture.projection.set(Optional.of(new DeploymentTaskFeedback.Presentation(Optional.empty(), true, 2, false, false)));
                        fixture.records.set(List.of(fixture.stage("stage.buildComponent"), fixture.stage("stage.closePreparation"), fixture.stage("stage.freezeSource")));
                        fixture.refresh(TaskState.RUNNING, Optional.empty(), "Building the verified candidate: web");
                        var frame = fixture.onEdt(() -> {
                            var window = new JFrame("部署任务反馈 · " + zoom + "%");
                            var card = DeploymentPageStyle.card("style.results", fixture.feedback.component());
                            DeploymentPageStyle.refresh(card, java.util.ResourceBundle.getBundle(
                                "gold.debug.windowstolinux.mod.deployment.ui.deployment-page", Locale.SIMPLIFIED_CHINESE));
                            window.setContentPane(card);
                            window.setSize(new Dimension(560, 900));
                            window.setLocation(40, 40);
                            window.setVisible(true);
                            window.validate();
                            return window;
                        });
                        try {
                            for (boolean expanded : new boolean[]{false, true}) {
                                fixture.onEdt(() -> {
                                    if (expanded) ((JToggleButton) fixture.named("detailsToggle")).doClick();
                                    frame.validate();
                                    var percentage = fixture.named("percentage");
                                    assertTrue(percentage.getWidth() >= percentage.getPreferredSize().width);
                                    var intervention = fixture.button("intervention");
                                    assertTrue(intervention.isShowing());
                                    assertTrue(intervention.getWidth() >= intervention.getPreferredSize().width);
                                    var toggle = fixture.named("detailsToggle");
                                    assertTrue(toggle.isShowing());
                                    assertTrue(toggle.getWidth() >= toggle.getPreferredSize().width);
                                    assertEquals(expanded, fixture.named("details").isShowing());
                                    var image = new BufferedImage(frame.getWidth(), frame.getHeight(), BufferedImage.TYPE_INT_RGB);
                                    var graphics = image.createGraphics();
                                    try {
                                        frame.paint(graphics);
                                    } finally {
                                        graphics.dispose();
                                    }
                                    assertTrue(ImageIO.write(image, "png", output.resolve("feedback-" + theme + "-" + zoom
                                        + (expanded ? "-details.png" : "-default.png")).toFile()));
                                    return null;
                                });
                            }
                        } finally {
                            SwingUtilities.invokeAndWait(frame::dispose);
                        }
                    }
                }
        } finally {
            SwingUtilities.invokeAndWait(() -> DesktopThemeService.install(previousTheme, previousZoom));
        }
    }

    private static final class Fixture implements AutoCloseable {
        final UUID id = UUID.randomUUID();

        final String type;

        final AtomicReference<TaskSnapshot> snapshot = new AtomicReference<>();

        final AtomicReference<Optional<DeploymentTaskFeedback.Presentation>> projection = new AtomicReference<>(Optional.empty());

        final AtomicReference<List<LogReadResult.Record>> records = new AtomicReference<>(List.of());

        final AtomicReference<UUID> navigated = new AtomicReference<>();

        final ExecutorService worker = Executors.newSingleThreadExecutor();

        final DeploymentTaskFeedback feedback;

        Fixture(String type) throws Exception {
            this.type = type;
            snapshot.set(snapshot(TaskState.QUEUED, Optional.empty(), ""));
            var logs = (LogService) Proxy.newProxyInstance(LogService.class.getClassLoader(), new Class<?>[]{LogService.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "register" -> new LogTarget(UUID.randomUUID());
                    case "read" -> new LogReadResult(records.get(), Optional.empty());
                    default -> null;
                });
            var context = (ModContext) Proxy.newProxyInstance(ModContext.class.getClassLoader(), new Class<?>[]{ModContext.class},
                (proxy, method, args) -> {
                    assertEquals("logs", method.getName());
                    return logs;
                });
            TaskHandle<Void> handle = new TaskHandle<>() {
                public UUID id() {
                    return id;
                }

                public TaskSnapshot snapshot() {
                    return snapshot.get();
                }

                public CompletionStage<OperationResult<Void>> completion() {
                    return new CompletableFuture<>();
                }

                public void requestCancel() {
                }
            };
            var created = new AtomicReference<DeploymentTaskFeedback>();
            SwingUtilities.invokeAndWait(() -> {
                var value = new DeploymentTaskFeedback(new DeploymentDiagnostics(context), worker, () -> {
                }, new DeploymentConfig.View(60000, 17));
                value.additionalDetails(() -> {
                    assertTrue(SwingUtilities.isEventDispatchThread());
                    return "HOST_RAW_DIAGNOSTIC";
                });
                value.presentationLookup(ignored -> {
                    assertFalse(SwingUtilities.isEventDispatchThread());
                    return projection.get();
                });
                value.interventionNavigation(navigated::set);
                value.watch(handle);
                created.set(value);
            });
            feedback = created.get();
            drain();
        }

        TaskSnapshot snapshot(TaskState state, Optional<TaskResult> outcome, String progress) {
            var started = Instant.parse("2026-10-01T00:00:00Z");
            return new TaskSnapshot(id, "deployment", "1", type, state, outcome, progress, started,
                state == TaskState.ENDED ? Optional.of(started.plusSeconds(1)) : Optional.empty(), List.of());
        }

        void refresh(TaskState state, Optional<TaskResult> outcome, String progress) throws Exception {
            drain();
            snapshot.set(snapshot(state, outcome, progress));
            SwingUtilities.invokeAndWait(() -> feedback.configure(Locale.SIMPLIFIED_CHINESE, false));
            drain();
        }

        void newOriginalTask(String progress) throws Exception {
            var previous = snapshot.get();
            UUID nextId = UUID.randomUUID();
            snapshot.set(new TaskSnapshot(nextId, previous.ownerId(), previous.ownerVersion(), type, TaskState.RUNNING,
                Optional.empty(), progress, previous.startedAt(), Optional.empty(), List.of()));
            TaskHandle<Void> next = new TaskHandle<>() {
                public UUID id() {
                    return nextId;
                }

                public TaskSnapshot snapshot() {
                    return snapshot.get();
                }

                public CompletionStage<OperationResult<Void>> completion() {
                    return new CompletableFuture<>();
                }

                public void requestCancel() {
                }
            };
            SwingUtilities.invokeAndWait(() -> feedback.watch(next));
            drain();
        }

        LogReadResult.Record stage(String stage) {
            return new LogReadResult.Record(UUID.randomUUID(), Instant.now(), new LogEvent(LogLevel.INFO, "DEPLOYMENT_STAGE", stage,
                Optional.empty(), Optional.of(id), Optional.of(id), Optional.empty()), false);
        }

        void drain() throws Exception {
            worker.submit(() -> {
            }).get(5, TimeUnit.SECONDS);
            SwingUtilities.invokeAndWait(() -> {
            });
        }

        void await(Callable<Boolean> condition) throws Exception {
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (!condition.call() && System.nanoTime() < until) Thread.sleep(10);
            assertTrue(condition.call());
        }

        String label(String suffix) throws Exception {
            return onEdt(() -> ((JLabel) named(suffix)).getText());
        }

        String text(String suffix) throws Exception {
            return onEdt(() -> ((JTextArea) named(suffix)).getText());
        }

        boolean detailsVisible() throws Exception {
            return onEdt(() -> named("details").getParent().getParent().getParent().isVisible());
        }

        JButton button(String suffix) {
            return (JButton) named(suffix);
        }

        JProgressBar progress() throws Exception {
            return onEdt(() -> (JProgressBar) named("progress"));
        }

        Component named(String suffix) {
            return find(feedback.component(), "deployment.task.feedback." + suffix);
        }

        <T> T onEdt(Callable<T> supplier) throws Exception {
            var result = new AtomicReference<T>();
            SwingUtilities.invokeAndWait(() -> {
                try {
                    result.set(supplier.call());
                } catch (Exception failure) {
                    throw new AssertionError(failure);
                }
            });
            return result.get();
        }

        public void close() throws Exception {
            SwingUtilities.invokeAndWait(feedback::close);
            worker.shutdown();
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private static Component find(Component component, String name) {
        if (name.equals(component.getName())) return component;
        if (component instanceof Container container) for (var child : container.getComponents()) {
            var result = find(child, name);
            if (result != null) return result;
        }
        return null;
    }
}
