package gold.debug.windowstolinux.mod.deployment.ui.flow;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import javax.swing.*;

import gold.debug.windowstolinux.app.db.server.StoredServerProfile;
import gold.debug.windowstolinux.app.service.server.ServerProfile;
import gold.debug.windowstolinux.app.service.task.PlatformInterventionService;
import gold.debug.windowstolinux.app.ui.language.MessageCatalog;
import gold.debug.windowstolinux.app.ui.task.TaskInterventionPresenter;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.appapi.task.run.TaskHandle;
import gold.debug.windowstolinux.appapi.task.model.TaskRequest;
import gold.debug.windowstolinux.appapi.task.result.*;
import gold.debug.windowstolinux.appapi.ui.page.UiEnvironment;
import gold.debug.windowstolinux.appapi.ui.page.UiNavigationRequest;
import gold.debug.windowstolinux.appapi.ui.UiService;
import gold.debug.windowstolinux.appapi.ui.intervention.UiInterventionService;
import gold.debug.windowstolinux.appapi.ui.intervention.UiInterventionSpec;
import gold.debug.windowstolinux.appapi.ui.intervention.UiInterventionForm;
import gold.debug.windowstolinux.mod.deployment.*;
import gold.debug.windowstolinux.mod.deployment.db.setting.DeploymentPreferenceStore;
import gold.debug.windowstolinux.mod.deployment.service.agent.*;
import gold.debug.windowstolinux.mod.deployment.service.core.publish.DeploymentRefFlow;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.DeploymentStandardPageService;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.contract.DeploymentStandardInputs;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Exercises the actual flow and original metadata tasks, without deployment or network effects. */
class DeploymentFlowPageTest extends DeploymentRuntimeFixture {
    private Path selected;

    @Override
    protected Path selectedSourceDirectory() {
        return selected;
    }

    @ParameterizedTest
    @ValueSource(strings = {"STATIC", "ASSISTED", "AGENT"})
    void confirmedModeRestoresWithoutCreatingDeploymentOrSelectingFirstServer(String mode) throws Exception {
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            runtime.servers().saveServerProfile(ServerProfile.fromStored(new StoredServerProfile("flow-server", "127.0.0.1", 22, "fixture", "fixture-password")), "fixture-password".toCharArray());
            var context = runtime.modContexts().get(OWNER).context();
            var expected = new DeploymentPreferenceStore.Selection(mode, "MANUAL_REVIEW", true);
            var seed = context.tasks().submit(new TaskRequest("seed-flow-mode", "Seed actual flow preference", java.util.List.of(), true), task -> {
                new DeploymentPreferenceStore(context.db()).save(task, expected);
                return new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(expected), Optional.empty());
            });
            seed.completion().toCompletableFuture().get(15, TimeUnit.SECONDS);
            var service = DeploymentStandardEntryFixture.pages(context, new DeploymentStandardEntryFixture(context, new DeploymentRefFlow(context)));
            var page = new DeploymentFlowPage(service, new DeploymentAgentPageService(context, new DeploymentAgentWorkflow(context)));
            var mounted = new AtomicReference<JComponent>();
            try {
                edt(() -> mounted.set(page.create(new UiEnvironment(Locale.ENGLISH, "LIGHT", false))));
                await(() -> control(mounted.get(), "deployment.flow.mode", JButton.class).isEnabled());
                edt(() -> {
                    assertEquals(mode, draft(page).mode);
                    assertNull(draft(page).server(), "Loading configured servers is not target selection");
                    assertFalse(control(mounted.get(), "deployment.flow.start", JButton.class).isEnabled());
                    assertEquals(1, count(mounted.get(), "deployment.local.choose"));
                    assertEquals(1, count(mounted.get(), "deployment.projects"));
                });
                var reread = service.preferences().completion().toCompletableFuture().get(15, TimeUnit.SECONDS);
                assertEquals(expected, reread.value().orElseThrow());
                assertTrue(service.publications().isEmpty());
            } finally {
                edt(page::close);
            }
        }
    }

    @Test
    void completedLocalSelectionAndTargetSurviveModeNavigationLocaleAndLockWithoutASecondGrant() throws Exception {
        selected = Files.createDirectories(root.resolve("flow-source"));
        Files.writeString(selected.resolve("main.txt"), "actual source contents");
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            runtime.servers().saveServerProfile(ServerProfile.fromStored(new StoredServerProfile("flow-retained", "127.0.0.1", 22, "fixture", "fixture-password")), "fixture-password".toCharArray());
            var context = runtime.modContexts().get(OWNER).context();
            var service = DeploymentStandardEntryFixture.pages(context, new DeploymentStandardEntryFixture(context, new DeploymentRefFlow(context)));
            var page = new DeploymentFlowPage(service, new DeploymentAgentPageService(context, new DeploymentAgentWorkflow(context)));
            var mounted = new AtomicReference<JComponent>();
            try {
                edt(() -> mounted.set(page.create(new UiEnvironment(Locale.ENGLISH, "LIGHT", false))));
                await(() -> control(mounted.get(), "deployment.local.choose", JButton.class).isEnabled());
                edt(() -> control(mounted.get(), "deployment.local.choose", JButton.class).doClick(0));
                await(() -> draft(page).local.selection().isPresent() && draft(page).servers.getItemCount() == 1);
                edt(() -> assertFalse(control(mounted.get(), "deployment.projects", JComboBox.class).isEnabled(), "An occupied flow source cannot be replaced from the project picker"));
                var original = new AtomicReference<DeploymentFlowDraft>();
                edt(() -> {
                    original.set(draft(page));
                    draft(page).servers.setSelectedIndex(0);
                    draft(page).name.setText("Retained source");
                    for (String destination : java.util.List.of("agent-deployment", "static-deployment", "agent-deployment")) {
                        page.restoreNavigation(destination);
                        assertSame(original.get(), draft(page));
                        assertEquals(selected.toUri(), draft(page).local.selection().orElseThrow().location());
                        assertEquals("flow-retained", draft(page).server().ref().id());
                        assertEquals("Retained source", draft(page).name.getText());
                    }
                    page.environmentChanged(new UiEnvironment(Locale.SIMPLIFIED_CHINESE, "DARK", true));
                    assertFalse(control(mounted.get(), "deployment.flow.mode", JButton.class).isEnabled());
                    assertFalse(control(mounted.get(), "deployment.flow.chooseServer", JButton.class).isEnabled());
                    page.environmentChanged(new UiEnvironment(Locale.ENGLISH, "LIGHT", false));
                    assertSame(original.get(), draft(page));
                    assertEquals(selected.toUri(), draft(page).local.selection().orElseThrow().location());
                    assertFalse(draft(page).local.importSelection(new StringSelection(selected.toString())), "Replacing an occupied source requires explicit clear");
                    control(mounted.get(), "deployment.local.clear", JButton.class).doClick(0);
                });
                await(() -> control(mounted.get(), "deployment.projects", JComboBox.class).isEnabled());
                edt(() -> assertTrue(draft(page).local.selection().isEmpty()));
                await(() -> !draft(page).local.busy());
                assertTrue(service.publications().isEmpty());
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
                while (scalar(runtime, "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL") != 0 && System.nanoTime() < deadline)
                    Thread.sleep(20);
                assertEquals(0, scalar(runtime, "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL"));
            } finally {
                edt(page::close);
            }
        }
    }

    @Test
    void acceptedOriginalTaskFreezesSourceTargetAndModeUntilActualCompletion() throws Exception {
        selected = Files.createDirectories(root.resolve("frozen-source"));
        Files.writeString(selected.resolve("main.txt"), "local source");
        enable();
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        try (var runtime = open()) {
            start(runtime, true);
            runtime.servers().saveServerProfile(ServerProfile.fromStored(new StoredServerProfile("frozen-target", "127.0.0.1", 22, "fixture", "fixture-password")), "fixture-password".toCharArray());
            var context = runtime.modContexts().get(OWNER).context();
            var original = new AtomicReference<gold.debug.windowstolinux.appapi.task.run.TaskHandle<Void>>();
            var entry = new DeploymentStandardInputs.Entry() {
                public gold.debug.windowstolinux.appapi.task.run.TaskHandle<Void> submit(gold.debug.windowstolinux.appapi.server.model.ServerId server,
                                                                                         DeploymentStandardInputs.Draft draft, Optional<DeploymentStandardInputs.GitSource> git, DeploymentStandardInputs.Reviewer reviewer,
                                                                                         String approval, boolean remember) throws ApiException {
                    var handle = context.tasks().<Void>submit(new TaskRequest("flow-admission-gate", "Observe actual admitted original task", java.util.List.of(server.id()), true), task -> {
                        entered.countDown();
                        assertTrue(release.await(15, TimeUnit.SECONDS));
                        return new OperationResult<>(TaskResult.SUCCEEDED, Optional.empty(), Optional.empty());
                    });
                    original.set(handle);
                    return handle;
                }

                public void validateReview(DeploymentStandardInputs.Prompt prompt, DeploymentStandardInputs.Answers answer) {
                }
            };
            var page = new DeploymentFlowPage(new DeploymentStandardPageService(context, entry, entry), new DeploymentAgentPageService(context, new DeploymentAgentWorkflow(context)));
            var mounted = new AtomicReference<JComponent>();
            try {
                edt(() -> {
                    page.restoreNavigation("static-deployment");
                    mounted.set(page.create(new UiEnvironment(Locale.ENGLISH, "LIGHT", false)));
                });
                await(() -> control(mounted.get(), "deployment.local.choose", JButton.class).isEnabled());
                edt(() -> control(mounted.get(), "deployment.local.choose", JButton.class).doClick(0));
                await(() -> draft(page).ready() && draft(page).servers.getItemCount() == 1);
                edt(() -> draft(page).servers.setSelectedIndex(0));
                await(() -> control(mounted.get(), "deployment.flow.start", JButton.class).isEnabled());
                edt(() -> control(mounted.get(), "deployment.flow.start", JButton.class).doClick(0));
                assertTrue(entered.await(15, TimeUnit.SECONDS));
                await(() -> draft(page).frozen);
                edt(() -> {
                    assertFalse(control(mounted.get(), "deployment.flow.mode", JButton.class).isEnabled());
                    assertFalse(control(mounted.get(), "deployment.flow.chooseServer", JButton.class).isEnabled());
                    assertFalse(control(mounted.get(), "deployment.flow.sourceAccept", JButton.class).isEnabled());
                    assertFalse(control(mounted.get(), "deployment.flow.approval", JButton.class).isVisible(), "Standard mode has no AI approval selector");
                    control(mounted.get(), "deployment.local.clear", JButton.class).doClick(0);
                    page.restoreNavigation("agent-deployment");
                    assertEquals("STATIC", draft(page).mode);
                    assertEquals(selected.toUri(), draft(page).local.selection().orElseThrow().location());
                    assertEquals("frozen-target", draft(page).server().ref().id());
                });
                release.countDown();
                assertEquals(TaskResult.SUCCEEDED, original.get().completion().toCompletableFuture().get(15, TimeUnit.SECONDS).outcome());
                await(() -> !draft(page).frozen && control(mounted.get(), "deployment.flow.mode", JButton.class).isEnabled());
            } finally {
                release.countDown();
                if (original.get() != null) original.get().completion().toCompletableFuture().get(15, TimeUnit.SECONDS);
                edt(page::close);
            }
        }
    }

    @ParameterizedTest(name = "hideOwner={0}")
    @ValueSource(booleans = {false, true})
    void explicitOriginalTaskNavigationSurvivesObservationAndOnlyItsVisiblePageOpensQuestions(boolean hideOwner) throws Exception {
        selected = Files.createDirectories(root.resolve("navigation-source"));
        Files.writeString(selected.resolve("main.txt"), "local source without a deployment effect");
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            runtime.servers().saveServerProfile(ServerProfile.fromStored(new StoredServerProfile("navigation-target", "127.0.0.1", 22,
                "fixture", "fixture-password")), "fixture-password".toCharArray());
            var actual = runtime.modContexts().get(OWNER).context();
            try (var queue = new PlatformInterventionService(runtime.tasks())) {
                var capability = queue.scoped(OWNER, VERSION, () -> true);
                var ui = (UiService) java.lang.reflect.Proxy.newProxyInstance(UiService.class.getClassLoader(), new Class<?>[]{UiService.class},
                    (proxy, method, args) -> {
                        if (method.getName().equals("interventions")) return capability;
                        try {
                            return method.invoke(actual.ui(), args);
                        } catch (java.lang.reflect.InvocationTargetException failure) {
                            throw failure.getCause();
                        }
                    });
                var context = (ModContext) java.lang.reflect.Proxy.newProxyInstance(ModContext.class.getClassLoader(), new Class<?>[]{ModContext.class},
                    (proxy, method, args) -> {
                        if (method.getName().equals("ui")) return ui;
                        try {
                            return method.invoke(actual, args);
                        } catch (java.lang.reflect.InvocationTargetException failure) {
                            throw failure.getCause();
                        }
                    });
                var first = new AtomicReference<TaskHandle<Void>>();
                var second = new AtomicReference<TaskHandle<Void>>();
                var releaseQuestions = new java.util.concurrent.CountDownLatch(1);
                var entry = new DeploymentStandardInputs.Entry() {
                    @Override
                    public TaskHandle<Void> submit(gold.debug.windowstolinux.appapi.server.model.ServerId server,
                                                   DeploymentStandardInputs.Draft draft, Optional<DeploymentStandardInputs.GitSource> git,
                                                   DeploymentStandardInputs.Reviewer reviewer, String approval, boolean remember) throws ApiException {
                        var handle = context.tasks().<Void>submit(new TaskRequest(DeploymentRefFlow.TASK_TYPE,
                            "Controlled original page task A", java.util.List.of(), true), task -> localQuestion(capability, task, "Question A", releaseQuestions));
                        first.set(handle);
                        return handle;
                    }

                    @Override
                    public void validateReview(DeploymentStandardInputs.Prompt prompt, DeploymentStandardInputs.Answers answer) {
                    }
                };
                var page = new DeploymentFlowPage(new DeploymentStandardPageService(context, entry, entry),
                    new DeploymentAgentPageService(context, new DeploymentAgentWorkflow(context)));
                var frame = new AtomicReference<JFrame>();
                var presenter = new AtomicReference<TaskInterventionPresenter>();
                var mounted = new AtomicReference<JComponent>();
                Throwable originalFailure = null;
                try {
                    edt(() -> {
                        page.restoreNavigation("static-deployment");
                        mounted.set(page.create(new UiEnvironment(Locale.ENGLISH, "LIGHT", false)));
                        var owner = new JFrame("Actual flow original-task navigation");
                        owner.setContentPane(mounted.get());
                        owner.setSize(1200, 800);
                        owner.setVisible(true);
                        frame.set(owner);
                        presenter.set(new TaskInterventionPresenter(owner, MessageCatalog.forLanguageTag("en"), queue));
                    });
                    await(() -> control(mounted.get(), "deployment.local.choose", JButton.class).isEnabled());
                    edt(() -> control(mounted.get(), "deployment.local.choose", JButton.class).doClick(0));
                    await(() -> draft(page).ready() && draft(page).servers.getItemCount() == 1);
                    edt(() -> draft(page).servers.setSelectedIndex(0));
                    await(() -> control(mounted.get(), "deployment.flow.start", JButton.class).isEnabled());
                    edt(() -> control(mounted.get(), "deployment.flow.start", JButton.class).doClick(0));
                    await(() -> first.get() != null && queue.taskVisible(first.get().id()) && queue.count(first.get().id()) == 1
                        && hostDialog(frame.get()) != null && hostDialog(frame.get()).getTitle().equals("Question A"));
                    assertEquals("Question A", hostDialog(frame.get()).getTitle());
                    second.set(context.tasks().<Void>submit(new TaskRequest(DeploymentRefFlow.TASK_TYPE,
                        "Controlled original task B", java.util.List.of(), true), task -> localQuestion(capability, task, "Question B", releaseQuestions)));
                    await(() -> queue.count(second.get().id()) == 1);
                    assertFalse(ready(queue, second.get().id()));
                    edt(() -> {
                        if (hideOwner) frame.get().setVisible(false);
                        else mounted.get().setVisible(false);
                    });
                    await(() -> !queue.taskVisible(first.get().id()) && !ready(queue, first.get().id()) && hostDialog(frame.get()) == null);
                    edt(() -> {
                        page.navigate(new UiNavigationRequest<>("intervene-task", UUID.class, Optional.of(second.get().id())));
                        try {
                            capability.show(second.get().id());
                        } catch (ApiException failure) {
                            throw new AssertionError(failure);
                        }
                    });
                    assertFalse(ready(queue, second.get().id()), "A hidden actual page cannot mark its question ready");
                    assertFalse(queue.taskVisible(second.get().id()));
                    edt(() -> {
                        if (hideOwner) frame.get().setVisible(true);
                        else mounted.get().setVisible(true);
                    });
                    await(() -> queue.taskVisible(second.get().id()));
                    edt(() -> {
                        try {
                            capability.show(second.get().id());
                        } catch (ApiException failure) {
                            throw new AssertionError(failure);
                        }
                    });
                    try {
                        await(() -> queue.taskVisible(second.get().id()) && queue.count(second.get().id()) == 1
                            && hostDialog(frame.get()) != null && hostDialog(frame.get()).getTitle().equals("Question B"));
                    } catch (AssertionError missingQuestion) {
                        var evidence = new AtomicReference<String>();
                        edt(() -> evidence.set("Original B opening: hideOwner=" + hideOwner + " visibleA=" + queue.taskVisible(first.get().id())
                            + " visibleB=" + queue.taskVisible(second.get().id()) + " queue=" + queue.pending()
                            + " windows=" + Arrays.stream(frame.get().getOwnedWindows()).map(window ->
                            window.getName() + ":" + (window instanceof JDialog dialog ? dialog.getTitle() : "")
                                + ":showing=" + window.isShowing()).toList()
                            + " focused=" + observedTask(page)
                            + " feedback=" + control(mounted.get(), "deployment.task.feedback.details", JTextArea.class).getText()));
                        throw new AssertionError(evidence.get(), missingQuestion);
                    }
                    edt(() -> {
                        assertFalse(ready(queue, first.get().id()));
                        assertFalse(queue.taskVisible(first.get().id()));
                        assertEquals("Question B", hostDialog(frame.get()).getTitle());
                        assertEquals(1, Arrays.stream(frame.get().getOwnedWindows())
                            .filter(window -> "task.intervention.dialog".equals(window.getName()) && window.isShowing()).count());
                    });
                    await(() -> control(mounted.get(), "deployment.task.feedback.details", JTextArea.class).getText()
                        .startsWith(second.get().id().toString()));
                    edt(() -> assertFalse(control(mounted.get(), "deployment.task.feedback.details", JTextArea.class).getText()
                        .contains(first.get().id().toString()), "Original B details must not include task A diagnostics"));
                    var observed = new java.util.concurrent.CountDownLatch(1);
                    edt(() -> {
                        var afterObservers = new javax.swing.Timer(600, ignored -> observed.countDown());
                        afterObservers.setRepeats(false);
                        afterObservers.start();
                    });
                    assertTrue(observed.await(3, TimeUnit.SECONDS));
                    edt(() -> {
                        assertEquals(second.get().id(), observedTask(page), "Original task A must not reclaim explicit navigation to B");
                        assertTrue(queue.taskVisible(second.get().id()));
                        assertEquals(1, queue.count(second.get().id()));
                        assertFalse(ready(queue, first.get().id()));
                        assertFalse(queue.taskVisible(first.get().id()));
                        var feedback = control(mounted.get(), "deployment.task.feedback.details", JTextArea.class);
                        assertTrue(feedback.getText().startsWith(second.get().id().toString()),
                            "The actual feedback card must observe B rather than current task A: " + feedback.getText());
                        assertFalse(feedback.getText().contains(first.get().id().toString()),
                            "Original B details must remain free of task A diagnostics after observation");
                        control(hostDialog(frame.get()), "task.intervention.accept", JButton.class).doClick(0);
                    });
                    assertEquals(TaskResult.SUCCEEDED, second.get().completion().toCompletableFuture().get(10, TimeUnit.SECONDS).outcome());
                    edt(() -> {
                        page.navigate(new UiNavigationRequest<>("intervene-task", UUID.class, Optional.of(first.get().id())));
                        try {
                            capability.show(first.get().id());
                        } catch (ApiException failure) {
                            throw new AssertionError(failure);
                        }
                    });
                    await(() -> queue.taskVisible(first.get().id()) && queue.count(first.get().id()) == 1
                        && hostDialog(frame.get()) != null && hostDialog(frame.get()).getTitle().equals("Question A"));
                    edt(() -> {
                        assertEquals("Question A", hostDialog(frame.get()).getTitle());
                        control(hostDialog(frame.get()), "task.intervention.accept", JButton.class).doClick(0);
                    });
                    assertEquals(TaskResult.SUCCEEDED, first.get().completion().toCompletableFuture().get(10, TimeUnit.SECONDS).outcome());
                    assertEquals(0, queue.count(first.get().id()));
                    assertEquals(0, queue.count(second.get().id()));
                } catch (Exception | Error failed) {
                    originalFailure = failed;
                    throw failed;
                } finally {
                    releaseQuestions.countDown();
                    edt(() -> {
                        if (presenter.get() != null) presenter.get().close();
                        page.close();
                        if (frame.get() != null) frame.get().dispose();
                    });
                    queue.withdrawAll();
                    for (var original : Arrays.asList(first.get(), second.get())) {
                        if (original == null) continue;
                        try {
                            if (original.snapshot().endedAt().isEmpty()) original.requestCancel();
                            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                            while (!original.completion().toCompletableFuture().isDone() && System.nanoTime() < deadline) {
                                queue.refresh();
                                Thread.sleep(20);
                            }
                            original.completion().toCompletableFuture().get(1, TimeUnit.SECONDS);
                        } catch (Exception cleanup) {
                            var evidence = new AssertionError("Controlled original cleanup failed: " + original.snapshot(), cleanup);
                            if (originalFailure != null) originalFailure.addSuppressed(evidence);
                            else throw evidence;
                        }
                    }
                }
            }
        }
    }

    private static OperationResult<Void> localQuestion(UiInterventionService service, TaskContext task, String title,
                                                       java.util.concurrent.CountDownLatch release) throws Exception {
        var request = service.request(task, new UiInterventionSpec(title, "Local UI decision without deployment authority", "Continue", false),
            () -> new UiInterventionForm<Boolean>() {
                @Override
                public JComponent component() {
                    return new JLabel(title);
                }

                @Override
                public Boolean submit() {
                    return Boolean.TRUE;
                }

                @Override
                public void close() {
                }
            });
        try {
            var decision = request.completion().toCompletableFuture();
            while (release.getCount() > 0 && !task.cancelToken().requested()) {
                try {
                    decision.get(100, TimeUnit.MILLISECONDS);
                    return new OperationResult<>(TaskResult.SUCCEEDED, Optional.empty(), Optional.empty());
                } catch (java.util.concurrent.TimeoutException pending) {
                    // The test can always release its own controlled callback during cleanup.
                }
            }
            return new OperationResult<>(TaskResult.CANCELLED, Optional.empty(), Optional.empty());
        } catch (java.util.concurrent.CancellationException withdrawn) {
            return new OperationResult<>(TaskResult.CANCELLED, Optional.empty(), Optional.empty());
        } catch (java.util.concurrent.ExecutionException withdrawn) {
            if (withdrawn.getCause() instanceof java.util.concurrent.CancellationException)
                return new OperationResult<>(TaskResult.CANCELLED, Optional.empty(), Optional.empty());
            throw withdrawn;
        } finally {
            request.cancel();
        }
    }

    private static boolean ready(PlatformInterventionService queue, UUID original) {
        return queue.pending().stream().anyMatch(item -> item.task().id().equals(original) && item.ready());
    }

    private static JDialog hostDialog(JFrame frame) {
        return Arrays.stream(frame.getOwnedWindows()).filter(JDialog.class::isInstance).map(JDialog.class::cast)
            .filter(window -> window.isShowing() && "task.intervention.dialog".equals(window.getName())).findFirst().orElse(null);
    }

    private static UUID observedTask(DeploymentFlowPage page) {
        try {
            var field = DeploymentFlowPage.class.getDeclaredField("observedTask");
            field.setAccessible(true);
            return (UUID) field.get(page);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static DeploymentFlowDraft draft(DeploymentFlowPage page) {
        try {
            var field = DeploymentFlowPage.class.getDeclaredField("draft");
            field.setAccessible(true);
            return (DeploymentFlowDraft) field.get(page);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static <T extends Component> T control(Container parent, String name, Class<T> type) {
        for (var child : parent.getComponents()) {
            if (name.equals(child.getName())) return type.cast(child);
            if (child instanceof Container nested) {
                var found = control(nested, name, type);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static int count(Container parent, String name) {
        int found = 0;
        for (var child : parent.getComponents()) {
            if (name.equals(child.getName())) found++;
            if (child instanceof Container nested) found += count(nested, name);
        }
        return found;
    }

    private static void edt(Runnable action) throws Exception {
        SwingUtilities.invokeAndWait(action);
    }

    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        var ready = new boolean[1];
        do {
            edt(() -> ready[0] = condition.getAsBoolean());
            if (ready[0]) return;
            Thread.sleep(20);
        } while (System.nanoTime() < deadline);
        fail("Actual unified flow did not reach the required state");
    }
}
