package gold.debug.windowstolinux.mod.deployment.ui.standard;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.*;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.*;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.*;
import javax.swing.text.JTextComponent;

import gold.debug.windowstolinux.app.service.task.PlatformInterventionService;
import gold.debug.windowstolinux.app.ui.language.MessageCatalog;
import gold.debug.windowstolinux.app.ui.task.TaskInterventionPresenter;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.model.TaskRequest;
import gold.debug.windowstolinux.appapi.task.model.TaskState;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.appapi.ui.UiService;
import gold.debug.windowstolinux.appapi.ui.page.UiEnvironment;
import gold.debug.windowstolinux.mod.deployment.DeploymentRuntimeFixture;
import gold.debug.windowstolinux.mod.deployment.DeploymentStandardEntryFixture;
import gold.debug.windowstolinux.mod.deployment.service.core.model.language.SourceLanguageType;
import gold.debug.windowstolinux.mod.deployment.service.core.model.project.kind.DeploymentProjectKind;
import gold.debug.windowstolinux.mod.deployment.service.core.publish.DeploymentRefFlow;
import gold.debug.windowstolinux.mod.deployment.service.deploy.analyze.part.DiscoveredProjectPart;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.contract.DeploymentStandardInputs;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Uses the actual host review window and one original task without a remote or model call. */
class DeploymentOrdinaryRuntimeFormTest extends DeploymentRuntimeFixture {
    @ParameterizedTest
    @ValueSource(strings = {"port", "entrypoint", "arguments"})
    void automaticReviewHasOneFinalWindowAndInvalidLaunchEditsStayThere(String invalidField) throws Exception {
        enable();
        var owner = new AtomicReference<JFrame>();
        var page = new AtomicReference<DeploymentStandardPage>();
        var presenter = new AtomicReference<TaskInterventionPresenter>();
        try (var runtime = open()) {
            start(runtime, true);
            var actual = runtime.modContexts().get(OWNER).context();
            try (var interventions = new PlatformInterventionService(runtime.tasks())) {
                var capability = interventions.scoped(OWNER, VERSION, () -> true);
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
                            throw new AssertionError("No remote or model call is allowed");
                        try {
                            return method.invoke(actual, args);
                        } catch (InvocationTargetException failure) {
                            throw failure.getCause();
                        }
                    });
                SwingUtilities.invokeAndWait(() -> {
                    var view = new DeploymentStandardPage(DeploymentStandardEntryFixture.pages(context,
                        new DeploymentStandardEntryFixture(context, new DeploymentRefFlow(context))));
                    var frame = new JFrame("Merged ordinary review");
                    frame.setContentPane(view.create(new UiEnvironment(Locale.ENGLISH, "LIGHT", false)));
                    frame.pack();
                    frame.setVisible(true);
                    page.set(view);
                    owner.set(frame);
                    presenter.set(new TaskInterventionPresenter(frame, MessageCatalog.forLanguageTag("en"), interventions));
                });
                var stage = new AtomicInteger();
                var resultAnswers = new AtomicReference<Map<String, DeploymentStandardInputs.Answers>>();
                var prompt = prompt();
                var handle = actual.tasks().<Void>submit(new TaskRequest(DeploymentRefFlow.TASK_TYPE, "Merged review", List.of(), true), task -> {
                    capability.visible(task.id(), true);
                    var reviewer = new DeploymentStandardPageReviewer(page.get(), page.get().viewGeneration());
                    var selection = new DeploymentStandardInputs.Selection("internal-application-id",
                        Map.of("web", new DeploymentStandardInputs.Choice(DeploymentProjectKind.NODE_SERVICE, Set.of())), Optional.of("web"));
                    assertEquals(TaskResult.SUCCEEDED, reviewer.select(new ServerId("internal-server-id"), "source",
                        List.of(new DiscoveredProjectPart("web", Path.of("private-source-folder"), List.of(DeploymentProjectKind.NODE_SERVICE))),
                        selection, Map.of("web", Set.of(SourceLanguageType.TYPESCRIPT)), task).outcome());
                    var reviewed = reviewer.review(new ServerId("internal-server-id"), "source", List.of(prompt), task);
                    assertEquals(TaskResult.SUCCEEDED, reviewed.outcome());
                    assertEquals(0, interventions.count(task.id()), "Default inputs need no separate question");
                    stage.set(1);
                    var confirmed = reviewer.confirmAnswers(new ServerId("internal-server-id"), "source", Map.of("web", "a".repeat(64)),
                        reviewed.value().orElseThrow(), Map.of(), Optional.empty(), task);
                    resultAnswers.set(confirmed.value().orElseThrow());
                    return new OperationResult<>(confirmed.outcome(), Optional.empty(), confirmed.problem());
                });
                var dialog = awaitDialog(owner.get());
                assertEquals(1, stage.get());
                assertEquals(1, interventions.count(handle.id()));
                assertEquals(TaskState.RUNNING, handle.snapshot().state());
                var originalField = new AtomicReference<JTextField>();
                SwingUtilities.invokeAndWait(() -> {
                    String visible = visibleText(dialog);
                    for (String technical : List.of("internal-application-id", "internal-server-id", "private-source-folder", "npm --prefix"))
                        assertFalse(visible.contains(technical), technical);
                    ((JToggleButton) named(dialog, "deployment.launch.web.edit")).doClick();
                    var field = (JTextField) named(dialog, "deployment.launch.web." + invalidField);
                    originalField.set(field);
                    field.setText(switch (invalidField) {
                        case "port" -> "0";
                        case "entrypoint" -> "../escape.mjs";
                        default -> "\"unterminated";
                    });
                    ((JButton) named(dialog, "task.intervention.accept")).doClick();
                });
                assertFalse(handle.completion().toCompletableFuture().isDone());
                assertEquals(1, interventions.count(handle.id()));
                SwingUtilities.invokeAndWait(() -> {
                    assertTrue(dialog.isVisible());
                    assertSame(originalField.get(), named(dialog, "deployment.launch.web." + invalidField));
                    assertTrue(visibleText(dialog).contains(page.get().text("automatic.launchInvalid")));
                    originalField.get().setText(switch (invalidField) {
                        case "port" -> "8080";
                        case "entrypoint" -> "src/server.mjs";
                        default -> "--mode production";
                    });
                    var port = (JTextField) named(dialog, "deployment.launch.web.port");
                    var url = (JTextField) named(dialog, "deployment.launch.web.accessUrl");
                    port.setText("9001");
                    assertEquals("http://example.invalid:9001/", url.getText());
                    url.setText("https://custom.example.invalid/");
                    port.setText("9002");
                    assertEquals("https://custom.example.invalid/", url.getText());
                    ((JButton) named(dialog, "task.intervention.accept")).doClick();
                });
                var completed = handle.completion().toCompletableFuture().get(10, TimeUnit.SECONDS);
                assertEquals(TaskResult.SUCCEEDED, completed.outcome(), completed.toString());
                assertEquals(0, interventions.count(handle.id()));
                assertEquals("9002", resultAnswers.get().get("web").values().get("port"));
                assertEquals("https://custom.example.invalid/", resultAnswers.get().get("web").values().get("accessUrl"));
                SwingUtilities.invokeAndWait(() -> {
                    assertFalse(dialog.isVisible());
                    presenter.get().close();
                    page.get().close();
                    owner.get().dispose();
                });
            }
        } finally {
            SwingUtilities.invokeAndWait(() -> {
                if (presenter.get() != null) presenter.get().close();
                if (page.get() != null) page.get().close();
                if (owner.get() != null) owner.get().dispose();
            });
        }
    }

    private static DeploymentStandardInputs.Prompt prompt() {
        return new DeploymentStandardInputs.Prompt("web", "internal-application-id", DeploymentProjectKind.NODE_SERVICE,
            Map.of("version", "22", "port", "8080", "accessUrl", "http://example.invalid:8080/", "exposure", "EXTERNAL",
                "healthMode", "HTTP", "databaseMode", "NONE", "executionMode", "DAEMON", "configuration", "PORT=8080"), List.of());
    }

    private static JDialog awaitDialog(JFrame owner) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        var result = new AtomicReference<JDialog>();
        while (result.get() == null && System.nanoTime() < until) {
            SwingUtilities.invokeAndWait(() -> {
                for (var window : owner.getOwnedWindows())
                    if (window instanceof JDialog dialog && dialog.isVisible() && "task.intervention.dialog".equals(dialog.getName()))
                        result.set(dialog);
            });
            if (result.get() == null) Thread.sleep(20);
        }
        assertNotNull(result.get(), "The single host confirmation window must open");
        return result.get();
    }

    private static Component named(Component component, String name) {
        if (name.equals(component.getName())) return component;
        if (component instanceof Container container) for (var child : container.getComponents()) {
            var found = named(child, name);
            if (found != null) return found;
        }
        return null;
    }

    private static String visibleText(Component component) {
        if (!component.isShowing()) return "";
        var text = component instanceof JTextComponent input ? input.getText()
            : component instanceof JLabel label ? label.getText() : "";
        if (component instanceof Container container)
            for (var child : container.getComponents()) text += "\n" + visibleText(child);
        return text;
    }
}
