package gold.debug.windowstolinux.mod.deployment.ui;

import gold.debug.windowstolinux.appapi.task.OperationResult;
import gold.debug.windowstolinux.appapi.task.Outcome;
import gold.debug.windowstolinux.appapi.task.TaskContext;
import gold.debug.windowstolinux.appapi.task.TaskState;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.*;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.*;

import javax.swing.*;

import gold.debug.windowstolinux.app.db.server.StoredServerProfile;
import gold.debug.windowstolinux.app.service.server.ServerProfile;
import gold.debug.windowstolinux.appapi.linux.*;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.server.ServerRef;

import gold.debug.windowstolinux.appapi.ui.UiEnvironment;
import gold.debug.windowstolinux.mod.deployment.DeploymentRuntimeFixture;
import gold.debug.windowstolinux.mod.deployment.service.core.DeploymentReferenceFlow;
import gold.debug.windowstolinux.mod.deployment.service.core.model.*;
import gold.debug.windowstolinux.mod.deployment.service.standard.*;
import gold.debug.windowstolinux.mod.deployment.service.standard.analyze.component.DiscoveredProjectComponent;
import gold.debug.windowstolinux.mod.deployment.service.standard.deploy.database.DeploymentPreparedDatabase;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Keeps the actual ordinary runtime form while the same CLASS task corrects invalid limits. / 同一CLASS任务修正非法限额时保留实际普通运行表单。 */
class DeploymentOrdinaryRuntimeFormTest extends DeploymentRuntimeFixture {
    @Override
    protected Path selectedSourceDirectory() {
        return root.resolve("input");
    }

    /** Reuses exact form controls and requires fresh final approval before any SSH connection. / 复用精确表单控件且任一SSH连接前要求新的最终审批。
     * @param invalid invalid human timeout input / 非法人工超时输入
     * @throws Exception if real source, CLASS task or owner modal fails / 实际来源、CLASS任务或所属模态失败时 */
    @ParameterizedTest
    @ValueSource(strings = {"not-a-number", "0", "malformed-configuration"})
    void invalidOrdinaryLimitKeepsOriginalFormUntilCorrectionAndFinalRefusal(String invalid) throws Exception {
        Files.createDirectories(selectedSourceDirectory());
        Files.writeString(selectedSourceDirectory().resolve("go.mod"), "module example.test/runtimeform\n\ngo 1.24\n");
        Files.writeString(selectedSourceDirectory().resolve("go.sum"), "example.test/dependency v1 h1:fixture\n");
        Files.writeString(selectedSourceDirectory().resolve("main.go"), "package main\nfunc main() {}\n");
        Files.writeString(selectedSourceDirectory().resolve("windowstolinux-application.properties"),
            "version=1\nhealth.mode=TCP\n");
        enable();
        var owner = new AtomicReference<JFrame>();
        var page = new AtomicReference<DeploymentStaticPage>();
        try (var runtime = open()) {
            start(runtime, true);
            runtime.servers().saveServerProfile(
                ServerProfile
                    .fromStored(new StoredServerProfile("server", "127.0.0.1", 22, "root", "fixture-password")),
                "fixture-password".toCharArray());
            var actual = runtime.modContexts().get(OWNER).context();
            var connections = new AtomicInteger();
            LinuxService linux = new LinuxService() {
                public RemoteSession connect(ServerRef server, TaskContext task) {
                    connections.incrementAndGet();
                    throw new AssertionError("No SSH before final approval");
                }

                public List<TransferCheckpoint> pendingTransfers(TaskContext task) {
                    return List.of();
                }
            };
            var bound = (ModContext) Proxy.newProxyInstance(ModContext.class.getClassLoader(),
                new Class<?>[]{ModContext.class}, (proxy, method, args) -> {
                    if (method.getName().equals("linux"))
                        return linux;
                    try {
                        return method.invoke(actual, args);
                    } catch (InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                });
            var workflow = new DeploymentStaticWorkflow(bound, new DeploymentReferenceFlow(bound));
            SwingUtilities.invokeAndWait(() -> {
                var view = new DeploymentStaticPage(new DeploymentStaticPageService(bound, workflow));
                var frame = new JFrame("Original ordinary runtime form");
                frame.setContentPane(view.create(new UiEnvironment(Locale.ENGLISH, "LIGHT", false)));
                frame.pack();
                frame.setVisible(true);
                page.set(view);
                owner.set(frame);
            });
            var originalTask = new AtomicReference<TaskContext>();
            var prompt = new AtomicReference<DeploymentStaticWorkflow.Prompt>();
            var expected = new AtomicReference<Map<String, String>>();
            var confirms = new AtomicInteger();
            var delegate = new DeploymentStaticPageReviewer(page.get(), page.get().viewGeneration());
            var reviewer = new DeploymentStaticWorkflow.Reviewer() {
                public OperationResult<DeploymentStaticWorkflow.Selection> select(ServerRef server, String sha,
                                                                                  List<DiscoveredProjectComponent> components, TaskContext task) {
                    assertEquals(1, components.size());
                    originalTask.set(task);
                    String id = components.getFirst().id();
                    return success(new DeploymentStaticWorkflow.Selection(id,
                        Map.of(id, new DeploymentStaticWorkflow.Choice(DeploymentProjectKind.GO_SERVICE, Set.of())),
                        Optional.empty()));
                }

                public OperationResult<Map<String, DeploymentStaticWorkflow.Answers>> review(ServerRef server,
                                                                                             String sha, List<DeploymentStaticWorkflow.Prompt> prompts, TaskContext task) {
                    assertSame(originalTask.get(), task);
                    assertEquals(1, prompts.size());
                    var current = prompts.getFirst();
                    prompt.set(current);
                    var values = new LinkedHashMap<>(current.defaults());
                    current.fields().forEach(
                        field -> values.put(field.id().substring(field.id().indexOf('/') + 1), field.value()));
                    values.putAll(Map.of("primary", "app", "secondary", "main.go", "version", "1.24", "port", "8080",
                        "healthMode", "TCP", "exposure", "INTERNAL", "configuration", "SENTINEL=retained"));
                    workflow.validateReview(current,
                        new DeploymentStaticWorkflow.Answers(values, List.of(), limits(), false, true));
                    expected.set(Map.copyOf(values));
                    return delegate.review(server, sha, prompts, task);
                }

                public OperationResult<Boolean> confirm(ServerRef server, String sha, Map<String, String> archives,
                                                        Map<String, DeploymentStaticWorkflow.Answers> answers,
                                                        Map<String, DeploymentPreparedDatabase> databases,
                                                        Optional<DeploymentStaticWorkflow.GitReview> git, TaskContext task) {
                    assertSame(originalTask.get(), task);
                    assertEquals(0, connections.get());
                    assertEquals(1, archives.size());
                    var reviewed = answers.get(prompt.get().componentId());
                    var completed = new LinkedHashMap<>(prompt.get().defaults());
                    completed.putAll(reviewed.values());
                    assertEquals(expected.get(), completed);
                    assertEquals(limits(), reviewed.limits());
                    assertTrue(reviewed.adapterRisk());
                    confirms.incrementAndGet();
                    return delegate.confirm(server, sha, archives, answers, databases, git, task);
                }
            };
            var handle = workflow.submit(new ServerRef("server"),
                new DeploymentStaticWorkflow.Draft("", 65536, 100, 65536), Optional.empty(), reviewer);
            var first = awaitModal(owner.get(), "Review runtime and bindings");
            var fields = new AtomicReference<Map<String, JComponent>>();
            var form = new AtomicReference<JPanel>();
            var timeout = new AtomicReference<JTextField>();
            SwingUtilities.invokeAndWait(() -> {
                assertSame(owner.get(), first.getOwner());
                var current = componentForm(first);
                form.set(current);
                var inputs = runtimeFields(current, prompt.get());
                fields.set(inputs);
                expected.get().forEach((key, value) -> {
                    if (inputs.containsKey(key) && Set
                        .of("primary", "secondary", "version", "port", "healthMode", "exposure", "configuration")
                        .contains(key))
                        set(inputs.get(key), value);
                });
                int limitOffset = 2 + inputs.size() * 2;
                assertEquals(page.get().text("timeoutSeconds"), ((JLabel) current.getComponent(limitOffset)).getText());
                timeout.set((JTextField) current.getComponent(limitOffset + 1));
                timeout.get().setText(invalid.equals("malformed-configuration") ? "600" : invalid);
                if (invalid.equals("malformed-configuration"))
                    set(inputs.get("configuration"), "missing-equals");
                var risks = Arrays.stream(current.getComponents()).filter(JCheckBox.class::isInstance)
                    .map(JCheckBox.class::cast).toList();
                assertEquals(2, risks.size());
                risks.getLast().setSelected(true);
                decide(first, JOptionPane.OK_OPTION);
            });
            var repeated = awaitModal(owner.get(), "Review runtime and bindings");
            SwingUtilities.invokeAndWait(() -> {
                assertSame(form.get(), componentForm(repeated));
                assertEquals(fields.get(), runtimeFields(form.get(), prompt.get()));
                assertEquals(invalid.equals("malformed-configuration") ? "600" : invalid, timeout.get().getText());
                for (String key : List.of("primary", "secondary", "version", "port", "configuration"))
                    assertEquals(key.equals("configuration") && invalid.equals("malformed-configuration")
                        ? "missing-equals"
                        : expected.get().get(key), value(fields.get().get(key)));
                assertTrue(descendants(repeated).stream().filter(JLabel.class::isInstance).map(JLabel.class::cast)
                    .anyMatch(label -> page.get().text("invalidForm").equals(label.getText())));
                assertEquals(0, connections.get());
                assertEquals(handle.id(), originalTask.get().id());
                assertFalse(handle.completion().toCompletableFuture().isDone());
                if (invalid.equals("malformed-configuration"))
                    set(fields.get().get("configuration"), "SENTINEL=retained");
                else
                    timeout.get().setText("600");
                decide(repeated, JOptionPane.OK_OPTION);
            });
            var finalReview = awaitModal(owner.get(), "Confirm deployment");
            SwingUtilities.invokeAndWait(() -> {
                assertEquals(1, confirms.get());
                assertEquals(0, connections.get());
                decide(finalReview, JOptionPane.CANCEL_OPTION);
            });
            var result = handle.completion().toCompletableFuture().get(25, TimeUnit.SECONDS);
            assertEquals(Outcome.CANCELLED, result.outcome(), result.toString());
            assertEquals(TaskState.ENDED, handle.snapshot().state());
            assertEquals(handle.id(), originalTask.get().id());
            assertEquals(0, connections.get());
            SwingUtilities.invokeAndWait(() -> {
                page.get().close();
                owner.get().dispose();
            });
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (actual.tasks().list(Optional.empty(), 200).items().stream()
                .filter(task -> task.type().equals("deployment-server-list")
                    || task.type().equals("deployment-secret-list"))
                .anyMatch(task -> task.state() != TaskState.ENDED) && System.nanoTime() < until)
                Thread.sleep(20);
            assertEquals(0, scalar(runtime, "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL"));
        } finally {
            SwingUtilities.invokeAndWait(() -> {
                if (page.get() != null)
                    page.get().close();
                if (owner.get() != null)
                    owner.get().dispose();
            });
        }
    }

    private static DeploymentBuildLimits limits() {
        return new DeploymentBuildLimits(600, 1024, 4096, 4194304, 4294967296L, false);
    }

    private static <T> OperationResult<T> success(T value) {
        return new OperationResult<>(Outcome.SUCCEEDED, Optional.of(value), Optional.empty());
    }

    private static JPanel componentForm(JDialog dialog) {
        var tabs = (JTabbedPane) descendants(dialog).stream().filter(JTabbedPane.class::isInstance).findFirst()
            .orElseThrow();
        assertEquals(1, tabs.getTabCount());
        return (JPanel) ((JScrollPane) tabs.getComponentAt(0)).getViewport().getView();
    }

    private static Map<String, JComponent> runtimeFields(JPanel panel, DeploymentStaticWorkflow.Prompt prompt) {
        var fields = new LinkedHashMap<String, JComponent>();
        for (var field : prompt.fields()) {
            String key = field.id().substring(field.id().indexOf('/') + 1);
            if (!fields.containsKey(key))
                fields.put(key, (JComponent) panel.getComponent(3 + 2 * fields.size()));
        }
        return fields;
    }

    private static void set(JComponent control, String value) {
        if (control instanceof JTextField field)
            field.setText(value);
        else if (control instanceof JComboBox<?> box)
            box.setSelectedItem(value);
        else
            throw new AssertionError("Unexpected actual runtime input " + control);
    }

    private static String value(JComponent control) {
        return control instanceof JTextField field
            ? field.getText()
            : String.valueOf(((JComboBox<?>) control).getSelectedItem());
    }

    private static void decide(JDialog dialog, int choice) {
        var pane = (JOptionPane) descendants(dialog).stream().filter(JOptionPane.class::isInstance).findFirst()
            .orElseThrow();
        pane.setValue(choice);
        dialog.dispose();
    }

    private static List<Component> descendants(Container parent) {
        var result = new ArrayList<Component>();
        for (var child : parent.getComponents()) {
            result.add(child);
            if (child instanceof Container nested)
                result.addAll(descendants(nested));
        }
        return result;
    }

    private static JDialog awaitModal(JFrame owner, String title) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < until) {
            var result = new AtomicReference<JDialog>();
            SwingUtilities.invokeAndWait(() -> {
                for (var window : owner.getOwnedWindows())
                    if (window instanceof JDialog dialog && dialog.isVisible() && title.equals(dialog.getTitle()))
                        result.set(dialog);
            });
            if (result.get() != null)
                return result.get();
            Thread.sleep(20);
        }
        throw new AssertionError("Actual original-task modal did not open: " + title);
    }
}
