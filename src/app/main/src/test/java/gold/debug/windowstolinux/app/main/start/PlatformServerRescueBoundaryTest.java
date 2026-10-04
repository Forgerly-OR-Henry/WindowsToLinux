package gold.debug.windowstolinux.app.main.start;

import static gold.debug.windowstolinux.app.main.start.PlatformTaskAdmissionIntegrationTest.*;
import static gold.debug.windowstolinux.app.main.start.PlatformWindowTest.*;
import static org.junit.jupiter.api.Assertions.*;

import java.awt.*;
import java.net.*;
import java.nio.file.Path;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;

import javax.swing.*;

import gold.debug.windowstolinux.app.linux.ssh.recover.*;
import gold.debug.windowstolinux.app.service.server.*;
import gold.debug.windowstolinux.app.ui.display.*;
import gold.debug.windowstolinux.app.ui.error.FailureReportStore;
import gold.debug.windowstolinux.app.ui.language.MessageCatalog;
import gold.debug.windowstolinux.app.ui.window.PlatformFrame;
import gold.debug.windowstolinux.appapi.linux.*;
import gold.debug.windowstolinux.appapi.linux.session.RemoteSession;
import gold.debug.windowstolinux.appapi.linux.transfer.TransferCheckpoint;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.model.TaskState;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.Problem;
import gold.debug.windowstolinux.appapi.task.result.ProblemCode;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.appapi.task.run.TaskHandle;
import gold.debug.windowstolinux.appapi.ui.page.UiEnvironment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlatformServerRescueBoundaryTest {
    @TempDir
    Path root;

    static PlatformFrame window(PlatformRuntime runtime, PlatformServerService servers) throws Exception {
        assertFalse(GraphicsEnvironment.isHeadless(), "This test requires the local desktop");
        var recovery = runtime.recovery();
        return onEdt(() -> {
            DesktopThemeService.install(ThemeMode.LIGHT);
            var frame = new PlatformFrame(runtime.security(), MessageCatalog.forLanguageTag("zh-CN"),
                ThemePalette.light(), new UiEnvironment(Locale.SIMPLIFIED_CHINESE, "LIGHT", false), FailureReportStore.disabled());
            frame.installServers(servers);
            frame.installRecovery(recovery);
            ((JTabbedPane) named(frame, "platform.pages")).setSelectedComponent(named(frame, "platform.servers"));
            frame.setVisible(true);
            return frame;
        });
    }

    static void select(PlatformFrame frame, ServerProfile profile) throws Exception {
        await(() -> named(frame, "servers.check").isEnabled());
        onEdt(() -> {
            var table = (JTable) named(frame, "servers.table");
            for (int row = 0; row < table.getRowCount(); row++) {
                if (profile.displayName().equals(table.getModel().getValueAt(table.convertRowIndexToModel(row), 0))) {
                    table.setRowSelectionInterval(row, row);
                    return null;
                }
            }
            throw new AssertionError("Selected saved server is absent");
        });
    }

    static void click(PlatformFrame frame, String name) throws Exception {
        onEdt(() -> {
            ((JButton) named(frame, name)).doClick(0);
            return null;
        });
    }

    static void dispose(PlatformFrame frame) throws Exception {
        onEdt(() -> {
            for (var owned : frame.getOwnedWindows()) owned.dispose();
            frame.dispose();
            return null;
        });
    }

    @Test
    void definiteEndedFailureOffersRescueForTheOriginalTargetAfterSelectionChanges() throws Exception {
        try (var failed = new PlatformRemoteCapabilitiesTest.Remote(root.resolve("failed-ssh"));
             var other = new ConnectionProbe();
             var runtime = PlatformRuntime.open(root.resolve("data"), PlatformStartupTest.PROTECTION)) {
            var servers = PlatformServerServiceTest.setup(runtime);
            runtime.started();
            var first = PlatformServerServiceTest.profile("failed-target-12", failed.listener.server().getPort());
            var second = PlatformServerServiceTest.profile("other-target-12", other.port());
            servers.saveServerProfile(first, "wrong-password".toCharArray());
            servers.saveServerProfile(second, "fixture-secret".toCharArray());
            var recovery = runtime.recovery();
            var frame = window(runtime, servers);
            try {
                select(frame, first);
                click(frame, "servers.check");
                await(() -> named(frame, "servers.rescueFailedCheck").isVisible()
                    && named(frame, "servers.rescueFailedCheck").isEnabled());
                assertTrue(servers.checks().isEmpty());
                assertEquals(0, count(runtime.persistence().database(), "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL"));
                int originalChecks = failed.authentications.get();
                assertTrue(originalChecks > 0);
                select(frame, second);
                click(frame, "servers.rescueFailedCheck");
                await(() -> PlatformServersWindowTest.dialog("recovery.dialog") != null);
                onEdt(() -> {
                    var dialog = PlatformServersWindowTest.dialog("recovery.dialog");
                    assertTrue(descendants(dialog).filter(JLabel.class::isInstance).map(JLabel.class::cast)
                        .anyMatch(label -> label.getText().contains(first.id())));
                    assertFalse(descendants(dialog).filter(JLabel.class::isInstance).map(JLabel.class::cast)
                        .anyMatch(label -> label.getText().contains(second.id())));
                    return null;
                });
                await(() -> recovery.pending().isEmpty() && failed.authentications.get() > originalChecks);
                assertEquals(0, other.connections.get());
                assertEquals(0, failed.commands.get());
            } finally {
                dispose(frame);
            }
        }
    }

    @Test
    void editedOriginalProfileIsRefusedBeforeAnyRescueTransportOrBrowserIsCreated() throws Exception {
        try (var first = new PlatformRemoteCapabilitiesTest.Remote(root.resolve("first-ssh"));
             var second = new ConnectionProbe();
             var runtime = PlatformRuntime.open(root.resolve("data"), PlatformStartupTest.PROTECTION)) {
            var servers = PlatformServerServiceTest.setup(runtime);
            runtime.started();
            var original = PlatformServerServiceTest.profile("server", first.listener.server().getPort());
            servers.saveServerProfile(original, "fixture-secret".toCharArray());
            var edited = PlatformServerServiceTest.profile("server", second.port());
            servers.saveServerProfile(edited, new char[0]);
            var session = runtime.recovery().start(new gold.debug.windowstolinux.app.linux.ssh.recover.SshRecovery.Server(original.id(), original.host(), original.sshPort(), original.username(), original.credentialKey(), original.displayName()));
            var handle = (TaskHandle<?>) PlatformRecoveryServiceTest.field(session, "handle");
            try {
                await(() -> handle.snapshot().state() == TaskState.ENDED || handle.snapshot().state() == TaskState.UNKNOWN);
                assertEquals(TaskState.ENDED, handle.snapshot().state(), "A known pre-transport profile rejection must end");
                var result = handle.completion().toCompletableFuture().get(6, TimeUnit.SECONDS);
                assertEquals(TaskResult.FAILED, result.outcome());
                assertEquals("RESCUE_SERVER_CHANGED", result.problem().orElseThrow().eventCode());
                assertEquals(0, first.authentications.get());
                assertEquals(0, second.connections.get());
                assertEquals(0, count(runtime.persistence().database(), "SELECT COUNT(*) FROM browser_recovery_task"));
                assertEquals(0, count(runtime.persistence().database(), "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL"));
                assertTrue(runtime.recovery().pending().isEmpty());
            } finally {
                session.close();
                if (handle.snapshot().state() == TaskState.UNKNOWN)
                    runtime.recovery().reconcile(handle.id(), true).toCompletableFuture().get(5, TimeUnit.SECONDS);
                else handle.completion().toCompletableFuture().get(6, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void cancelledOrUnconfirmedCheckDoesNotOfferAnAutomaticFailureRescueEntry() throws Exception {
        for (var code : List.of(ProblemCode.CANCELLED, ProblemCode.UNKNOWN)) {
            try (var runtime = PlatformRuntime.open(root.resolve(code.name()), PlatformStartupTest.PROTECTION)) {
                runtime.security().setupAndUnlock(PASSWORD.toCharArray(), PASSWORD.toCharArray());
                runtime.setInteractionPresenter(new PlatformRemoteCapabilitiesTest.Reviews());
                runtime.started();
                var linux = new ControlledLinux(runtime);
                linux.codes.put("controlled", code);
                try (var servers = new PlatformServerService(runtime.persistence(), runtime.security(), runtime.tasks(),
                    runtime.version(), linux, task -> {
                })) {
                    var profile = PlatformServerServiceTest.profile("controlled", 2222);
                    servers.saveServerProfile(profile, "fixture-secret".toCharArray());
                    var frame = window(runtime, servers);
                    try {
                        select(frame, profile);
                        click(frame, "servers.check");
                        await(() -> linux.contexts.containsKey(profile.id()));
                        if (code == ProblemCode.UNKNOWN)
                            await(() -> servers.checks().stream().anyMatch(task -> task.state() == TaskState.UNKNOWN));
                        else await(() -> servers.checks().isEmpty() && named(frame, "servers.refresh").isEnabled()
                            && ((JTextArea) named(frame, "servers.output")).getText().contains("取消"));
                        onEdt(() -> {
                            assertFalse(named(frame, "servers.rescueFailedCheck").isVisible());
                            assertFalse(named(frame, "servers.rescueFailedCheck").isEnabled());
                            return null;
                        });
                        assertTrue(runtime.recovery().pending().isEmpty());
                    } finally {
                        dispose(frame);
                        for (var task : servers.checks())
                            servers.reconcile(task.id()).toCompletableFuture().get(5, TimeUnit.SECONDS);
                    }
                }
            }
        }
    }

    @Test
    void obsoleteCheckAndClosedWindowCannotRestoreOldFailureOrRescueControls() throws Exception {
        for (boolean close : List.of(false, true)) {
            try (var runtime = PlatformRuntime.open(root.resolve(Boolean.toString(close)), PlatformStartupTest.PROTECTION)) {
                runtime.security().setupAndUnlock(PASSWORD.toCharArray(), PASSWORD.toCharArray());
                runtime.setInteractionPresenter(new PlatformRemoteCapabilitiesTest.Reviews());
                runtime.started();
                var linux = new ControlledLinux(runtime);
                var release = new CountDownLatch(1);
                linux.gates.put("slow", release);
                linux.codes.put("slow", ProblemCode.IO_FAILURE);
                linux.codes.put("latest", ProblemCode.CANCELLED);
                try (var servers = new PlatformServerService(runtime.persistence(), runtime.security(), runtime.tasks(),
                    runtime.version(), linux, task -> {
                })) {
                    var slow = PlatformServerServiceTest.profile("slow", 2222);
                    var latest = PlatformServerServiceTest.profile("latest", 2223);
                    servers.saveServerProfile(slow, "fixture-secret".toCharArray());
                    servers.saveServerProfile(latest, "fixture-secret".toCharArray());
                    var frame = window(runtime, servers);
                    try {
                        select(frame, slow);
                        click(frame, "servers.check");
                        await(() -> linux.contexts.containsKey(slow.id()) && named(frame, "servers.refresh").isEnabled()
                            && ((JComboBox<?>) named(frame, "servers.checks")).getItemCount() == 1);
                        String latestText;
                        if (close) {
                            dispose(frame);
                            latestText = "";
                        } else {
                            select(frame, latest);
                            click(frame, "servers.check");
                            await(() -> linux.contexts.containsKey(latest.id()) && named(frame, "servers.refresh").isEnabled()
                                && ((JTextArea) named(frame, "servers.output")).getText().contains("取消"));
                            latestText = onEdt(() -> ((JTextArea) named(frame, "servers.output")).getText());
                        }
                        release.countDown();
                        await(() -> servers.checks().isEmpty());
                        onEdt(() -> {
                            assertEquals(latestText, ((JTextArea) named(frame, "servers.output")).getText());
                            assertFalse(named(frame, "servers.rescueFailedCheck").isVisible());
                            return null;
                        });
                        assertTrue(runtime.recovery().pending().isEmpty());
                    } finally {
                        release.countDown();
                        dispose(frame);
                        await(() -> servers.checks().isEmpty());
                    }
                }
            }
        }
    }

    /** Actual second address whose accepted TCP connections expose any unintended contact. / 用实际第二地址的 TCP 接收计数揭示任何非预期联系。 */
    static final class ConnectionProbe implements AutoCloseable {
        final ServerSocket listener = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));

        final java.util.concurrent.atomic.AtomicInteger connections = new java.util.concurrent.atomic.AtomicInteger();

        final java.util.concurrent.atomic.AtomicReference<Throwable> failure = new java.util.concurrent.atomic.AtomicReference<>();

        final ExecutorService worker = Executors.newSingleThreadExecutor();

        ConnectionProbe() throws java.io.IOException {
            worker.submit(() -> {
                while (!listener.isClosed()) {
                    try (var connection = listener.accept()) {
                        connections.incrementAndGet();
                    } catch (SocketException stopped) {
                        if (!listener.isClosed()) failure.set(stopped);
                        return;
                    } catch (java.io.IOException unexpected) {
                        failure.set(unexpected);
                        return;
                    }
                }
            });
        }

        int port() {
            return listener.getLocalPort();
        }

        @Override
        public void close() throws Exception {
            listener.close();
            worker.shutdown();
            assertTrue(worker.awaitTermination(2, TimeUnit.SECONDS), "Second-address probe did not stop");
            assertNull(failure.get());
        }
    }

    /** Controlled adapter errors surround real host tasks and window callbacks. / 用受控适配错误覆盖真实宿主任务和窗口回调。 */
    static final class ControlledLinux implements LinuxService {
        final PlatformRuntime runtime;

        final Map<String, ProblemCode> codes = new ConcurrentHashMap<>();

        final Map<String, CountDownLatch> gates = new ConcurrentHashMap<>();

        final Map<String, TaskContext> contexts = new ConcurrentHashMap<>();

        ControlledLinux(PlatformRuntime runtime) {
            this.runtime = runtime;
        }

        @Override
        public RemoteSession connect(ServerId server, TaskContext task) throws ApiException {
            runtime.tasks().requireExecuting(task, "@platform", runtime.version());
            contexts.put(server.id(), task);
            try {
                var gate = gates.get(server.id());
                if (gate != null && !gate.await(20, TimeUnit.SECONDS))
                    throw new AssertionError("Controlled check was not released");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError(interrupted);
            }
            var code = Objects.requireNonNull(codes.get(server.id()));
            throw new ApiException(new Problem(code, "CONTROLLED_CHECK_" + code.name(), "Controlled SSH result", Map.of(), Optional.empty()));
        }

        @Override
        public List<TransferCheckpoint> pendingTransfers(TaskContext task) {
            return List.of();
        }
    }
}
