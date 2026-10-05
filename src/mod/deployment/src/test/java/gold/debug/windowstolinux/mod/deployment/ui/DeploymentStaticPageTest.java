package gold.debug.windowstolinux.mod.deployment.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.Graphics2D;
import java.awt.Window;
import java.awt.image.BufferedImage;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import javax.imageio.ImageIO;
import javax.swing.*;

import gold.debug.windowstolinux.app.db.server.StoredServerProfile;
import gold.debug.windowstolinux.app.service.server.ServerProfile;
import gold.debug.windowstolinux.app.ui.mod.ModPageRegistry;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.secret.model.SecretId;
import gold.debug.windowstolinux.appapi.secret.SecretService;
import gold.debug.windowstolinux.appapi.task.model.TaskRequest;
import gold.debug.windowstolinux.appapi.task.model.TaskState;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.appapi.task.run.TaskHandle;
import gold.debug.windowstolinux.appapi.ui.*;
import gold.debug.windowstolinux.appapi.ui.page.PageSpec;
import gold.debug.windowstolinux.appapi.ui.page.UiEnvironment;
import gold.debug.windowstolinux.mod.deployment.DeploymentRuntimeFixture;
import gold.debug.windowstolinux.mod.deployment.db.DeploymentSecretStore;
import gold.debug.windowstolinux.mod.deployment.service.core.model.DeploymentDatabaseEngineType;
import gold.debug.windowstolinux.mod.deployment.service.core.model.DeploymentProjectKind;
import gold.debug.windowstolinux.mod.deployment.service.core.model.DeploymentSecretRef;
import gold.debug.windowstolinux.mod.deployment.service.core.model.ecosystem.db.DeploymentNativeDatabaseFacts.Candidate;
import gold.debug.windowstolinux.mod.deployment.service.core.model.ecosystem.db.DeploymentNativeDatabaseFacts.Instance;
import gold.debug.windowstolinux.mod.deployment.service.standard.DeploymentStaticWorkflow;
import gold.debug.windowstolinux.mod.deployment.service.standard.analyze.ecosystem.db.DatabaseProjectChecker;
import gold.debug.windowstolinux.mod.deployment.service.standard.deploy.database.DeploymentDatabaseSourceInputs;
import gold.debug.windowstolinux.mod.deployment.service.standard.deploy.database.DeploymentSqliteSetup;
import gold.debug.windowstolinux.mod.deployment.service.standard.deploy.input.DeploymentStaticRuntimeResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Checks the actual lazily registered Swing view, lock/close lifetime and optional visual evidence. / 检查真实惰性登记Swing视图、锁定关闭寿命及可选视觉证据。 */
class DeploymentStaticPageTest extends DeploymentRuntimeFixture {
    private Path pickerDirectory;

    private CountDownLatch pickerEntered, pickerRelease;

    @Override
    protected Path selectedSourceDirectory() {
        if (pickerDirectory == null) return super.selectedSourceDirectory();
        if (pickerEntered != null) {
            pickerEntered.countDown();
            try {
                if (!pickerRelease.await(10, TimeUnit.SECONDS))
                    throw new AssertionError("Original picker was not released");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new java.util.concurrent.CancellationException("Original picker interrupted");
            }
        }
        return pickerDirectory;
    }

    @Test
    void selectedLocalMetadataClosesPickerGrantAndExplicitClearAllowsActualPathImport() throws Exception {
        var directory = Files.createDirectories(root.resolve("picked-source"));
        Files.writeString(directory.resolve("main.txt"), "actual local data");
        pickerDirectory = directory;
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            runtime.servers().saveServerProfile(ServerProfile.fromStored(
                new StoredServerProfile("source-server", "127.0.0.1", 22, "root", "fixture-password")), "fixture-password".toCharArray());
            var context = runtime.modContexts().get(OWNER).context();
            var page = new DeploymentStaticPage(new gold.debug.windowstolinux.mod.deployment.service.standard.DeploymentStaticPageService(context,
                new DeploymentStaticWorkflow(context, new gold.debug.windowstolinux.mod.deployment.service.core.DeploymentRefFlow(context))));
            var mounted = new AtomicReference<JComponent>();
            try {
                SwingUtilities.invokeAndWait(() -> mounted.set(page.create(new UiEnvironment(Locale.ENGLISH, "LIGHT", false))));
                awaitControlEnabled(mounted.get(), "deployment.local.choose");
                SwingUtilities.invokeAndWait(() -> ((JButton) namedControl(mounted.get(), "deployment.local.choose")).doClick());
                awaitControlEnabled(mounted.get(), "deployment.local.clear");
                awaitLocalLocation(mounted.get(), directory.toRealPath().toUri().toString());
                assertEquals(0, scalar(runtime, "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL"));
                SwingUtilities.invokeAndWait(() -> {
                    assertNull(((JComboBox<?>) namedControl(mounted.get(), "deployment.server")).getSelectedItem());
                    assertFalse(findButton(mounted.get(), "Start static / assisted deployment").isEnabled());
                    var location = (JTextField) namedControl(mounted.get(), "deployment.local.location");
                    var duplicate = new TransferHandler.TransferSupport(location, new java.awt.datatransfer.StringSelection(directory.toString()));
                    assertFalse(location.getTransferHandler().importData(duplicate), "Another path requires an explicit clear");
                    ((JButton) namedControl(mounted.get(), "deployment.local.clear")).doClick();
                    assertEquals("", location.getText());
                    var relative = new TransferHandler.TransferSupport(location, new java.awt.datatransfer.StringSelection("relative/path"));
                    assertFalse(location.getTransferHandler().importData(relative));
                    var original = new TransferHandler.TransferSupport(location, new java.awt.datatransfer.StringSelection(directory.toString()));
                    assertTrue(location.getTransferHandler().importData(original));
                });
                awaitLocalLocation(mounted.get(), directory.toRealPath().toUri().toString());
                awaitServerTasks(context);
                assertEquals(0, scalar(runtime, "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL"));
            } finally {
                SwingUtilities.invokeAndWait(page::close);
            }
            awaitServerTasks(context);
        }
    }

    @Test
    void clearingOriginalPickerRejectsItsDelayedResultWithoutAReplacementGrant() throws Exception {
        var directory = Files.createDirectories(root.resolve("late-source"));
        Files.writeString(directory.resolve("main.txt"), "actual local data");
        pickerDirectory = directory;
        pickerEntered = new CountDownLatch(1);
        pickerRelease = new CountDownLatch(1);
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            var context = runtime.modContexts().get(OWNER).context();
            var page = new DeploymentStaticPage(new gold.debug.windowstolinux.mod.deployment.service.standard.DeploymentStaticPageService(context,
                new DeploymentStaticWorkflow(context, new gold.debug.windowstolinux.mod.deployment.service.core.DeploymentRefFlow(context))));
            var mounted = new AtomicReference<JComponent>();
            try {
                SwingUtilities.invokeAndWait(() -> mounted.set(page.create(new UiEnvironment(Locale.ENGLISH, "LIGHT", false))));
                awaitControlEnabled(mounted.get(), "deployment.local.choose");
                SwingUtilities.invokeAndWait(() -> ((JButton) namedControl(mounted.get(), "deployment.local.choose")).doClick());
                assertTrue(pickerEntered.await(10, TimeUnit.SECONDS));
                SwingUtilities.invokeAndWait(() -> {
                    var clear = (JButton) namedControl(mounted.get(), "deployment.local.clear");
                    assertTrue(clear.isEnabled());
                    clear.doClick();
                });
                pickerRelease.countDown();
                awaitControlEnabled(mounted.get(), "deployment.local.choose");
                awaitServerTasks(context);
                SwingUtilities.invokeAndWait(() -> assertEquals("", ((JTextField) namedControl(mounted.get(), "deployment.local.location")).getText()));
                assertTrue(context.tasks().list(Optional.empty(), 200).items().stream().filter(task -> task.type().equals("deployment-source-selection"))
                    .allMatch(task -> task.state() == TaskState.ENDED && task.outcome().equals(Optional.of(TaskResult.CANCELLED))));
                assertEquals(0, scalar(runtime, "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL"));
            } finally {
                pickerRelease.countDown();
                SwingUtilities.invokeAndWait(page::close);
            }
            awaitServerTasks(context);
        }
    }

    @Test
    void invalidRuntimeNumberReopensSameOriginalComponentAndCorrectsActualFieldWithoutDroppingValues() throws Exception {
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            var context = runtime.modContexts().get(OWNER).context();
            var page = new DeploymentStaticPage(new gold.debug.windowstolinux.mod.deployment.service.standard.DeploymentStaticPageService(context,
                new DeploymentStaticWorkflow(context, new gold.debug.windowstolinux.mod.deployment.service.core.DeploymentRefFlow(context))));
            SwingUtilities.invokeAndWait(() -> page.create(new UiEnvironment(Locale.ENGLISH, "LIGHT", false)));
            try {
                var resolver = new DeploymentStaticRuntimeResolver();
                var prompts = new ArrayList<DeploymentStaticWorkflow.Prompt>();
                for (String id : List.of("first", "second")) {
                    var values = new LinkedHashMap<String, String>();
                    values.put("primary", id + "-bin");
                    values.put("secondary", "main.go");
                    values.put("version", "1.24");
                    values.put("healthMode", "TCP");
                    values.put("port", id.equals("second") ? "private-entered-text" : "8080");
                    values.put("exposure", "INTERNAL");
                    values.put("configuration", "RUNTIME:MODE=retained;\nRUNTIME:VALUE=unchanged");
                    values.put("databaseMode", "NONE");
                    values.put("databaseDetails", "");
                    values.put("secrets", "");
                    prompts.add(new DeploymentStaticWorkflow.Prompt(id, id, DeploymentProjectKind.GO_SERVICE, values, resolver.corrections(id, values)));
                }
                var reviewer = new DeploymentStaticPageReviewer(page, 0);
                var handle = context.tasks().submit(new TaskRequest("runtime-correction-modal", "Original runtime review", List.of(), true),
                    task -> reviewer.review(new gold.debug.windowstolinux.appapi.server.model.ServerId("server"), "a".repeat(64), prompts, task));
                var firstDialog = awaitDialog("Review runtime and bindings");
                var originalInput = (JTextField) namedControl(firstDialog, "deployment.runtime.second/port");
                var configuration = (JTextArea) namedControl(firstDialog, "deployment.runtime.second/configuration");
                assertNotNull(originalInput);
                assertNotNull(configuration);
                acceptDialog(firstDialog);
                var correctedDialog = awaitDialog("Review runtime and bindings");
                SwingUtilities.invokeAndWait(() -> {
                    assertSame(originalInput, namedControl(correctedDialog, "deployment.runtime.second/port"));
                    assertEquals("private-entered-text", originalInput.getText());
                    assertEquals("error", originalInput.getClientProperty("JComponent.outline"));
                    assertNotNull(findLabel(correctedDialog, "Please correct the marked input and review again. second / Port"));
                    assertEquals("RUNTIME:MODE=retained;\nRUNTIME:VALUE=unchanged", configuration.getText());
                    originalInput.setText("8081");
                });
                acceptDialog(correctedDialog);
                var result = handle.completion().toCompletableFuture().get(20, TimeUnit.SECONDS);
                assertEquals(TaskResult.SUCCEEDED, result.outcome(), result.toString());
                assertEquals("8081", result.value().orElseThrow().get("second").values().get("port"));
                assertEquals("RUNTIME:MODE=retained;\nRUNTIME:VALUE=unchanged", result.value().orElseThrow().get("second").values().get("configuration"));
                assertEquals(TaskState.ENDED, handle.snapshot().state());
            } finally {
                SwingUtilities.invokeAndWait(page::close);
            }
            awaitServerTasks(context);
            assertEquals(0, scalar(runtime, "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL"));
        }
    }

    @Test
    void reviewRuntimeExceptionsKeepTheirOriginalIdentityOnAndOffEdt() throws Exception {
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            var context = runtime.modContexts().get(OWNER).context();
            var page = new DeploymentStaticPage(
                new gold.debug.windowstolinux.mod.deployment.service.standard.DeploymentStaticPageService(context,
                    new DeploymentStaticWorkflow(context,
                        new gold.debug.windowstolinux.mod.deployment.service.core.DeploymentRefFlow(
                            context))));
            try {
                SwingUtilities
                    .invokeAndWait(() -> page.create(new UiEnvironment(Locale.SIMPLIFIED_CHINESE, "LIGHT", true)));
                assertFalse(SwingUtilities.isEventDispatchThread());
                for (RuntimeException original : List.of(new IllegalArgumentException("invalid reviewed input"),
                    new java.util.concurrent.CancellationException("original review cancelled"))) {
                    assertSame(original, assertThrows(original.getClass(), () -> page.onEdt(() -> {
                        throw original;
                    })));
                    SwingUtilities.invokeAndWait(() -> {
                        assertTrue(SwingUtilities.isEventDispatchThread());
                        assertSame(original, assertThrows(original.getClass(), () -> page.onEdt(() -> {
                            throw original;
                        })));
                    });
                }
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (runtime.security().securityState().activeTasks() != 0 && System.nanoTime() < deadline)
                    Thread.sleep(10);
                assertEquals(0, runtime.security().securityState().activeTasks());
                assertEquals(0,
                    scalar(runtime, "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL"));
            } finally {
                SwingUtilities.invokeAndWait(page::close);
            }
        }
    }

    /** Shows each actual native package and restoration decision on the owner EDT. / 在所属 EDT 展示各原生软件包及恢复决定。
     * @throws Exception if the original task or modal handoff fails / 原任务或模态交接失败时 */
    @Test
    void nativeInstallReplacementAndRestoreUseActualOwnerModals() throws Exception {
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            var actual = runtime.modContexts().get(OWNER).context();
            var remoteCalls = new java.util.concurrent.atomic.AtomicInteger();
            var linux = Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{gold.debug.windowstolinux.appapi.linux.LinuxService.class},
                (proxy, method, args) -> {
                    remoteCalls.incrementAndGet();
                    throw new AssertionError("Modal review cannot perform SSH effects");
                });
            var context = (ModContext) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{ModContext.class}, (proxy, method, args) -> {
                    if (method.getName().equals("linux"))
                        return linux;
                    try {
                        return method.invoke(actual, args);
                    } catch (InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                });
            var originalTask = new AtomicReference<UUID>();
            var page = new DeploymentStaticPage(
                new gold.debug.windowstolinux.mod.deployment.service.standard.DeploymentStaticPageService(context,
                    new DeploymentStaticWorkflow(context,
                        new gold.debug.windowstolinux.mod.deployment.service.core.DeploymentRefFlow(
                            context))));
            SwingUtilities
                .invokeAndWait(() -> page.create(new UiEnvironment(Locale.SIMPLIFIED_CHINESE, "LIGHT", false)));
            var server = new gold.debug.windowstolinux.appapi.server.model.ServerId("native-modal-server");
            var previous = new Instance(DeploymentDatabaseEngineType.POSTGRESQL, "postgresql-16", "15.6", 5432,
                "postgresql.service", "/var/lib/postgresql/16/main", "a".repeat(64), false);
            var installed = new Instance(DeploymentDatabaseEngineType.POSTGRESQL, "postgresql-16", "16.6", 5432,
                "postgresql.service", "/var/lib/postgresql/16/main", "a".repeat(64), true);
            var candidate = new Candidate("postgresql-16", "16.6-1", "16.6");
            var database = new gold.debug.windowstolinux.mod.deployment.service.core.model.ecosystem.db.DatabaseRequirement(
                "main", DeploymentDatabaseEngineType.POSTGRESQL, ">=16", "sampledb", "sampleuser", "DB",
                "DB_PASSWORD", List.of(), false, "fixture");
            var handle = context.tasks().submit(new TaskRequest("native-modal-test", "Native review", List.of(), true),
                task -> {
                    assertTrue(originalTask.compareAndSet(null, task.id()));
                    var reviewer = new DeploymentStaticPageReviewer(page, page.viewGeneration());
                    for (var result : List.of(reviewer.install(server, database, candidate, task),
                        reviewer.replacement(server, previous, candidate, task),
                        reviewer.restored(server, installed, task)))
                        if (result.outcome() != TaskResult.SUCCEEDED)
                            return new OperationResult<Void>(result.outcome(), Optional.empty(), result.problem());
                    return new OperationResult<Void>(TaskResult.SUCCEEDED, Optional.empty(), Optional.empty());
                });
            var install = awaitDialog("安装数据库软件包");
            captureDialog(install, "native-install");
            acceptDialog(install);
            var replacement = awaitDialog("替换数据库软件包");
            captureDialog(replacement, "native-replacement");
            var panel = new AtomicReference<JPanel>();
            var checks = new AtomicReference<List<JCheckBox>>();
            SwingUtilities.invokeAndWait(() -> {
                panel.set(assertInstanceOf(JPanel.class,
                    assertInstanceOf(JScrollPane.class, findOptionPane(replacement).getMessage()).getViewport()
                        .getView()));
                checks.set(Arrays.stream(panel.get().getComponents()).filter(JCheckBox.class::isInstance)
                    .map(JCheckBox.class::cast).toList());
                assertEquals(
                    List.of("database.replacement.backup", "database.replacement.downtime",
                        "database.replacement.software"),
                    checks.get().stream().map(JCheckBox::getName).toList());
                checks.get().forEach(check -> assertFalse(check.isSelected()));
            });
            var current = replacement;
            for (int selected = 0; selected < 3; selected++) {
                int accepted = selected;
                SwingUtilities.invokeAndWait(() -> {
                    for (int index = 0; index < checks.get().size(); index++)
                        assertEquals(index < accepted, checks.get().get(index).isSelected());
                });
                assertEquals(handle.id(), originalTask.get());
                assertEquals(0, remoteCalls.get());
                assertFalse(handle.completion().toCompletableFuture().isDone());
                acceptDialog(current);
                current = awaitDialog("替换数据库软件包");
                var repeated = current;
                SwingUtilities.invokeAndWait(() -> {
                    assertSame(panel.get(), assertInstanceOf(JScrollPane.class, findOptionPane(repeated).getMessage())
                        .getViewport().getView());
                    for (int index = 0; index < checks.get().size(); index++)
                        assertSame(checks.get().get(index), panel.get().getComponent(index + 4));
                    checks.get().get(accepted).setSelected(true);
                });
            }
            acceptDialog(current);
            var restoration = awaitDialog("确认人工恢复数据库");
            captureDialog(restoration, "native-restoration");
            acceptDialog(restoration);
            assertEquals(TaskResult.SUCCEEDED,
                handle.completion().toCompletableFuture().get(20, TimeUnit.SECONDS).outcome());
            assertEquals(handle.id(), originalTask.get());
            assertEquals(TaskState.ENDED, handle.snapshot().state());
            assertEquals(0, remoteCalls.get());
            SwingUtilities.invokeAndWait(page::close);
            assertEquals(0, scalar(runtime, "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL"));
        }
    }

    /** A confirmed secret task completes after its owning page closes. / 已确认秘密任务在所属页关闭后继续完成。
     * @throws Exception if original task handoff fails / 原任务交接失败时 */
    @Test
    void confirmedSecretCreationSurvivesImmediatePageClose() throws Exception {
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            runtime.servers()
                .saveServerProfile(ServerProfile.fromStored(
                        new StoredServerProfile("secret-server", "127.0.0.1", 22, "root", "fixture-password")),
                    "fixture-password".toCharArray());
            var real = runtime.modContexts().get(OWNER).context();
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var cancelled = new AtomicBoolean();
            var intercepted = (SecretService) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{SecretService.class}, (proxy, method, args) -> {
                    if (method.getName().equals("create")) {
                        entered.countDown();
                        assertTrue(release.await(20, TimeUnit.SECONDS));
                        cancelled.set(((TaskContext) args[2]).cancelToken().requested());
                    }
                    try {
                        return method.invoke(real.secrets(), args);
                    } catch (InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                });
            var bound = (ModContext) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{ModContext.class}, (proxy, method, args) -> {
                    if (method.getName().equals("secrets"))
                        return intercepted;
                    try {
                        return method.invoke(real, args);
                    } catch (InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                });
            var page = new DeploymentStaticPage(
                new gold.debug.windowstolinux.mod.deployment.service.standard.DeploymentStaticPageService(bound,
                    new DeploymentStaticWorkflow(bound,
                        new gold.debug.windowstolinux.mod.deployment.service.core.DeploymentRefFlow(
                            bound))));
            var mounted = new AtomicReference<JComponent>();
            SwingUtilities.invokeAndWait(
                () -> mounted.set(page.create(new UiEnvironment(Locale.SIMPLIFIED_CHINESE, "LIGHT", false))));
            awaitStart(mounted.get());
            var secretPanel = (JPanel) namedControl(advancedControls(mounted.get()), "deployment.secrets");
            SwingUtilities.invokeAndWait(() -> {
                ((JTextField) namedControl(secretPanel, "deployment.secret.identifier")).setText("confirmed-secret");
                findButton(secretPanel, "创建受保护修订").doClick();
            });
            var dialog = awaitDialog("创建受保护的应用秘密");
            var handle = awaitCreation(page);
            SwingUtilities.invokeAndWait(() -> {
                findPassword(dialog).setText("\u4f60\u597d");
                findOptionPane(dialog).setValue(JOptionPane.OK_OPTION);
                dialog.dispose();
            });
            try {
                assertTrue(entered.await(10, TimeUnit.SECONDS));
                SwingUtilities.invokeAndWait(page::close);
            } finally {
                release.countDown();
            }
            assertEquals(TaskResult.SUCCEEDED,
                handle.completion().toCompletableFuture().get(20, TimeUnit.SECONDS).outcome());
            assertFalse(cancelled.get(), "Confirmed original secret creation must outlive the view");
            awaitServerTasks(real);
        }
    }

    /** Closing or locking an unanswered secret prompt cancels its original task without creating a credential. / 关闭或锁定未答秘密提示会取消原任务且不创建凭据。
     * @param lock whether the view is locked instead of closed / 是否锁定而非关闭
     * @throws Exception if the task or Swing boundary fails / 任务或 Swing 边界失败时 */
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void unansweredSecretPromptCancelsOriginalTask(boolean lock) throws Exception {
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            runtime.servers()
                .saveServerProfile(ServerProfile.fromStored(
                        new StoredServerProfile("secret-server", "127.0.0.1", 22, "root", "fixture-password")),
                    "fixture-password".toCharArray());
            var context = runtime.modContexts().get(OWNER).context();
            var page = new DeploymentStaticPage(
                new gold.debug.windowstolinux.mod.deployment.service.standard.DeploymentStaticPageService(context,
                    new DeploymentStaticWorkflow(context,
                        new gold.debug.windowstolinux.mod.deployment.service.core.DeploymentRefFlow(
                            context))));
            var mounted = new AtomicReference<JComponent>();
            SwingUtilities.invokeAndWait(
                () -> mounted.set(page.create(new UiEnvironment(Locale.SIMPLIFIED_CHINESE, "LIGHT", false))));
            awaitStart(mounted.get());
            var secretPanel = (JPanel) namedControl(advancedControls(mounted.get()), "deployment.secrets");
            SwingUtilities.invokeAndWait(() -> {
                ((JTextField) namedControl(secretPanel, "deployment.secret.identifier")).setText("cancelled-secret");
                findButton(secretPanel, "创建受保护修订").doClick();
            });
            var dialog = awaitDialog("创建受保护的应用秘密");
            var handle = awaitCreation(page);
            SwingUtilities.invokeAndWait(() -> {
                if (lock)
                    page.environmentChanged(new UiEnvironment(Locale.SIMPLIFIED_CHINESE, "LIGHT", true));
                else
                    page.close();
            });
            assertFalse(dialog.isVisible());
            assertEquals(TaskResult.CANCELLED,
                handle.completion().toCompletableFuture().get(20, TimeUnit.SECONDS).outcome());
            var read = context.tasks().submit(
                new TaskRequest("cancelled-secret-read", "Check absent revision", List.of(), true), task -> {
                    assertTrue(new DeploymentSecretStore(context.db())
                        .findRevision(task, new DeploymentSecretRef("cancelled-secret", 1)).isEmpty());
                    return new OperationResult<Void>(TaskResult.SUCCEEDED, Optional.empty(), Optional.empty());
                });
            assertEquals(TaskResult.SUCCEEDED,
                read.completion().toCompletableFuture().get(20, TimeUnit.SECONDS).outcome());
            if (lock)
                SwingUtilities.invokeAndWait(page::close);
            awaitServerTasks(context);
        }
    }

    /** Interrupting the waiting worker still clears the delayed EDT password control. / 中断等待线程后迟到的 EDT 仍清空密码控件。
     * @throws Exception if the bounded Swing handoff fails / 有界 Swing 交接失败时 */
    @Test
    void interruptedPasswordCaptureDiscardsDelayedSwingValue() throws Exception {
        var field = new JPasswordField();
        SwingUtilities.invokeAndWait(() -> field.setText("\u4f60\u597d"));
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var failure = new AtomicReference<Throwable>();
        SwingUtilities.invokeLater(() -> {
            entered.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
        var capture = DeploymentStaticPasswordInput.class.getDeclaredMethod("capture", JPasswordField.class);
        capture.setAccessible(true);
        var worker = Thread.ofVirtual().start(() -> {
            try {
                char[] exposed = (char[]) capture.invoke(null, field);
                Arrays.fill(exposed, '\0');
                failure.set(new AssertionError("Interrupted capture returned a secret"));
            } catch (java.lang.reflect.InvocationTargetException wrapped) {
                failure.set(wrapped.getCause());
            } catch (Exception unexpected) {
                failure.set(unexpected);
            }
        });
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            worker.interrupt();
            worker.join(5_000);
            assertFalse(worker.isAlive());
            assertInstanceOf(InterruptedException.class, failure.get());
        } finally {
            release.countDown();
        }
        SwingUtilities.invokeAndWait(() -> assertEquals(0, field.getPassword().length));
    }

    /** Creates a non-ASCII secret through the real page and selects its persisted exact revision. / 经真实页面创建非 ASCII 秘密并选择其持久精确修订。
     * @throws Exception if the original secret task or Swing handoff fails / 原秘密任务或 Swing 交接失败时 */
    @Test
    void pageCreatesProtectedSecretBeforePublicationAndOffersExactRevision() throws Exception {
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            runtime.servers()
                .saveServerProfile(ServerProfile.fromStored(
                        new StoredServerProfile("secret-server", "127.0.0.1", 22, "root", "fixture-password")),
                    "fixture-password".toCharArray());
            var context = runtime.modContexts().get(OWNER).context();
            var page = new DeploymentStaticPage(
                new gold.debug.windowstolinux.mod.deployment.service.standard.DeploymentStaticPageService(context,
                    new DeploymentStaticWorkflow(context,
                        new gold.debug.windowstolinux.mod.deployment.service.core.DeploymentRefFlow(
                            context))));
            var mounted = new AtomicReference<JComponent>();
            SwingUtilities.invokeAndWait(
                () -> mounted.set(page.create(new UiEnvironment(Locale.SIMPLIFIED_CHINESE, "LIGHT", false))));
            awaitStart(mounted.get());
            var controls = advancedControls(mounted.get());
            SwingUtilities.invokeAndWait(() -> {
                var identifier = inputAfterLabel(controls, page.text("secretIdentifier"));
                var revision = inputAfterLabel(controls, page.text("secretRevision"));
                var create = findButton(controls, page.text("secretCreate"));
                assertNotNull(create);
                var secrets = namedControl(controls, "deployment.secrets");
                assertTrue(SwingUtilities.isDescendingFrom(identifier, secrets));
                assertTrue(SwingUtilities.isDescendingFrom(revision, secrets));
                assertTrue(SwingUtilities.isDescendingFrom(create, secrets));
                identifier.setText("page-secret");
                revision.setText("1");
                render(mounted.get(), "secret-page");
                create.doClick();
            });
            var dialog = awaitDialog("创建受保护的应用秘密");
            var password = findPassword(dialog);
            assertNotNull(password);
            char[] input = {'你', '好', '!'};
            try {
                SwingUtilities.invokeAndWait(() -> {
                    password.setText(new String(input));
                    findOptionPane(dialog).setValue(JOptionPane.OK_OPTION);
                    dialog.dispose();
                });
            } finally {
                Arrays.fill(input, '\0');
            }
            var reference = new DeploymentSecretRef("page-secret", 1);
            awaitSecretChoice(page, reference);
            var read = context.tasks().submit(
                new TaskRequest("page-secret-read", "Read saved revision", List.of("secret-server"), false),
                task -> {
                    var stored = new DeploymentSecretStore(context.db()).findRevision(task, reference)
                        .orElseThrow();
                    assertEquals("deployment-application", stored.purpose().orElseThrow());
                    var expected = new byte[]{(byte) 0xe4, (byte) 0xbd, (byte) 0xa0, (byte) 0xe5, (byte) 0xa5,
                        (byte) 0xbd, 0x21};
                    boolean matched = context.secrets().use(
                        new SecretId(stored.credentialId(), stored.purpose().orElseThrow()),
                        stored.purpose().orElseThrow(), task, actual -> Arrays.equals(expected, actual));
                    Arrays.fill(expected, (byte) 0);
                    assertTrue(matched);
                    return new OperationResult<Void>(TaskResult.SUCCEEDED, Optional.empty(), Optional.empty());
                });
            assertEquals(TaskResult.SUCCEEDED,
                read.completion().toCompletableFuture().get(20, TimeUnit.SECONDS).outcome());
            SwingUtilities.invokeAndWait(() -> {
                var choice = new DeploymentSecretRefField(page::text, "", page.secretChoices());
                findButton(choice, "使用所选修订").doClick();
                assertEquals("page-secret:1", choice.value());
                var reviewRow = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEADING));
                reviewRow.add(new JLabel("已存在的秘密修订"));
                reviewRow.add(choice);
                var review = new JPanel(new java.awt.BorderLayout());
                review.add(reviewRow, java.awt.BorderLayout.NORTH);
                render(new JOptionPane(review, JOptionPane.QUESTION_MESSAGE, JOptionPane.OK_CANCEL_OPTION),
                    "secret-review");
                assertEquals(0, password.getPassword().length, "The Swing password control must be cleared");
                page.close();
            });
            var reopened = new DeploymentStaticPage(
                new gold.debug.windowstolinux.mod.deployment.service.standard.DeploymentStaticPageService(context,
                    new DeploymentStaticWorkflow(context,
                        new gold.debug.windowstolinux.mod.deployment.service.core.DeploymentRefFlow(
                            context))));
            var reopenedView = new AtomicReference<JComponent>();
            SwingUtilities.invokeAndWait(() -> reopenedView
                .set(reopened.create(new UiEnvironment(Locale.SIMPLIFIED_CHINESE, "LIGHT", false))));
            awaitSecretChoice(reopened, reference);
            assertEquals("deployment-application", reopened.secretChoices().stream()
                .filter(item -> item.reference().equals(reference)).findFirst().orElseThrow().purpose());
            SwingUtilities.invokeAndWait(() -> render(reopenedView.get(), "secret-page-saved"));
            SwingUtilities.invokeAndWait(reopened::close);
            awaitServerTasks(context);
        }
    }

    /** A closed page can reopen as a fresh instance without pinning or leaking its pending view. / 已关闭页面可作为新实例重开，不固定导航或泄漏待答视图。
     * @throws Exception if actual Mod registration or Swing rendering fails / 实际Mod登记或Swing渲染失败时 */
    @Test
    void normalModRegistersLazyLocalizedPageAndReopensAfterLock() throws Exception {
        enable();
        try (var runtime = open()) {
            runtime.servers()
                .saveServerProfile(ServerProfile.fromStored(
                        new StoredServerProfile("visual-server", "127.0.0.1", 22, "root", "fixture-password")),
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
            assertEquals(
                List.of(key),
                pages.contributions().stream().map(ModPageRegistry.PageContribution::key).toList());
            assertNull(mounted.get(), "Registration must remain lazy");
            pages.openView(key);
            var first = mounted.get();
            assertNotNull(first);
            var start = awaitStart(first);
            assertNotNull(start);
            SwingUtilities.invokeAndWait(() -> render(first, "page"));
            var advanced = advancedControls(first);
            var mode = (JComboBox<?>) namedControl(first, "deployment.sourceMode");
            var gitPanel = (JPanel) namedControl(advanced, "deployment.git");
            var secretPanel = (JPanel) namedControl(advanced, "deployment.secrets");
            var url = (JTextField) namedControl(advanced, "deployment.git.url");
            var name = (JTextField) namedControl(first, "deployment.sourceName");
            var nativeGit = (JTextField) namedControl(advanced, "deployment.git.executable");
            var status = (JTextArea) namedControl(first, "deployment.status");
            SwingUtilities.invokeAndWait(() -> {
                assertFalse(gitPanel.isVisible());
                ((JTextField) namedControl(secretPanel, "deployment.secret.identifier")).setText("INVALID-UPPER");
                findButton(secretPanel, "创建受保护修订").doClick();
                assertNotNull(findLabel(secretPanel, "请输入小写标识和正修订号。"));
                mode.setSelectedIndex(1);
                assertTrue(gitPanel.isVisible());
                assertNotNull(findLabel(first, "Git 项目名称（必填）"));
                assertNotNull(findLabel(gitPanel, "不含凭据的 Git URL"));
                url.setText("https://example.invalid/repository.git");
                render(first, "git-page");
                start.doClick();
                assertEquals("请检查 Git URL、引用与可执行文件路径。", status.getText());
                name.setText("reviewed-project");
                nativeGit.setText(Path.of(System.getProperty("java.home"), "bin", "java.exe").toString());
                url.setText("https://token@example.invalid/repository.git");
                start.doClick();
                assertEquals("请检查 Git URL、引用与可执行文件路径。", status.getText());
                url.setText("https://example.invalid/repository.git");
            });
            pages.environment(new UiEnvironment(Locale.ENGLISH, "LIGHT", false));
            SwingUtilities.invokeAndWait(() -> {
                assertEquals("https://example.invalid/repository.git", url.getText());
                assertNotNull(findLabel(first, "Git project name (required)"));
                assertNotNull(findLabel(gitPanel, "Credential-free Git URL"));
            });
            pages.environment(new UiEnvironment(Locale.SIMPLIFIED_CHINESE, "LIGHT", true));
            assertFalse(start.isEnabled());
            pages.closeView(key);
            assertNull(mounted.get());
            pages.environment(new UiEnvironment(Locale.SIMPLIFIED_CHINESE, "LIGHT", false));
            pages.openView(key);
            assertNotSame(first, mounted.get());
            awaitStart(mounted.get());
            pages.closeView(key);
            awaitServerTasks(runtime.modContexts().get(OWNER).context());
        }
    }

    /** Captures the same runtime form used in the modal review without starting a fake publication. / 抓取模态审阅实际复用的运行表单，不启动伪发布。
     * @throws Exception if actual Swing form cannot render / 实际Swing表单无法渲染时 */
    @Test
    void runtimeReviewFormRendersEditableConfigurationAndDatabaseScope() throws Exception {
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            var context = runtime.modContexts().get(OWNER).context();
            var page = new DeploymentStaticPage(
                new gold.debug.windowstolinux.mod.deployment.service.standard.DeploymentStaticPageService(context,
                    new DeploymentStaticWorkflow(context,
                        new gold.debug.windowstolinux.mod.deployment.service.core.DeploymentRefFlow(
                            context))));
            SwingUtilities
                .invokeAndWait(() -> page.create(new UiEnvironment(Locale.SIMPLIFIED_CHINESE, "LIGHT", false)));
            var defaults = new LinkedHashMap<String, String>();
            defaults.put("healthMode", "TCP");
            defaults.put("configuration", "RUNTIME:PORT=8080");
            defaults.put("secrets", "db-password:2");
            defaults.put("databaseMode", "NONE");
            defaults.put("databaseDetails", "");
            var fields = new DeploymentStaticRuntimeResolver().corrections("service", defaults);
            var prompt = new DeploymentStaticWorkflow.Prompt("service", "service", DeploymentProjectKind.GO_SERVICE,
                defaults, fields);
            var formClass = Class.forName(DeploymentStaticPageReviewer.class.getName() + "$PartForm");
            var constructor = formClass.getDeclaredConstructor(DeploymentStaticPage.class,
                DeploymentStaticWorkflow.Prompt.class);
            constructor.setAccessible(true);
            var panelMethod = formClass.getDeclaredMethod("panel");
            panelMethod.setAccessible(true);
            var confirmation = DeploymentStaticPageReviewer.class.getDeclaredMethod("confirmation",
                DeploymentStaticPage.class, gold.debug.windowstolinux.appapi.server.model.ServerId.class, String.class,
                Map.class, Map.class, Map.class, Optional.class);
            confirmation.setAccessible(true);
            SwingUtilities.invokeAndWait(() -> {
                try {
                    var form = constructor.newInstance(page, prompt);
                    var panel = (JPanel) panelMethod.invoke(form);
                    assertNotNull(findLabel(panel, "普通配置条目"));
                    assertNotNull(findLabel(panel, "已存在的秘密修订"));
                    assertNotNull(findLabel(panel, "数据库绑定方式"));
                    render(new JOptionPane(new JScrollPane(panel), JOptionPane.QUESTION_MESSAGE,
                        JOptionPane.OK_CANCEL_OPTION), "runtime-review");
                    var gitReview = new DeploymentStaticWorkflow.GitReview("https://example.invalid/repository.git",
                        "DEFAULT:HEAD", "a".repeat(40));
                    var finalPanel = (JPanel) confirmation.invoke(null, page,
                        new gold.debug.windowstolinux.appapi.server.model.ServerId("visual-server"), "b".repeat(64),
                        Map.of("service", "c".repeat(64)), Map.of(), Map.of(), Optional.of(gitReview));
                    assertNotNull(findLabel(finalPanel, "确认后将在部署前保存本次经审阅的来源项目记录。"));
                    render(new JOptionPane(new JScrollPane(finalPanel), JOptionPane.QUESTION_MESSAGE,
                        JOptionPane.OK_CANCEL_OPTION), "git-confirm");
                } catch (Exception failure) {
                    throw new AssertionError(failure);
                }
            });
            SwingUtilities.invokeAndWait(page::close);
            awaitServerTasks(context);
        }
    }

    /** The real Swing source form preserves declared paths while correcting a missing file and accepting multiple SQL files. / 真实 Swing 源码表单在修复缺失文件并选择多份 SQL 时保留已声明路径。
     * @throws Exception if local Swing or source setup fails / 本机 Swing 或源码准备失败时 */
    @Test
    void sqliteCorrectionKeepsSourceDefaultsAndMultipleSqlFiles() throws Exception {
        Path source = root.resolve("sqlite-source");
        Files.createDirectories(source);
        Files.writeString(source.resolve("windowstolinux-db.properties"),
            "db=main\ndb.main.engine=SQLITE\ndb.main.path=state/custom.db\n"
                + "db.main.pathEnvironment=APP_DB_PATH\ndb.main.initialize=schema-a.sql,missing.sql\n");
        Files.writeString(source.resolve("schema-a.sql"), "CREATE TABLE a(id integer);");
        Files.writeString(source.resolve("schema-b.sql"), "CREATE TABLE b(id integer);");
        var assessment = new DatabaseProjectChecker().inspect(source, (source).getFileName().toString());
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            var context = runtime.modContexts().get(OWNER).context();
            var page = new DeploymentStaticPage(
                new gold.debug.windowstolinux.mod.deployment.service.standard.DeploymentStaticPageService(context,
                    new DeploymentStaticWorkflow(context,
                        new gold.debug.windowstolinux.mod.deployment.service.core.DeploymentRefFlow(
                            context))));
            SwingUtilities
                .invokeAndWait(() -> page.create(new UiEnvironment(Locale.SIMPLIFIED_CHINESE, "LIGHT", false)));
            var target = new gold.debug.windowstolinux.appapi.server.model.ServerId("sqlite-server");
            var result = new AtomicReference<Map<String, String>>();
            var handle = context.tasks().submit(new TaskRequest("sqlite-modal-test", "SQLite review", List.of(), true),
                task -> {
                    var reviewer = new DeploymentStaticPageReviewer(page, 0);
                    var input = reviewer.databaseInputs(target, "api", "a".repeat(64), source, "sqlite-app",
                        assessment, task);
                    if (input.outcome() != TaskResult.SUCCEEDED)
                        return new OperationResult<Void>(input.outcome(), Optional.empty(), input.problem());
                    result.set(input.value().orElseThrow());
                    var completed = DeploymentDatabaseSourceInputs.complete(source, "sqlite-app", assessment,
                        result.get());
                    var first = reviewer.confirmSqliteInitialization(target, "api", "a".repeat(64), completed,
                        task);
                    if (first.outcome() != TaskResult.SUCCEEDED)
                        return new OperationResult<Void>(first.outcome(), Optional.empty(), first.problem());
                    var prepared = DeploymentSqliteSetup.prepare(source, "sqlite-app", completed, true, task);
                    var answers = new DeploymentStaticWorkflow.Answers(
                        Map.of("databaseMode", "NONE", "databaseDetails", ""), List.of(),
                        gold.debug.windowstolinux.mod.deployment.service.core.model.DeploymentBuildLimits
                            .defaultNonRoot(),
                        false, true);
                    var finalReview = reviewer.confirm(target, "a".repeat(64), Map.of("api", "b".repeat(64)),
                        Map.of("api", answers), Map.of("api", prepared), Optional.empty(), task);
                    return new OperationResult<Void>(finalReview.outcome(), Optional.empty(),
                        finalReview.problem());
                });
            acceptDialog(awaitDialog("审阅源码数据库声明"));
            var correction = awaitDialog("审阅源码数据库声明");
            SwingUtilities.invokeAndWait(() -> {
                assertNotNull(findTextField(correction, "state/custom.db"));
                assertNotNull(findTextField(correction, "APP_DB_PATH"));
                var sql = findTextField(correction, "schema-a.sql,missing.sql");
                assertNotNull(sql);
                sql.setText("schema-a.sql,schema-b.sql");
                captureDialog(correction, "sqlite-source-correction");
            });
            acceptDialog(correction);
            assertTrue(awaitDialog("同意在正式发布中首次初始化 SQLite").isVisible());
            var initialization = awaitDialog("同意在正式发布中首次初始化 SQLite");
            SwingUtilities.invokeAndWait(() -> captureDialog(initialization, "sqlite-initialization-confirm"));
            acceptDialog(initialization);
            var finalDialog = awaitDialog("确认部署");
            SwingUtilities.invokeAndWait(() -> captureDialog(finalDialog, "sqlite-final-confirm"));
            acceptDialog(finalDialog);
            assertEquals(TaskResult.SUCCEEDED,
                handle.completion().toCompletableFuture().get(20, TimeUnit.SECONDS).outcome());
            var completed = DeploymentDatabaseSourceInputs.complete(source, "sqlite-app", assessment, result.get());
            assertEquals("state/custom.db", completed.databases().getFirst().sqlite().orElseThrow().accessPath());
            assertEquals("APP_DB_PATH", completed.databases().getFirst().sqlite().orElseThrow().pathEnvironment());
            assertEquals(List.of("schema-a.sql", "schema-b.sql"),
                completed.databases().getFirst().initializationFiles());
            SwingUtilities.invokeAndWait(page::close);
        }
    }

    /** Locking or closing an unanswered modal invalidates its original view generation and releases the waiting worker. / 锁定或关闭未答模态框会撤销原视图代次并释放等待中的工作线程。
     * @param lock whether the owner is locked instead of closed / 是否以锁定代替关闭
     * @throws Exception if the real Swing modal or task fails / 真实 Swing 模态框或任务失败时 */
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void unansweredReviewReturnsAfterLockOrClose(boolean lock) throws Exception {
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            var context = runtime.modContexts().get(OWNER).context();
            var page = new DeploymentStaticPage(
                new gold.debug.windowstolinux.mod.deployment.service.standard.DeploymentStaticPageService(context,
                    new DeploymentStaticWorkflow(context,
                        new gold.debug.windowstolinux.mod.deployment.service.core.DeploymentRefFlow(
                            context))));
            SwingUtilities
                .invokeAndWait(() -> page.create(new UiEnvironment(Locale.SIMPLIFIED_CHINESE, "LIGHT", false)));
            var handle = context.tasks().submit(new TaskRequest("modal-review-test", "Modal review", List.of(), true),
                task -> new OperationResult<>(TaskResult.SUCCEEDED,
                    Optional.of(page.approve(0, new JPanel(), "Review", task)), Optional.empty()));
            awaitDialog();
            SwingUtilities.invokeAndWait(() -> {
                if (lock)
                    page.environmentChanged(new UiEnvironment(Locale.SIMPLIFIED_CHINESE, "LIGHT", true));
                else
                    page.close();
            });
            var result = handle.completion().toCompletableFuture().get(10, java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(TaskResult.SUCCEEDED, result.outcome());
            assertEquals(false, result.value().orElseThrow());
            if (lock)
                SwingUtilities.invokeAndWait(page::close);
            awaitServerTasks(context);
        }
    }

    /** An accepted final decision remains owned by its admitted task when the page closes. / 最终决定获接受后，页面关闭仍由已准入原任务持有。
     * @throws Exception if Swing approval or original task fails / Swing 审阅或原任务失败时 */
    @Test
    void closingAfterFinalConsentDoesNotCancelOriginalTask() throws Exception {
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            var context = runtime.modContexts().get(OWNER).context();
            var page = new DeploymentStaticPage(
                new gold.debug.windowstolinux.mod.deployment.service.standard.DeploymentStaticPageService(context,
                    new DeploymentStaticWorkflow(context,
                        new gold.debug.windowstolinux.mod.deployment.service.core.DeploymentRefFlow(
                            context))));
            SwingUtilities
                .invokeAndWait(() -> page.create(new UiEnvironment(Locale.SIMPLIFIED_CHINESE, "LIGHT", false)));
            var accepted = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var cancelled = new AtomicBoolean();
            var handle = context.tasks().submit(new TaskRequest("final-review-test", "Final review", List.of(), true),
                task -> {
                    boolean approved = page.approve(0, new JPanel(), "Final review", task, true);
                    accepted.countDown();
                    if (!release.await(10, TimeUnit.SECONDS))
                        throw new AssertionError("Original task release timed out");
                    cancelled.set(task.cancelToken().requested());
                    return new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(approved), Optional.empty());
                });
            var field = DeploymentStaticPage.class.getDeclaredField("currentTask");
            field.setAccessible(true);
            field.set(page, handle);
            try {
                var dialog = awaitDialog("Final review");
                SwingUtilities.invokeAndWait(() -> {
                    var pane = findOptionPane(dialog);
                    assertNotNull(pane);
                    pane.setValue(JOptionPane.OK_OPTION);
                    dialog.dispose();
                });
                assertTrue(accepted.await(10, TimeUnit.SECONDS));
                SwingUtilities.invokeAndWait(page::close);
            } finally {
                release.countDown();
            }
            var result = handle.completion().toCompletableFuture().get(10, TimeUnit.SECONDS);
            assertEquals(TaskResult.SUCCEEDED, result.outcome());
            assertTrue(result.value().orElseThrow());
            assertFalse(cancelled.get());
            awaitServerTasks(context);
        }
    }

    private static void awaitDialog() throws Exception {
        awaitDialog("Review");
    }

    private static JDialog awaitDialog(String title) throws Exception {
        long until = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        var visible = new AtomicReference<JDialog>();
        while (System.nanoTime() < until) {
            SwingUtilities.invokeAndWait(() -> visible.set(Arrays.stream(Window.getWindows())
                .filter(window -> window instanceof JDialog dialog && dialog.isVisible()
                    && dialog.getTitle().equals(title))
                .map(window -> (JDialog) window).findFirst().orElse(null)));
            if (visible.get() != null)
                return visible.get();
            Thread.sleep(20);
        }
        fail("Original owner review dialog did not appear");
        return null;
    }

    /** Accepts the actual owned modal on the EDT. / 在 EDT 接受真实所属模态框。
     * @param dialog current visible review / 当前可见审阅
     * @throws Exception if EDT dispatch fails / EDT 派发失败时 */
    private static void acceptDialog(JDialog dialog) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            findOptionPane(dialog).setValue(JOptionPane.OK_OPTION);
            dialog.dispose();
        });
    }

    /** Finds one actual editable text field by its preserved source value. / 按保留的源码值查找真实可编辑文本字段。
     * @param root current dialog subtree / 当前对话框子树
     * @param value expected source value / 预期源码值
     * @return matching field or absent / 匹配字段或空 */
    private static JTextField findTextField(java.awt.Container root, String value) {
        for (var child : root.getComponents()) {
            if (child instanceof JTextField field && field.getText().equals(value))
                return field;
            if (child instanceof java.awt.Container nested) {
                var found = findTextField(nested, value);
                if (found != null)
                    return found;
            }
        }
        return null;
    }

    /** Captures the displayed owned modal at its actual laid-out size. / 以真实布局尺寸捕获已显示的所属模态框。
     * @param dialog visible owner modal / 可见所属模态框
     * @param name evidence name / 证据名 */
    private static void captureDialog(JDialog dialog, String name) {
        String directory = System.getProperty("static.page.screenshot.dir", "");
        if (directory.isBlank())
            return;
        try {
            Path destination = Path.of(directory, name + ".png");
            Files.createDirectories(destination.getParent());
            var image = new BufferedImage(dialog.getWidth(), dialog.getHeight(), BufferedImage.TYPE_INT_RGB);
            var graphics = image.createGraphics();
            try {
                dialog.printAll(graphics);
            } finally {
                graphics.dispose();
            }
            ImageIO.write(image, "png", destination.toFile());
        } catch (Exception failure) {
            throw new AssertionError("Actual owner modal could not render", failure);
        }
    }

    private static JOptionPane findOptionPane(java.awt.Container root) {
        for (var child : root.getComponents()) {
            if (child instanceof JOptionPane pane)
                return pane;
            if (child instanceof java.awt.Container nested) {
                var found = findOptionPane(nested);
                if (found != null)
                    return found;
            }
        }
        return null;
    }

    /** Finds the actual modal password control. / 查找真实模态密码控件。
     * @param root dialog tree / 对话框树
     * @return password field or absent / 密码字段或空 */
    private static JPasswordField findPassword(java.awt.Container root) {
        for (var child : root.getComponents()) {
            if (child instanceof JPasswordField field)
                return field;
            if (child instanceof java.awt.Container nested) {
                var found = findPassword(nested);
                if (found != null)
                    return found;
            }
        }
        return null;
    }

    /** Reads the actual admitted secret handle after the prompt is visible. / 在提示可见后读取实际已准入秘密句柄。
     * @param page original page / 原页面
     * @return original secret task / 原秘密任务
     * @throws Exception if no task was admitted / 无任务准入时 */
    private static TaskHandle<?> awaitCreation(DeploymentStaticPage page) throws Exception {
        var controlsField = DeploymentStaticPage.class.getDeclaredField("secretControls");
        controlsField.setAccessible(true);
        var controls = controlsField.get(page);
        var creation = DeploymentStaticSecretControls.class.getDeclaredField("creation");
        creation.setAccessible(true);
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < until) {
            var handle = (TaskHandle<?>) creation.get(controls);
            if (handle != null)
                return handle;
            Thread.sleep(20);
        }
        fail("Actual secret task was not admitted");
        return null;
    }

    /** Waits for the page's observed saved reference, not just dialog disposal. / 等待页面观察到已保存引用而非仅等对话框关闭。
     * @param page current page / 当前页面
     * @param reference expected business revision / 预期业务修订
     * @throws Exception if no saved choice is observed / 未观察到已保存选项时 */
    private static void awaitSecretChoice(DeploymentStaticPage page, DeploymentSecretRef reference)
        throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < until) {
            if (page.secretChoices().stream().anyMatch(item -> item.reference().equals(reference)))
                return;
            Thread.sleep(20);
        }
        fail("Page did not observe the saved secret revision");
    }

    private final List<JFrame> layoutOwners = new ArrayList<>();

    @org.junit.jupiter.api.AfterEach
    void disposeLayoutOwners() throws Exception {
        SwingUtilities.invokeAndWait(() -> layoutOwners.forEach(JFrame::dispose));
    }

    private JPanel advancedControls(JComponent page) throws Exception {
        var result = new AtomicReference<JPanel>();
        Runnable open = () -> {
            if (SwingUtilities.getWindowAncestor(page) == null) {
                var owner = new JFrame("Isolated page layout");
                owner.setContentPane(page);
                owner.setSize(1000, 760);
                layoutOwners.add(owner);
            }
            ((JButton) namedControl(page, "deployment.advanced")).doClick(0);
            var owner = SwingUtilities.getWindowAncestor(page);
            result.set((JPanel) namedControl(owner.getOwnedWindows(), "deployment.advanced.content"));
        };
        if (SwingUtilities.isEventDispatchThread())
            open.run();
        else
            SwingUtilities.invokeAndWait(open);
        return result.get();
    }

    private static java.awt.Component namedControl(java.awt.Component[] roots, String name) {
        for (var root : roots) {
            var found = namedControl(root, name);
            if (found != null)
                return found;
        }
        return null;
    }

    private static java.awt.Component namedControl(java.awt.Component root, String name) {
        if (name.equals(root.getName()))
            return root;
        return root instanceof java.awt.Container container ? namedControl(container.getComponents(), name) : null;
    }

    private static JButton findButton(java.awt.Container root, String text) {
        for (var child : root.getComponents()) {
            if (child instanceof JButton button && text.equals(button.getText()))
                return button;
            if (child instanceof java.awt.Container nested) {
                var found = findButton(nested, text);
                if (found != null)
                    return found;
            }
        }
        return null;
    }

    private static JButton awaitStart(JComponent root) throws Exception {
        long until = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        var enabled = new AtomicReference<JButton>();
        while (System.nanoTime() < until) {
            SwingUtilities.invokeAndWait(() -> {
                var server = (JComboBox<?>) namedControl(root, "deployment.server");
                var button = findButton(root, "开始静态 / 辅助部署");
                if (server == null || button == null || server.getItemCount() == 0 || !server.isEnabled()) return;
                if (server.getSelectedItem() == null) {
                    assertFalse(button.isEnabled(), "A populated server list must not implicitly select a target");
                    server.setSelectedIndex(0);
                }
                if (button.isEnabled()) enabled.set(button);
            });
            if (enabled.get() != null) return enabled.get();
            Thread.sleep(20);
        }
        fail("Actual server-list task did not populate the page");
        return null;
    }

    private static void awaitControlEnabled(JComponent root, String name) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        var enabled = new AtomicBoolean();
        while (System.nanoTime() < until) {
            SwingUtilities.invokeAndWait(() -> {
                var actual = namedControl(root, name);
                enabled.set(actual != null && actual.isEnabled());
            });
            if (enabled.get()) return;
            Thread.sleep(20);
        }
        fail("Original control did not become available: " + name);
    }

    private static void awaitLocalLocation(JComponent root, String uri) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        var present = new AtomicBoolean();
        while (System.nanoTime() < until) {
            SwingUtilities.invokeAndWait(() -> present.set(uri.equals(((JTextField) namedControl(root, "deployment.local.location")).getText())));
            if (present.get()) return;
            Thread.sleep(20);
        }
        fail("Original metadata task did not install the exact location");
    }

    private static void awaitServerTasks(gold.debug.windowstolinux.appapi.mod.ModContext context) throws Exception {
        long until = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < until) {
            var tasks = context.tasks().list(Optional.empty(), 200).items();
            if (tasks.stream()
                .filter(item -> item.type().equals("deployment-server-list")
                    || item.type().equals("deployment-secret-list") || item.type().equals("deployment-project-list")
                    || item.type().equals("deployment-source-selection"))
                .allMatch(item -> item.state() == TaskState.ENDED))
                return;
            Thread.sleep(20);
        }
        fail("Original server-list tasks did not release their Mod occupancy");
    }

    private static JTextField inputAfterLabel(java.awt.Container root, String text) {
        var label = findLabel(root, text);
        assertNotNull(label, "Actual secret input label is missing: " + text);
        var siblings = label.getParent().getComponents();
        for (int index = 0; index + 1 < siblings.length; index++)
            if (siblings[index] == label) {
                assertInstanceOf(JTextField.class, siblings[index + 1], "Label must identify the actual secret input");
                return (JTextField) siblings[index + 1];
            }
        throw new AssertionError("Actual labelled secret input is missing: " + text);
    }

    private static JLabel findLabel(java.awt.Container root, String text) {
        for (var child : root.getComponents()) {
            if (child instanceof JLabel label && text.equals(label.getText()))
                return label;
            if (child instanceof java.awt.Container nested) {
                var found = findLabel(nested, text);
                if (found != null)
                    return found;
            }
        }
        return null;
    }

    private static void render(JComponent component, String name) {
        String directory = System.getProperty("static.page.screenshot.dir", "");
        if (directory.isBlank())
            return;
        try {
            var destination = Path.of(directory, name + ".png");
            Files.createDirectories(destination.getParent());
            var window = new JFrame("Deployment static page visual check");
            window.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
            window.setContentPane(component);
            window.setSize(860, 620);
            window.setLocationByPlatform(true);
            window.setVisible(true);
            var image = new BufferedImage(860, 620, BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = image.createGraphics();
            try {
                window.printAll(graphics);
            } finally {
                graphics.dispose();
                window.dispose();
            }
            ImageIO.write(image, "png", destination.toFile());
        } catch (Exception failure) {
            throw new AssertionError("Actual Swing view could not render", failure);
        }
    }
}
