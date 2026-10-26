package gold.debug.windowstolinux.mod.deployment.ui.standard;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.Component;
import java.awt.Container;
import java.awt.event.WindowEvent;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.*;
import javax.swing.text.JTextComponent;

import gold.debug.windowstolinux.app.main.start.PlatformRuntime;
import gold.debug.windowstolinux.app.service.task.PlatformInterventionService;
import gold.debug.windowstolinux.app.ui.language.MessageCatalog;
import gold.debug.windowstolinux.app.ui.task.TaskInterventionPresenter;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.task.model.TaskRequest;
import gold.debug.windowstolinux.appapi.task.model.TaskState;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.appapi.task.run.TaskHandle;
import gold.debug.windowstolinux.appapi.ui.UiService;
import gold.debug.windowstolinux.appapi.ui.intervention.UiInterventionService;
import gold.debug.windowstolinux.appapi.ui.page.UiEnvironment;
import gold.debug.windowstolinux.mod.deployment.DeploymentRuntimeFixture;
import gold.debug.windowstolinux.mod.deployment.DeploymentStandardEntryFixture;
import gold.debug.windowstolinux.mod.deployment.service.core.publish.DeploymentRefFlow;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DeploymentHostedPasswordTest extends DeploymentRuntimeFixture {
    private static final String PURPOSE = "输入连接数据库所需的演示密码";

    private static final String INTERNAL_BINDING = "fixture-internal-binding-id-42";

    private static final String TECHNICAL_LABEL = "Database password; binding=" + INTERNAL_BINDING;

    private static final String UNICODE_FAKE_PASSWORD = "仅供测试-演示密码🔒42";

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void hostedPasswordDefersWithoutRetainingInputAndReturnsOwnedUtf8AfterFreshAcceptance(boolean nativeClose) throws Exception {
        enable();
        var handle = new AtomicReference<TaskHandle<Void>>();
        var received = new AtomicReference<byte[]>();
        try (var runtime = open()) {
            start(runtime, true);
            try (var view = new HostedView(runtime)) {
                var accepted = new AtomicInteger();
                handle.set(view.actual.tasks().submit(new TaskRequest(DeploymentRefFlow.TASK_TYPE,
                    "Hosted password fixture", List.of(), true), task -> {
                    view.capability.visible(task.id(), true);
                    try {
                        received.set(DeploymentStandardPasswordInput.request(view.page, view.page.viewGeneration(), task,
                            PURPOSE, TECHNICAL_LABEL, false, accepted::incrementAndGet));
                        return new OperationResult<>(TaskResult.SUCCEEDED, Optional.empty(), Optional.empty());
                    } catch (Exception failure) {
                        if (task.cancelToken().requested())
                            return new OperationResult<>(TaskResult.CANCELLED, Optional.empty(), Optional.empty());
                        throw failure;
                    }
                }));
                try {
                    var firstDialog = awaitDialog(view.owner);
                    var originalRequest = view.interventions.pending().getFirst().requestId();
                    var firstField = new AtomicReference<JPasswordField>();
                    SwingUtilities.invokeAndWait(() -> {
                        assertTrue(visibleText(firstDialog).contains(PURPOSE));
                        assertFalse(visibleText(firstDialog).contains(INTERNAL_BINDING));
                        assertTrue(allText(firstDialog).contains(INTERNAL_BINDING), "Technical identity remains available in details");
                        var details = textButton(firstDialog, view.page.text("automatic.details"));
                        assertNotNull(details);
                        details.doClick();
                        assertTrue(visibleText(firstDialog).contains(INTERNAL_BINDING));
                        details.doClick();
                        assertFalse(visibleText(firstDialog).contains(INTERNAL_BINDING));
                        var field = (JPasswordField) named(firstDialog, "deployment.intervention.password");
                        assertNotNull(field);
                        firstField.set(field);
                        field.setText("fictional-value-before-deferral");
                        if (nativeClose)
                            firstDialog.dispatchEvent(new WindowEvent(firstDialog, WindowEvent.WINDOW_CLOSING));
                        else ((JButton) named(firstDialog, "task.intervention.later")).doClick();
                        assertPasswordEmpty(field);
                        assertFalse(firstDialog.isVisible());
                    });
                    assertEquals(0, accepted.get());
                    assertFalse(handle.get().completion().toCompletableFuture().isDone());
                    assertEquals(TaskState.RUNNING, handle.get().snapshot().state());
                    assertEquals(1, view.interventions.count(handle.get().id()));
                    assertEquals(originalRequest, view.interventions.pending().getFirst().requestId());
                    assertEquals(1, interactionLeases(runtime, handle.get()));
                    view.capability.show(handle.get().id());
                    var reopened = awaitDialog(view.owner);
                    var acceptedField = new AtomicReference<JPasswordField>();
                    SwingUtilities.invokeAndWait(() -> {
                        var field = (JPasswordField) named(reopened, "deployment.intervention.password");
                        assertNotNull(field);
                        assertNotSame(firstField.get(), field);
                        assertPasswordEmpty(field);
                        assertFalse(visibleText(reopened).contains(INTERNAL_BINDING));
                        acceptedField.set(field);
                        field.setText(UNICODE_FAKE_PASSWORD);
                        ((JButton) named(reopened, "task.intervention.accept")).doClick();
                        assertPasswordEmpty(field);
                    });
                    var result = handle.get().completion().toCompletableFuture().get(10, TimeUnit.SECONDS);
                    assertEquals(TaskResult.SUCCEEDED, result.outcome(), result.toString());
                    var expected = UNICODE_FAKE_PASSWORD.getBytes(StandardCharsets.UTF_8);
                    try {
                        assertArrayEquals(expected, received.get());
                    } finally {
                        Arrays.fill(expected, (byte) 0);
                    }
                    assertEquals(1, accepted.get());
                    assertEquals(TaskState.ENDED, handle.get().snapshot().state());
                    assertEquals(0, interactionLeases(runtime, handle.get()));
                    assertEquals(0, view.interventions.count(handle.get().id()));
                    SwingUtilities.invokeAndWait(() -> {
                        assertPasswordEmpty(firstField.get());
                        assertPasswordEmpty(acceptedField.get());
                    });
                } finally {
                    if (!handle.get().completion().toCompletableFuture().isDone()) handle.get().requestCancel();
                    view.interventions.withdrawAll();
                    handle.get().completion().toCompletableFuture().get(10, TimeUnit.SECONDS);
                    var bytes = received.get();
                    if (bytes != null) Arrays.fill(bytes, (byte) 0);
                }
            }
        }
    }

    private long interactionLeases(PlatformRuntime runtime, TaskHandle<?> handle) throws Exception {
        return scalar(runtime, "SELECT COUNT(*) FROM platform_task_resource WHERE task_id='" + handle.id()
            + "' AND resource_kind='CUSTOM' AND resource_id LIKE '%/interaction/host-review-%' AND released_at IS NULL");
    }

    private final class HostedView implements AutoCloseable {
        final ModContext actual;

        final PlatformInterventionService interventions;

        final UiInterventionService capability;

        final JFrame owner;

        final DeploymentStandardPage page;

        final TaskInterventionPresenter presenter;

        HostedView(PlatformRuntime runtime) throws Exception {
            actual = runtime.modContexts().get(OWNER).context();
            interventions = runtime.tasks().interventions();
            capability = interventions.scoped(OWNER, VERSION, () -> true);
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
            var createdOwner = new AtomicReference<JFrame>();
            var createdPage = new AtomicReference<DeploymentStandardPage>();
            var createdPresenter = new AtomicReference<TaskInterventionPresenter>();
            SwingUtilities.invokeAndWait(() -> {
                var view = new DeploymentStandardPage(DeploymentStandardEntryFixture.pages(context,
                    new DeploymentStandardEntryFixture(context, new DeploymentRefFlow(context))));
                var frame = new JFrame("Hosted password fixture");
                frame.setContentPane(view.create(new UiEnvironment(Locale.ENGLISH, "LIGHT", false)));
                frame.pack();
                frame.setVisible(true);
                createdOwner.set(frame);
                createdPage.set(view);
                createdPresenter.set(new TaskInterventionPresenter(frame, MessageCatalog.forLanguageTag("en"), interventions));
            });
            owner = createdOwner.get();
            page = createdPage.get();
            presenter = createdPresenter.get();
        }

        @Override
        public void close() throws Exception {
            SwingUtilities.invokeAndWait(() -> {
                presenter.close();
                page.close();
                owner.dispose();
            });
        }
    }

    private static JDialog awaitDialog(JFrame owner) throws Exception {
        var result = new AtomicReference<JDialog>();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (result.get() == null && System.nanoTime() < deadline) {
            SwingUtilities.invokeAndWait(() -> {
                for (var window : owner.getOwnedWindows())
                    if (window instanceof JDialog dialog && dialog.isVisible() && "task.intervention.dialog".equals(dialog.getName()))
                        result.set(dialog);
            });
            if (result.get() == null) Thread.sleep(20);
        }
        assertNotNull(result.get(), "The original hosted password question must be visible");
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

    private static JToggleButton textButton(Component component, String text) {
        if (component instanceof JToggleButton button && text.equals(button.getText())) return button;
        if (component instanceof Container container) for (var child : container.getComponents()) {
            var found = textButton(child, text);
            if (found != null) return found;
        }
        return null;
    }

    private static String visibleText(Component component) {
        if (!component.isShowing()) return "";
        String text = ownText(component);
        if (component instanceof Container container)
            for (var child : container.getComponents()) text += "\n" + visibleText(child);
        return text;
    }

    private static String allText(Component component) {
        String text = ownText(component);
        if (component instanceof Container container)
            for (var child : container.getComponents()) text += "\n" + allText(child);
        return text;
    }

    private static String ownText(Component component) {
        if (component instanceof JPasswordField) return "";
        return component instanceof JTextComponent text ? text.getText() : component instanceof JLabel label ? label.getText() : "";
    }

    private static void assertPasswordEmpty(JPasswordField field) {
        char[] observed = field.getPassword();
        try {
            assertEquals(0, observed.length, "Ended form openings must clear the password document");
        } finally {
            Arrays.fill(observed, '\0');
        }
    }
}
