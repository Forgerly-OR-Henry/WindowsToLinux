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
import gold.debug.windowstolinux.appapi.task.model.TaskRequest;
import gold.debug.windowstolinux.appapi.task.result.*;
import gold.debug.windowstolinux.appapi.ui.page.UiEnvironment;
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
