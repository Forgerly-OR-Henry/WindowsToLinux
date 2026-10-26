package gold.debug.windowstolinux.mod.deployment.ui.standard;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.Component;
import java.awt.Container;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import javax.swing.*;
import javax.swing.text.JTextComponent;

import gold.debug.windowstolinux.app.service.task.PlatformInterventionService;
import gold.debug.windowstolinux.app.ui.language.MessageCatalog;
import gold.debug.windowstolinux.app.ui.task.TaskInterventionPresenter;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.model.TaskRequest;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.appapi.task.run.TaskHandle;
import gold.debug.windowstolinux.appapi.ui.UiService;
import gold.debug.windowstolinux.appapi.ui.page.UiEnvironment;
import gold.debug.windowstolinux.mod.deployment.DeploymentRuntimeFixture;
import gold.debug.windowstolinux.mod.deployment.DeploymentStandardEntryFixture;
import gold.debug.windowstolinux.mod.deployment.config.DeploymentConfigLoader;
import gold.debug.windowstolinux.mod.deployment.service.core.model.build.DeploymentBuildLimits;
import gold.debug.windowstolinux.mod.deployment.service.core.model.project.kind.DeploymentProjectKind;
import gold.debug.windowstolinux.mod.deployment.service.core.publish.DeploymentRefFlow;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.contract.DeploymentStandardInputs;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DeploymentRequiredRuntimeFormTest extends DeploymentRuntimeFixture {
    @ParameterizedTest
    @ValueSource(strings = {"missing-version", "invalid-version", "invalid-port", "invalid-configuration", "invalid-build-limit"})
    void necessaryInputsAreCorrectedInTheOriginalHostWindowWithoutOpeningProtectedFields(String scenario) throws Exception {
        enable();
        var owner = new AtomicReference<JFrame>();
        var page = new AtomicReference<DeploymentStandardPage>();
        var presenter = new AtomicReference<TaskInterventionPresenter>();
        var handle = new AtomicReference<TaskHandle<Void>>();
        var reviewed = new AtomicReference<OperationResult<Map<String, DeploymentStandardInputs.Answers>>>();
        var values = new LinkedHashMap<>(Map.ofEntries(Map.entry("version", "22"), Map.entry("port", "8080"),
            Map.entry("healthMode", "HTTP"), Map.entry("exposure", "EXTERNAL"), Map.entry("accessUrl", "http://example.invalid:8080/"),
            Map.entry("executionMode", "DAEMON"), Map.entry("configuration", "PORT=8080"), Map.entry("secrets", "db-password:1"),
            Map.entry("databaseMode", "NONE"), Map.entry("databaseDetails", ""), Map.entry("adapterRisk", "true"),
            Map.entry("maxProcesses", "17")));
        switch (scenario) {
            case "missing-version" -> values.remove("version");
            case "invalid-version" -> values.put("version", "source-version-unresolved");
            case "invalid-port" -> values.put("port", "source-port-unresolved");
            case "invalid-configuration" -> values.put("configuration", "missing-equals");
            case "invalid-build-limit" -> values.put("timeoutSeconds", "not-a-build-limit");
            default -> throw new AssertionError(scenario);
        }
        try (var runtime = open()) {
            start(runtime, true);
            var actual = runtime.modContexts().get(OWNER).context();
            try (var queue = new PlatformInterventionService(runtime.tasks())) {
                var capability = queue.scoped(OWNER, VERSION, () -> true);
                var ui = (UiService) Proxy.newProxyInstance(UiService.class.getClassLoader(), new Class<?>[]{UiService.class},
                    (proxy, method, args) -> {
                        if (method.getName().equals("interventions")) return capability;
                        try {
                            return method.invoke(actual.ui(), args);
                        } catch (InvocationTargetException failure) {
                            throw failure.getCause();
                        }
                    });
                var context = (ModContext) Proxy.newProxyInstance(ModContext.class.getClassLoader(), new Class<?>[]{ModContext.class},
                    (proxy, method, args) -> {
                        if (method.getName().equals("ui")) return ui;
                        if (method.getName().equals("linux") || method.getName().equals("ai"))
                            throw new AssertionError("No model, SSH or deployment call is allowed");
                        try {
                            return method.invoke(actual, args);
                        } catch (InvocationTargetException failure) {
                            throw failure.getCause();
                        }
                    });
                Throwable originalFailure = null;
                try {
                    SwingUtilities.invokeAndWait(() -> {
                        var current = new DeploymentStandardPage(DeploymentStandardEntryFixture.pages(context,
                            new DeploymentStandardEntryFixture(context, new DeploymentRefFlow(context))));
                        var frame = new JFrame("Required runtime information");
                        frame.setContentPane(current.create(new UiEnvironment(Locale.ENGLISH, "LIGHT", false)));
                        frame.setSize(1000, 760);
                        frame.setVisible(true);
                        owner.set(frame);
                        page.set(current);
                        presenter.set(new TaskInterventionPresenter(frame, MessageCatalog.forLanguageTag("en"), queue));
                    });
                    long lookupDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                    while (!runtime.tasks().ownerBlockers(OWNER).isEmpty() && System.nanoTime() < lookupDeadline)
                        Thread.sleep(20);
                    assertTrue(runtime.tasks().ownerBlockers(OWNER).isEmpty(), "Initial local preference and server queries must settle before this controlled task");
                    var prompt = new DeploymentStandardInputs.Prompt("web", "internal-application-id", DeploymentProjectKind.NODE_SERVICE, values, List.of());
                    handle.set(actual.tasks().<Void>submit(new TaskRequest(DeploymentRefFlow.TASK_TYPE,
                        "Controlled required runtime information", List.of(), true), task -> {
                        capability.visible(task.id(), true);
                        var result = new DeploymentStandardPageReviewer(page.get(), page.get().viewGeneration())
                            .review(new ServerId("internal-server-id"), "b".repeat(64), List.of(prompt), task);
                        reviewed.set(result);
                        return new OperationResult<>(result.outcome(), Optional.empty(), result.problem());
                    }));
                    if (scenario.equals("invalid-configuration") || scenario.equals("invalid-build-limit")) {
                        var result = handle.get().completion().toCompletableFuture().get(10, TimeUnit.SECONDS);
                        assertEquals(TaskResult.FAILED, result.outcome());
                        assertTrue(reviewed.get().value().isEmpty());
                        assertEquals("DEPLOYMENT_AUTOMATIC_INPUT_INCOMPLETE", result.problem().orElseThrow().eventCode());
                        assertEquals(0, queue.count(handle.get().id()), "Protected or unidentifiable fields cannot create a speculative form");
                        SwingUtilities.invokeAndWait(() -> assertNull(hostDialog(owner.get())));
                        return;
                    }
                    await(() -> hostDialog(owner.get()) != null);
                    var dialog = new AtomicReference<JDialog>();
                    var field = new AtomicReference<JTextField>();
                    String key = scenario.equals("invalid-port") ? "port" : "version";
                    SwingUtilities.invokeAndWait(() -> {
                        dialog.set(hostDialog(owner.get()));
                        field.set((JTextField) named(dialog.get(), "deployment.runtime.web/" + key));
                        assertNotNull(field.get());
                        assertFalse(visibleText(dialog.get()).contains("internal-application-id"));
                        assertTrue(visibleText(dialog.get()).contains(page.get().text("automatic.completePrompt")));
                        assertFalse(named(dialog.get(), "deployment.review.technical").isVisible());
                        for (String protectedKey : List.of("configuration", "secrets", "databaseMode", "databaseDetails", "timeoutSeconds",
                            "maxProcesses", "maxWorkspaceBytes", "adapterRisk", "containerRisk"))
                            assertNull(named(dialog.get(), "deployment.runtime.web/" + protectedKey), protectedKey);
                    });
                    for (String invalid : List.of("still-not-a-number", key.equals("version") ? "-1" : "0")) {
                        SwingUtilities.invokeAndWait(() -> {
                            field.get().setText(invalid);
                            ((JButton) named(dialog.get(), "task.intervention.accept")).doClick(0);
                            assertSame(dialog.get(), hostDialog(owner.get()));
                            assertSame(field.get(), named(dialog.get(), "deployment.runtime.web/" + key));
                            assertEquals(invalid, field.get().getText());
                            assertEquals(page.get().text("invalidForm"), ((JLabel) named(dialog.get(), "deployment.runtime.completion.error")).getText());
                        });
                        assertEquals(1, queue.count(handle.get().id()));
                        assertFalse(handle.get().completion().toCompletableFuture().isDone());
                    }
                    String corrected = key.equals("port") ? "8080" : "22";
                    SwingUtilities.invokeAndWait(() -> {
                        field.get().setText(corrected);
                        ((JButton) named(dialog.get(), "task.intervention.accept")).doClick(0);
                    });
                    var result = handle.get().completion().toCompletableFuture().get(10, TimeUnit.SECONDS);
                    assertEquals(TaskResult.SUCCEEDED, result.outcome(), result.toString());
                    var answers = reviewed.get().value().orElseThrow();
                    assertEquals(Set.of("web"), answers.keySet());
                    var answer = answers.get("web");
                    var expected = new LinkedHashMap<>(values);
                    expected.put(key, corrected);
                    assertEquals(expected, answer.values(), "Only the identified necessary runtime field can change");
                    var defaults = DeploymentConfigLoader.current().build().limits();
                    assertEquals(new DeploymentBuildLimits(defaults.timeoutSeconds(), 17, defaults.maxMemoryMiB(), defaults.maxOutputBytes(),
                        defaults.maxWorkspaceBytes(), false, defaults.hostReserveBytes()), answer.limits());
                    assertTrue(answer.files().isEmpty());
                    assertTrue(answer.adapterRisk());
                    assertFalse(answer.containerRisk());
                    assertEquals("AUTO", answer.workspaceRequest().mode().name());
                    assertTrue(answer.workspaceSelection().isEmpty());
                    assertEquals(0, queue.count(handle.get().id()));
                } catch (Exception | Error failed) {
                    originalFailure = failed;
                    throw failed;
                } finally {
                    try {
                        queue.withdrawAll();
                        if (handle.get() != null && handle.get().snapshot().endedAt().isEmpty())
                            handle.get().requestCancel();
                        SwingUtilities.invokeAndWait(() -> {
                            if (presenter.get() != null) presenter.get().close();
                            if (page.get() != null) page.get().close();
                            if (owner.get() != null) owner.get().dispose();
                        });
                        if (handle.get() != null)
                            handle.get().completion().toCompletableFuture().get(10, TimeUnit.SECONDS);
                    } catch (Exception cleanup) {
                        if (originalFailure != null) originalFailure.addSuppressed(cleanup);
                        else throw cleanup;
                    }
                }
            }
        }
    }

    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        var observed = new boolean[1];
        do {
            SwingUtilities.invokeAndWait(() -> observed[0] = condition.getAsBoolean());
            if (observed[0]) return;
            Thread.sleep(20);
        } while (System.nanoTime() < deadline);
        fail("The original task's required-information window did not open");
    }

    private static JDialog hostDialog(JFrame owner) {
        return Arrays.stream(owner.getOwnedWindows()).filter(JDialog.class::isInstance).map(JDialog.class::cast)
            .filter(dialog -> dialog.isShowing() && "task.intervention.dialog".equals(dialog.getName())).findFirst().orElse(null);
    }

    private static Component named(Component component, String name) {
        if (name.equals(component.getName())) return component;
        if (component instanceof Container container) for (var child : container.getComponents()) {
            var result = named(child, name);
            if (result != null) return result;
        }
        return null;
    }

    private static String visibleText(Component component) {
        if (!component.isShowing()) return "";
        String result = component instanceof JTextComponent text ? text.getText() : component instanceof JLabel label ? label.getText() : "";
        if (component instanceof Container container)
            for (var child : container.getComponents()) result += "\n" + visibleText(child);
        return result;
    }
}
