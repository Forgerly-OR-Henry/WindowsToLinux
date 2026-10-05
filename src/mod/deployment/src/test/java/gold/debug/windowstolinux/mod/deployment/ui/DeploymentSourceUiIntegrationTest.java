package gold.debug.windowstolinux.mod.deployment.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.Component;
import java.awt.Container;
import java.awt.Window;
import java.awt.datatransfer.StringSelection;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import javax.swing.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import gold.debug.windowstolinux.app.ai.model.AiCapabilityType;
import gold.debug.windowstolinux.app.ai.model.AiPurposeType;
import gold.debug.windowstolinux.app.main.start.PlatformRuntime;
import gold.debug.windowstolinux.app.service.ai.PlatformModelService;
import gold.debug.windowstolinux.app.service.mod.PlatformModIntents.RetryReview;
import gold.debug.windowstolinux.app.ui.display.DesktopThemeService;
import gold.debug.windowstolinux.app.ui.display.ThemeMode;
import gold.debug.windowstolinux.app.ui.display.ThemePalette;
import gold.debug.windowstolinux.app.ui.error.FailureReportStore;
import gold.debug.windowstolinux.app.ui.language.MessageCatalog;
import gold.debug.windowstolinux.app.ui.mod.ModPageRegistry.PageKey;
import gold.debug.windowstolinux.app.ui.mod.PlatformModsPane;
import gold.debug.windowstolinux.app.ui.task.DesktopTaskRunner;
import gold.debug.windowstolinux.app.ui.window.PlatformFrame;
import gold.debug.windowstolinux.appapi.ai.model.AiPurpose;
import gold.debug.windowstolinux.appapi.db.schema.SchemaIdentity;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.server.model.ServerInfo;
import gold.debug.windowstolinux.appapi.task.model.TaskRequest;
import gold.debug.windowstolinux.appapi.task.model.TaskState;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;
import gold.debug.windowstolinux.appapi.task.result.ProblemCode;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.appapi.ui.page.UiEnvironment;
import gold.debug.windowstolinux.mod.deployment.DeploymentRuntimeFixture;
import gold.debug.windowstolinux.mod.deployment.db.DeploymentProjectRecord;
import gold.debug.windowstolinux.mod.deployment.service.standard.DeploymentStaticSourceService;
import org.junit.jupiter.api.Test;

/** Exercises real project buttons and real platform configuration from the original CLASS workspace. / 从原CLASS工作区操作真实项目按钮及平台配置。 */
class DeploymentSourceUiIntegrationTest extends DeploymentRuntimeFixture {
    private Path picked;

    private final AtomicInteger pickerCalls = new AtomicInteger();

    @Override
    protected Path selectedSourceDirectory() {
        pickerCalls.incrementAndGet();
        return picked;
    }

    @Test
    void actualProjectListSelectionAndSaveButtonsReviseOnlyTheOriginalCompleteRow() throws Exception {
        picked = directory("original-source", "original frozen contents");
        var revisedDirectory = directory("revised-source", "explicit new source contents");
        enable();
        try (var runtime = open(); var desktop = new Desktop(runtime)) {
            var frame = desktop.frame;
            var name = control(frame, "deployment.sourceName", JTextField.class);
            var location = control(frame, "deployment.local.location", JTextField.class);
            var projects = control(frame, "deployment.projects", JComboBox.class);
            awaitUi(() -> projects.isEnabled());
            assertEquals(0, ui(projects::getItemCount).intValue());
            click(frame, "deployment.local.choose");
            awaitUi(() -> picked.toUri().toString().equals(location.getText()) && projects.isEnabled());
            assertEquals(1, pickerCalls.get());
            edit(() -> name.setText("Original UI project"));
            saveProject(frame);
            awaitUi(() -> projects.isEnabled() && projects.getItemCount() == 1);
            var original = projects(runtime).getFirst();
            assertEquals("Original UI project", original.displayName());
            assertEquals(picked.toRealPath().toUri(), original.source().location());
            assertTrue(original.source().sha256().matches("[0-9a-f]{64}"));

            edit(() -> buttonText(frame, "Refresh projects").doClick(0));
            awaitUi(projects::isEnabled);
            edit(() -> {
                projects.setSelectedItem(null);
                name.setText("unsaved name that selection must replace");
                projects.setSelectedIndex(0);
                assertEquals(original.displayName(), name.getText());
                assertEquals(original.source().location().toString(), location.getText());
                assertTrue(control(frame, "deployment.project.saveOnPublication", JCheckBox.class).isSelected());
            });
            click(frame, "deployment.local.clear");
            edit(() -> {
                assertEquals("", location.getText());
                assertTrue(location.getTransferHandler().importData(new TransferHandler.TransferSupport(location,
                    new StringSelection(revisedDirectory.toAbsolutePath().toString()))));
            });
            awaitUi(() -> revisedDirectory.toUri().toString().equals(location.getText()) && projects.isEnabled());
            edit(() -> name.setText("Revised original UI project"));
            saveProject(frame);
            awaitUi(() -> projects.isEnabled() && projects.getItemCount() == 1);
            var rows = projects(runtime);
            assertEquals(1, rows.size(), "The actual revise button must not create another project identity");
            var revised = rows.getFirst();
            assertEquals(original.id(), revised.id());
            assertEquals(original.createdAt(), revised.createdAt());
            assertTrue(revised.updatedAt().isAfter(original.updatedAt()));
            assertEquals("Revised original UI project", revised.displayName());
            assertEquals(revisedDirectory.toRealPath().toUri(), revised.source().location());
            assertNotEquals(original.source().sha256(), revised.source().sha256());
            assertEquals(1, pickerCalls.get(), "Imported explicit metadata must use Path admission, not reopen the host picker");
            edit(() -> {
                projects.setSelectedItem(null);
                projects.setSelectedIndex(0);
                assertEquals(revised.displayName(), name.getText());
                assertEquals(revised.source().location().toString(), location.getText());
            });
            assertNoDeployment(runtime);
        }
    }

    @Test
    void emptyConfigurationOpensActualPlatformEditorsAndReturnsToTheSameDraftWithExplicitExactTarget() throws Exception {
        picked = directory("preselected-source", "retained source draft");
        enable();
        try (var peer = new CapabilityPeer(); var runtime = open(); var desktop = new Desktop(runtime)) {
            var frame = desktop.frame;
            var models = desktop.models;
            var context = runtime.modContexts().get(OWNER).context();
            var name = control(frame, "deployment.sourceName", JTextField.class);
            var location = control(frame, "deployment.local.location", JTextField.class);
            var servers = control(frame, "deployment.server", JComboBox.class);
            awaitUi(() -> servers.isEnabled() && control(frame, "deployment.projects", JComboBox.class).isEnabled());
            assertEquals(0, ui(servers::getItemCount).intValue());
            assertNull(ui(servers::getSelectedItem));
            assertFalse(ui(() -> deploymentStart(frame).isEnabled()));
            assertTrue(models.listAiConfigurations().isEmpty());
            assertUnavailableRoute(context);
            click(frame, "deployment.local.choose");
            awaitUi(() -> picked.toUri().toString().equals(location.getText()));
            assertFalse(ui(() -> deploymentStart(frame).isEnabled()), "A real preselection cannot replace the missing explicit target");
            edit(() -> name.setText("original draft across platform configuration"));
            click(frame, "deployment.platform.servers");
            assertEquals("platform.servers", ui(frame::currentPage).platformId());
            assertEquals(0, ui(() -> control(frame, "servers.table", JTable.class).getRowCount()).intValue());
            asyncClick(frame, "servers.add");
            var serverEditor = window("server.editor");
            edit(() -> {
                control(serverEditor, "server.name", JTextField.class).setText("Explicit original target");
                control(serverEditor, "server.host", JTextField.class).setText("127.0.0.1");
                control(serverEditor, "server.port", JTextField.class).setText("22");
                control(serverEditor, "server.user", JTextField.class).setText("fixture-user");
                control(serverEditor, "server.password", JPasswordField.class).setText("server-secret-sentinel");
                control(serverEditor, "server.save", AbstractButton.class).doClick(0);
            });
            awaitUi(() -> !serverEditor.isDisplayable() && control(frame, "servers.table", JTable.class).getRowCount() == 1);
            var stored = runtime.servers().list().getFirst().profile();
            returnToDraft(frame, name, location);
            click(frame, "deployment.server.refresh");
            awaitUi(() -> servers.isEnabled() && servers.getItemCount() == 1);
            edit(() -> {
                assertNull(servers.getSelectedItem(), "Refresh must not automatically choose the first configured server");
                servers.setSelectedIndex(0);
                var chosen = (ServerInfo) servers.getSelectedItem();
                assertEquals(stored.id(), chosen.ref().id());
                assertTrue(chosen.trustedFingerprint().isEmpty());
                assertTrue(chosen.observedAt().isEmpty());
                var summary = control(frame, "deployment.server.summary", JTextArea.class).getText();
                assertTrue(summary.contains(stored.id()));
                assertTrue(summary.contains("Not observed"));
                assertFalse(summary.contains("server-secret-sentinel"));
            });
            click(frame, "deployment.server.clear");
            assertFalse(ui(() -> deploymentStart(frame).isEnabled()));
            click(frame, "deployment.server.refresh");
            awaitUi(() -> servers.isEnabled());
            edit(() -> {
                assertNull(servers.getSelectedItem());
                servers.setSelectedIndex(0);
            });

            click(frame, "deployment.platform.models");
            assertEquals("platform.models", ui(frame::currentPage).platformId());
            awaitUi(() -> control(frame, "ai.models.add", AbstractButton.class).isEnabled());
            assertEquals(0, ui(() -> control(frame, "ai.inventory", JList.class).getModel().getSize()).intValue());
            asyncClick(frame, "ai.models.add");
            var modelEditor = window("platform.model.editor");
            edit(() -> {
                control(modelEditor, "model.name", JTextField.class).setText("Actual configured model");
                control(modelEditor, "model.endpoint", JTextField.class).setText(peer.endpoint().toString());
                control(modelEditor, "model.model", JTextField.class).setText("fixture-text");
                control(modelEditor, "model.limit", JTextField.class).setText("128");
                control(modelEditor, "model.key", JPasswordField.class).setText("model-key-sentinel");
                control(modelEditor, "model.save", AbstractButton.class).doClick(0);
            });
            awaitUi(() -> control(modelEditor, "model.verify", AbstractButton.class).isEnabled());
            assertEquals(0, peer.requests.get(), "Saving configuration must not perform a model invocation");
            var saved = models.listAiConfigurations().getFirst();
            assertFalse(saved.verified(AiCapabilityType.TEXT));
            edit(() -> {
                control(modelEditor, "model.capability", JComboBox.class).setSelectedItem(AiCapabilityType.TEXT);
                control(modelEditor, "model.verify", AbstractButton.class).doClick(0);
            });
            awaitUi(() -> !modelEditor.isDisplayable());
            assertEquals(1, peer.requests.get());
            assertNull(peer.failure.get(), "The real platform probe must be the only request sent to the controlled peer");
            assertTrue(models.listAiConfigurations().getFirst().verified(AiCapabilityType.TEXT));
            awaitUi(() -> control(frame, "ai.inventory", JList.class).getModel().getSize() == 1
                && control(frame, "ai.open.DEPLOYMENT", AbstractButton.class).isEnabled());
            assignPurpose(frame, models, AiPurposeType.DEPLOYMENT, saved.profile().id());
            assignPurpose(frame, models, AiPurposeType.APPROVAL, saved.profile().id());
            assertConfiguredRoute(context, AiPurpose.LANGUAGE);
            assertConfiguredRoute(context, AiPurpose.APPROVAL);
            assertEquals(1, peer.requests.get(), "Purpose configuration and owner route reads must not invoke a model");
            returnToDraft(frame, name, location);
            click(frame, "deployment.server.refresh");
            awaitUi(() -> servers.isEnabled());
            edit(() -> assertEquals(stored.id(), ((ServerInfo) servers.getSelectedItem()).ref().id()));
            assertEquals(1, pickerCalls.get());
            assertNoDeployment(runtime);
        }
    }

    private Path directory(String name, String contents) throws Exception {
        var directory = Files.createDirectories(root.resolve(name));
        Files.writeString(directory.resolve("source.txt"), contents);
        return directory.toRealPath();
    }

    private static List<DeploymentProjectRecord> projects(PlatformRuntime runtime) throws Exception {
        var handle = new DeploymentStaticSourceService(runtime.modContexts().get(OWNER).context()).projects();
        var result = handle.completion().toCompletableFuture().get(15, TimeUnit.SECONDS);
        assertEquals(TaskResult.SUCCEEDED, result.outcome(), result.problem().toString());
        assertEquals(TaskState.ENDED, handle.snapshot().state());
        return result.value().orElseThrow();
    }

    private static void saveProject(PlatformFrame frame) throws Exception {
        SwingUtilities.invokeLater(() -> buttonText(frame, "Save source project").doClick(0));
        var dialog = dialog("Save source project");
        edit(() -> {
            var option = descendants(dialog).filter(JOptionPane.class::isInstance).map(JOptionPane.class::cast)
                .findFirst().orElseThrow();
            option.setValue(JOptionPane.OK_OPTION);
            dialog.dispose();
        });
    }

    private static void returnToDraft(PlatformFrame frame, JTextField name, JTextField location) throws Exception {
        edit(() -> rootButton(frame).doClick(0));
        awaitUi(() -> name.isShowing());
        edit(() -> {
            assertSame(name, control(frame, "deployment.sourceName", JTextField.class));
            assertSame(location, control(frame, "deployment.local.location", JTextField.class));
            assertEquals("original draft across platform configuration", name.getText());
            assertFalse(location.getText().isBlank());
        });
    }

    private static void assignPurpose(PlatformFrame frame, PlatformModelService models, AiPurposeType purpose,
                                      String modelId) throws Exception {
        click(frame, "ai.open." + purpose.name());
        edit(() -> {
            control(frame, "ai.inventory", JList.class).setSelectedIndex(0);
            var add = descendants(frame).filter(AbstractButton.class::isInstance).map(AbstractButton.class::cast)
                .filter(button -> "Add to this purpose".equals(button.getAccessibleContext().getAccessibleName()))
                .findFirst().orElseThrow();
            assertTrue(add.isEnabled(), "Only the actually verified model can be assigned");
            add.doClick(0);
            assertEquals(1, control(frame, "ai.purpose", JList.class).getModel().getSize());
            control(frame, "ai.purpose.save", AbstractButton.class).doClick(0);
        });
        awaitUi(() -> !DesktopTaskRunner.hasActiveTasks()
            && control(frame, "ai.open." + purpose.name(), AbstractButton.class).isEnabled()
            && descendants(frame).noneMatch(component -> "ai.purpose".equals(component.getName())
            || "ai.purpose.save".equals(component.getName())));
        var members = models.listAiPurpose(purpose);
        assertEquals(1, members.size());
        assertEquals(modelId, members.getFirst().profileId());
        assertTrue(members.getFirst().enabled());
    }

    private static void assertUnavailableRoute(ModContext context) throws Exception {
        var handle = context.tasks().submit(new TaskRequest("source-ui-route-read", "Read original missing model route", List.of(), true), task -> {
            var refusal = assertThrows(ApiException.class, () -> context.ai().configurationRevision(AiPurpose.LANGUAGE, task));
            assertEquals(ProblemCode.UNAVAILABLE, refusal.problem().code());
            assertEquals("AI_PURPOSE_UNAVAILABLE", refusal.problem().eventCode());
            return new OperationResult<Void>(TaskResult.SUCCEEDED, Optional.empty(), Optional.empty());
        });
        assertEquals(TaskResult.SUCCEEDED, handle.completion().toCompletableFuture().get(15, TimeUnit.SECONDS).outcome());
        assertEquals(TaskState.ENDED, handle.snapshot().state());
    }

    private static void assertConfiguredRoute(ModContext context, AiPurpose purpose) throws Exception {
        var handle = context.tasks().submit(new TaskRequest("source-ui-route-read", "Read original configured model route", List.of(), true), task -> {
            return new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(context.ai().configurationRevision(purpose, task)), Optional.empty());
        });
        var result = handle.completion().toCompletableFuture().get(15, TimeUnit.SECONDS);
        assertEquals(TaskResult.SUCCEEDED, result.outcome(), result.problem().toString());
        assertTrue(result.value().orElseThrow().matches("[0-9a-f]{64}"));
        assertEquals(TaskState.ENDED, handle.snapshot().state());
    }

    private void assertNoDeployment(PlatformRuntime runtime) throws Exception {
        assertEquals(0, scalar(runtime, "SELECT COUNT(*) FROM platform_task WHERE task_type IN ('deployment-publication','deployment-agent')"));
        var context = runtime.modContexts().get(OWNER).context();
        var handle = context.tasks().submit(new TaskRequest("source-ui-business-read", "Read original untouched business ledger", List.of(), true), task -> {
            try (var connection = context.db().openConnection(task); var statement = connection.createStatement();
                 var rows = statement.executeQuery("SELECT COUNT(*) FROM deployment_execution")) {
                assertTrue(rows.next());
                assertEquals(0, rows.getLong(1));
            }
            return new OperationResult<Void>(TaskResult.SUCCEEDED, Optional.empty(), Optional.empty());
        });
        assertEquals(TaskResult.SUCCEEDED, handle.completion().toCompletableFuture().get(15, TimeUnit.SECONDS).outcome());
    }

    private static AbstractButton rootButton(PlatformFrame frame) {
        return descendants(control(frame, "mods.navigation", Container.class)).filter(AbstractButton.class::isInstance)
            .map(AbstractButton.class::cast).filter(button -> button.getClientProperty("mod.pageKey") instanceof PageKey key
                && key.ownerId().equals(OWNER) && key.pageId().equals("workspace")).findFirst().orElseThrow();
    }

    private static AbstractButton buttonText(Container root, String text) {
        return descendants(root).filter(AbstractButton.class::isInstance).map(AbstractButton.class::cast)
            .filter(button -> text.equals(button.getText())).findFirst().orElseThrow();
    }

    private static AbstractButton deploymentStart(Container root) {
        return descendants(root).filter(AbstractButton.class::isInstance).map(AbstractButton.class::cast)
            .filter(button -> Boolean.TRUE.equals(button.getClientProperty("deployment.style.primary")))
            .findFirst().orElseThrow();
    }

    private static void click(Container root, String name) throws Exception {
        edit(() -> {
            var button = control(root, name, AbstractButton.class);
            assertTrue(button.isEnabled(), name);
            button.doClick(0);
        });
    }

    private static void asyncClick(Container root, String name) {
        SwingUtilities.invokeLater(() -> {
            var button = control(root, name, AbstractButton.class);
            assertTrue(button.isEnabled(), name);
            button.doClick(0);
        });
    }

    private static <T extends Component> T control(Container root, String name, Class<T> type) {
        return descendants(root).filter(component -> name.equals(component.getName())).map(type::cast)
            .findFirst().orElseThrow(() -> new AssertionError("Actual control missing: " + name));
    }

    private static Stream<Component> descendants(Component root) {
        return Stream.concat(Stream.of(root), root instanceof Container container
            ? Stream.of(container.getComponents()).flatMap(DeploymentSourceUiIntegrationTest::descendants) : Stream.empty());
    }

    private static Window window(String name) throws Exception {
        awaitUi(() -> Stream.of(Window.getWindows()).anyMatch(window -> window.isShowing() && name.equals(window.getName())));
        return ui(() -> Stream.of(Window.getWindows()).filter(window -> window.isShowing() && name.equals(window.getName()))
            .findFirst().orElseThrow());
    }

    private static JDialog dialog(String title) throws Exception {
        awaitUi(() -> Stream.of(Window.getWindows()).anyMatch(window -> window instanceof JDialog dialog
            && dialog.isShowing() && title.equals(dialog.getTitle())));
        return ui(() -> Stream.of(Window.getWindows()).filter(JDialog.class::isInstance).map(JDialog.class::cast)
            .filter(dialog -> dialog.isShowing() && title.equals(dialog.getTitle())).findFirst().orElseThrow());
    }

    private static void awaitUi(Callable<Boolean> condition) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        do {
            if (ui(condition)) return;
            Thread.sleep(20);
        } while (System.nanoTime() < until);
        fail("Actual interface did not reach its expected state within the existing bound");
    }

    private static void edit(Runnable action) throws Exception {
        ui(() -> {
            action.run();
            return null;
        });
    }

    private static <T> T ui(Callable<T> action) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) return action.call();
        var value = new AtomicReference<T>();
        var failure = new AtomicReference<Throwable>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                value.set(action.call());
            } catch (Throwable problem) {
                failure.set(problem);
            }
        });
        if (failure.get() instanceof Exception problem) throw problem;
        if (failure.get() instanceof Error problem) throw problem;
        return value.get();
    }

    /** Owns only the real frame and the exact newly observed page workers. / 仅持有真实窗口及精确新观测到的页面工作线程。 */
    private final class Desktop implements AutoCloseable {
        private final PlatformRuntime runtime;

        private final PlatformFrame frame;

        private final PlatformModelService models;

        private final Set<Thread> originalThreads = Set.copyOf(Thread.getAllStackTraces().keySet());

        private Desktop(PlatformRuntime runtime) throws Exception {
            this.runtime = runtime;
            models = runtime.models();
            var servers = runtime.servers();
            frame = ui(() -> {
                DesktopThemeService.install(ThemeMode.LIGHT, 100);
                var window = new PlatformFrame(runtime.security(), MessageCatalog.forLanguageTag("en"), ThemePalette.light(),
                    new UiEnvironment(Locale.ENGLISH, "LIGHT", false), FailureReportStore.disabled());
                window.onExit(runtime);
                window.installServers(servers);
                window.installModels(models);
                window.setVisible(true);
                return window;
            });
            try {
                var report = runtime.startDevelopmentMods(workspace(), frame.pages());
                assertTrue(report.problems().isEmpty(), report.problems().toString());
                var intents = runtime.modIntents();
                ui(() -> {
                    frame.installMods(intents, new PlatformModsPane.SchemaActions() {
                        public RetryReview review(String owner, Optional<SchemaIdentity> source) throws Exception {
                            return runtime.modRecovery().review(owner, source);
                        }

                        public TaskResult retry(RetryReview review) throws Exception {
                            return runtime.modRecovery().retry(review);
                        }

                        public TaskResult reconcile(UUID operation) throws Exception {
                            return runtime.modRecovery().reconcile(operation);
                        }
                    });
                    return null;
                });
                frame.pages().navigateView(new PageKey(OWNER, "workspace"), "static-deployment");
                awaitUi(() -> descendants(frame).anyMatch(component -> "deployment.sourceName".equals(component.getName()) && component.isShowing())
                    && descendants(frame).filter(AbstractButton.class::isInstance).map(AbstractButton.class::cast)
                    .anyMatch(button -> button.getClientProperty("mod.pageKey") instanceof PageKey key && key.ownerId().equals(OWNER)));
            } catch (Exception | Error failure) {
                try {
                    close();
                } catch (Exception | Error cleanup) {
                    failure.addSuppressed(cleanup);
                }
                throw failure;
            }
        }

        @Override
        public void close() throws Exception {
            var workers = Thread.getAllStackTraces().keySet().stream().filter(thread -> !originalThreads.contains(thread)
                && List.of("deployment-page-view", "deployment-agent-page", "deployment-workspace-preferences").contains(thread.getName())).toList();
            frame.pages().closeView(new PageKey(OWNER, "workspace"));
            edit(frame::dispose);
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            for (var worker : workers) {
                long remaining = until - System.nanoTime();
                assertTrue(remaining > 0);
                worker.join(Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining)));
                assertFalse(worker.isAlive(), "Only this closed frame's original page worker must have exited");
            }
            awaitUi(() -> !DesktopTaskRunner.hasActiveTasks());
            assertEquals(0, scalar(runtime, "SELECT COUNT(*) FROM platform_task WHERE owner_id='" + OWNER + "'"));
            assertEquals(0, scalar(runtime, "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL"));
        }
    }

    /** Answers the platform's fresh nonce challenge through a real local HTTP exchange. / 经真实本地HTTP交互回答平台新随机挑战。 */
    private static final class CapabilityPeer implements AutoCloseable {
        private final HttpServer server;

        private final AtomicInteger requests = new AtomicInteger();

        private final AtomicReference<Throwable> failure = new AtomicReference<>();

        private CapabilityPeer() throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/v1/chat/completions", exchange -> {
                requests.incrementAndGet();
                try (exchange) {
                    var mapper = new ObjectMapper();
                    var request = mapper.readTree(exchange.getRequestBody().readNBytes(65_537));
                    String challenge = request.path("messages").get(0).path("content").asText();
                    String prefix = "Reply with exactly this code and nothing else: ";
                    assertTrue(challenge.startsWith(prefix));
                    String nonce = challenge.substring(prefix.length());
                    assertTrue(nonce.matches("[0-9A-F]{8}"));
                    var answer = mapper.createObjectNode();
                    answer.put("model", "fixture-text");
                    var choice = answer.putArray("choices").addObject();
                    choice.put("finish_reason", "stop");
                    choice.putObject("message").put("role", "assistant").put("content", nonce);
                    byte[] bytes = mapper.writeValueAsBytes(answer);
                    exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
                    exchange.sendResponseHeaders(200, bytes.length);
                    exchange.getResponseBody().write(bytes);
                } catch (Throwable problem) {
                    failure.set(problem);
                }
            });
            server.start();
        }

        private URI endpoint() {
            return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions");
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
