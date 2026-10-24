package gold.debug.windowstolinux.mod.deployment.ui.standard;

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
import javax.swing.text.JTextComponent;

import gold.debug.windowstolinux.app.db.server.StoredServerProfile;
import gold.debug.windowstolinux.app.service.server.ServerProfile;
import gold.debug.windowstolinux.appapi.linux.*;
import gold.debug.windowstolinux.appapi.linux.session.RemoteSession;
import gold.debug.windowstolinux.appapi.linux.transfer.TransferCheckpoint;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.model.TaskState;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.appapi.ui.page.UiEnvironment;
import gold.debug.windowstolinux.mod.deployment.DeploymentRuntimeFixture;
import gold.debug.windowstolinux.mod.deployment.DeploymentStandardEntryFixture;
import gold.debug.windowstolinux.mod.deployment.service.core.model.build.DeploymentBuildLimits;
import gold.debug.windowstolinux.mod.deployment.service.core.model.project.kind.DeploymentProjectKind;
import gold.debug.windowstolinux.mod.deployment.service.core.publish.DeploymentRefFlow;
import gold.debug.windowstolinux.mod.deployment.service.deploy.analyze.part.DiscoveredProjectPart;
import gold.debug.windowstolinux.mod.deployment.service.deploy.database.DeploymentPreparedDatabase;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.contract.DeploymentStandardInputs;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Keeps the actual ordinary runtime form while the same CLASS task corrects invalid limits. / 同一CLASS任务修正非法限额时保留实际普通运行表单。 */
class DeploymentOrdinaryRuntimeFormTest extends DeploymentRuntimeFixture {
    @Override
    protected gold.debug.windowstolinux.app.service.task.PlatformInteractionService.Prompts interactionPrompts() {
        var refusal = super.interactionPrompts();
        var type = gold.debug.windowstolinux.app.service.task.PlatformInteractionService.Prompts.class;
        return (gold.debug.windowstolinux.app.service.task.PlatformInteractionService.Prompts) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> {
            if (method.getName().equals("approve")) {
                var intent = (gold.debug.windowstolinux.appapi.task.action.ActionIntent) args[0];
                assertEquals("platform.ssh.execute", intent.type());
                assertEquals("deployment.candidate.capacity.v1", intent.semantics().orElseThrow().operation());
                assertTrue(intent.exactAction().endsWith(gold.debug.windowstolinux.mod.deployment.linux.publish.DeploymentCandidateChannel.capacityScript(
                    gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout.original())));
                return true;
            }
            try {
                return method.invoke(refusal, args);
            } catch (InvocationTargetException failure) {
                throw failure.getCause();
            }
        });
    }

    @Override
    protected Path selectedSourceDirectory() {
        return root.resolve("input");
    }

    /** Reuses exact form controls and requires fresh final approval before any deployment effect. / 复用精确表单控件且任何部署副作用前要求新的最终审批。
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
        var page = new AtomicReference<DeploymentStandardPage>();
        try (var runtime = open()) {
            start(runtime, true);
            runtime.servers().saveServerProfile(
                ServerProfile
                    .fromStored(new StoredServerProfile("server", "127.0.0.1", 22, "root", "fixture-password")),
                "fixture-password".toCharArray());
            var actual = runtime.modContexts().get(OWNER).context();
            var connections = new AtomicInteger();
            LinuxService linux = new LinuxService() {
                public RemoteSession connect(ServerId server, TaskContext task) {
                    throw new AssertionError("The original fixed target is required");
                }

                public RemoteSession connect(gold.debug.windowstolinux.appapi.linux.session.RemoteTarget target, TaskContext task) throws gold.debug.windowstolinux.appapi.task.result.ApiException {
                    assertEquals("server", target.server().id());
                    assertEquals(task.id(), target.taskId());
                    assertEquals("127.0.0.1", target.host());
                    assertEquals(22, target.port());
                    assertEquals("root", target.username());
                    assertEquals(1, connections.incrementAndGet(), "Only one read-only capacity observation is permitted");
                    var lease = task.retain("controlled_ssh", "runtime-form-capacity", "Fixed read-only capacity fixture");
                    var executes = new AtomicInteger();
                    return (RemoteSession) Proxy.newProxyInstance(RemoteSession.class.getClassLoader(), new Class<?>[]{RemoteSession.class}, (proxy, method, args) -> {
                        return switch (method.getName()) {
                            case "facts" ->
                                new gold.debug.windowstolinux.appapi.linux.session.RemoteFacts("linux", "x86_64", "SHA256:" + "A".repeat(43));
                            case "close" -> {
                                lease.close();
                                yield null;
                            }
                            case "execute" -> {
                                assertEquals(1, executes.incrementAndGet());
                                assertNotNull(args[1], "The original host must mint the exact approval grant");
                                var request = (gold.debug.windowstolinux.appapi.linux.command.CommandRequest) args[0];
                                assertEquals(gold.debug.windowstolinux.mod.deployment.linux.publish.DeploymentCandidateChannel.capacityScript(
                                    gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout.original()), request.script());
                                assertEquals(0, request.stdin().length);
                                yield success(new gold.debug.windowstolinux.appapi.linux.command.CommandResult(Optional.of(0), "1000000000 4096\n", "", false));
                            }
                            default ->
                                throw new AssertionError("No deployment or transfer is allowed: " + method.getName());
                        };
                    });
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
            var workflow = new DeploymentStandardEntryFixture(bound, new DeploymentRefFlow(bound));
            SwingUtilities.invokeAndWait(() -> {
                var view = new DeploymentStandardPage(DeploymentStandardEntryFixture.pages(bound, workflow));
                var frame = new JFrame("Original ordinary runtime form");
                frame.setContentPane(view.create(new UiEnvironment(Locale.ENGLISH, "LIGHT", false)));
                frame.pack();
                frame.setVisible(true);
                page.set(view);
                owner.set(frame);
            });
            var originalTask = new AtomicReference<TaskContext>();
            var prompt = new AtomicReference<DeploymentStandardInputs.Prompt>();
            var expected = new AtomicReference<Map<String, String>>();
            var confirms = new AtomicInteger();
            var delegate = new DeploymentStandardPageReviewer(page.get(), page.get().viewGeneration());
            var reviewer = new DeploymentStandardInputs.Reviewer() {
                public OperationResult<DeploymentStandardInputs.Selection> select(ServerId server, String sha,
                                                                                  List<DiscoveredProjectPart> components, TaskContext task) {
                    assertEquals(1, components.size());
                    originalTask.set(task);
                    String id = components.getFirst().id();
                    return success(new DeploymentStandardInputs.Selection(id,
                        Map.of(id, new DeploymentStandardInputs.Choice(DeploymentProjectKind.GO_SERVICE, Set.of())),
                        Optional.empty()));
                }

                public OperationResult<Map<String, DeploymentStandardInputs.Answers>> review(ServerId server,
                                                                                             String sha, List<DeploymentStandardInputs.Prompt> prompts, TaskContext task) {
                    assertSame(originalTask.get(), task);
                    assertEquals(1, prompts.size());
                    var current = prompts.getFirst();
                    prompt.set(current);
                    var values = new LinkedHashMap<>(current.defaults());
                    current.fields().forEach(
                        field -> values.put(field.id().substring(field.id().indexOf('/') + 1), field.value()));
                    values.putAll(Map.of("primary", "app", "secondary", "main.go", "version", "1.24", "port", "8080",
                        "healthMode", "TCP", "exposure", "INTERNAL", "configuration", "SENTINEL=retained;\nSECOND_LINE=kept"));
                    workflow.validateReview(current,
                        new DeploymentStandardInputs.Answers(values, List.of(), limits(), false, true));
                    expected.set(Map.copyOf(values));
                    return delegate.review(server, sha, prompts, task);
                }

                public OperationResult<Boolean> confirm(ServerId server, String sha, Map<String, String> archives,
                                                        Map<String, DeploymentStandardInputs.Answers> answers,
                                                        Map<String, DeploymentPreparedDatabase> databases,
                                                        Optional<DeploymentStandardInputs.GitReview> git, TaskContext task) {
                    assertSame(originalTask.get(), task);
                    assertEquals(1, connections.get());
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
            var handle = workflow.submit(new ServerId("server"),
                new DeploymentStandardInputs.Draft("", 65536, 100, 65536), Optional.empty(), reviewer);
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
                assertInstanceOf(JTextArea.class, inputs.get("configuration"));
                expected.get().forEach((key, value) -> {
                    if (inputs.containsKey(key) && Set
                        .of("primary", "secondary", "version", "port", "healthMode", "exposure", "configuration")
                        .contains(key))
                        set(inputs.get(key), value);
                });
                ((JComboBox<?>) descendants(current).stream().filter(value -> "deployment.runtime.workspaceMode".equals(value.getName())).findFirst().orElseThrow())
                    .setSelectedItem(gold.debug.windowstolinux.mod.deployment.service.core.model.build.DeploymentWorkspaceQuota.Mode.EXPLICIT);
                int limitOffset = 2 + inputs.size() * 2;
                assertEquals(page.get().text("timeoutSeconds"), ((JLabel) current.getComponent(limitOffset)).getText());
                timeout.set((JTextField) current.getComponent(limitOffset + 1));
                timeout.get().setText(invalid.equals("malformed-configuration") ? "600" : invalid);
                if (invalid.equals("malformed-configuration"))
                    set(inputs.get("configuration"), "SENTINEL=retained;\nmissing-equals");
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
                        ? "SENTINEL=retained;\nmissing-equals"
                        : expected.get().get(key), value(fields.get().get(key)));
                String error = page.get().text("invalidForm") + " " + prompt.get().componentId()
                    + (invalid.equals("malformed-configuration") ? " / " + page.get().text("auto.field.configuration") : "");
                assertTrue(descendants(repeated).stream().filter(JLabel.class::isInstance).map(JLabel.class::cast)
                    .anyMatch(label -> error.equals(label.getText())));
                if (invalid.equals("malformed-configuration")) {
                    var configuration = fields.get().get("configuration");
                    assertTrue(descendants(repeated).stream().filter(JComponent.class::isInstance)
                        .map(JComponent.class::cast).anyMatch(control -> control.getClientProperty("deployment.review.focus") == configuration));
                }
                assertEquals(0, connections.get());
                assertEquals(handle.id(), originalTask.get().id());
                assertFalse(handle.completion().toCompletableFuture().isDone());
                if (invalid.equals("malformed-configuration"))
                    set(fields.get().get("configuration"), expected.get().get("configuration"));
                else
                    timeout.get().setText("600");
                decide(repeated, JOptionPane.OK_OPTION);
            });
            final JDialog finalReview;
            try {
                finalReview = awaitModal(owner.get(), "Confirm deployment");
            } catch (AssertionError missing) {
                if (handle.completion().toCompletableFuture().isDone())
                    throw new AssertionError("Final review was not reached: " + handle.completion().toCompletableFuture().get(), missing);
                throw missing;
            }
            SwingUtilities.invokeAndWait(() -> {
                assertEquals(1, confirms.get());
                assertEquals(1, connections.get());
                decide(finalReview, JOptionPane.CANCEL_OPTION);
            });
            var result = handle.completion().toCompletableFuture().get(25, TimeUnit.SECONDS);
            assertEquals(TaskResult.CANCELLED, result.outcome(), result.toString());
            assertEquals(TaskState.ENDED, handle.snapshot().state());
            assertEquals(handle.id(), originalTask.get().id());
            assertEquals(1, connections.get());
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
        return new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(value), Optional.empty());
    }

    private static JPanel componentForm(JDialog dialog) {
        var tabs = (JTabbedPane) descendants(dialog).stream().filter(JTabbedPane.class::isInstance).findFirst()
            .orElseThrow();
        assertEquals(1, tabs.getTabCount());
        return (JPanel) descendants((Container) tabs.getComponentAt(0)).stream()
            .filter(value -> value instanceof JPanel && value.getName() != null && value.getName().startsWith("deployment.runtime.component.")).findFirst().orElseThrow();
    }

    private static Map<String, JComponent> runtimeFields(JPanel panel, DeploymentStandardInputs.Prompt prompt) {
        var fields = new LinkedHashMap<String, JComponent>();
        for (var field : prompt.fields()) {
            String key = field.id().substring(field.id().indexOf('/') + 1);
            if (!fields.containsKey(key)) {
                var label = descendants(panel).stream().filter(JLabel.class::isInstance).map(JLabel.class::cast)
                    .filter(value -> value.getLabelFor() != null && ("deployment.runtime." + field.id()).equals(value.getLabelFor().getName())).findFirst().orElseThrow();
                var input = (JComponent) label.getLabelFor();
                assertNotNull(input, "Actual field label must target the editable control: " + key);
                assertEquals("deployment.runtime." + field.id(), input.getName());
                fields.put(key, input);
            }
        }
        return fields;
    }

    private static void set(JComponent control, String value) {
        if (control instanceof JTextComponent field)
            field.setText(value);
        else if (control instanceof JComboBox<?> box)
            box.setSelectedItem(value);
        else
            throw new AssertionError("Unexpected actual runtime input " + control);
    }

    private static String value(JComponent control) {
        return control instanceof JTextComponent field
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
