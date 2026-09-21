package gold.debug.windowstolinux.shared.linux.sshd.session;

import gold.debug.windowstolinux.shared.linux.connection.SshEndpoint;
import gold.debug.windowstolinux.shared.linux.error.LinuxOperationException;
import gold.debug.windowstolinux.shared.linux.protocol.database.RemoteDatabasePort;
import gold.debug.windowstolinux.shared.model.health.HealthCheck;
import gold.debug.windowstolinux.shared.model.lifecycle.*;
import gold.debug.windowstolinux.shared.model.managed.ManagedApplication;
import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSpecification;
import gold.debug.windowstolinux.shared.model.server.ServerIdentity;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.command.Command;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

@org.junit.jupiter.api.parallel.Isolated
class SshdLinuxRemoteSessionTest {
    @TempDir Path directory;
    @Test void recoveryProbeUsesOnlyAFixedCommandWithoutBashOrHelper() throws Exception {
        try (var fixture = new Fixture(directory)) {
            fixture.session.verifyConnection();
            assertEquals("printf 'WTL_SSH_READY\\n'", fixture.lastCommand);
            assertEquals(1, fixture.calls.get()); assertEquals(0, fixture.mutations);
            fixture.probeOutput = "unverified";
            assertThrows(LinuxOperationException.class, fixture.session::verifyConnection);
        }
    }

    @Test void existingCapabilitiesShareOneAuthenticatedConnectionAndFailAfterClose() throws Exception {
        try (var fixture = new Fixture(directory)) {
            var session = fixture.session;
            var databases = session.databaseOperations();
            var artifacts = session.backupArtifacts();
            var activation = session.restoreActivation();
            assertSame(databases, session.databaseOperations());
            assertSame(artifacts, session.backupArtifacts());
            assertSame(activation, session.restoreActivation());
            var request = new RemoteDatabasePort.BackupRequest("demo", new RemoteDatabasePort.ConnectionProfile.Sqlite("main", gold.debug.windowstolinux.shared.model.managed.ManagedStorageLocation.defaults(), "app.db"), false, false);
            assertTrue(databases.inspect(request).toolAvailable());
            assertTrue(artifacts.discardBackupOperation("operation-one").succeeded());
            assertTrue(activation.inspectRestoreActivation("demo", 1024).managedRootWritable());
            fixture.rejected = true;
            assertThrows(LinuxOperationException.class, () -> databases.inspect(request));
            assertThrows(LinuxOperationException.class, () -> artifacts.discardBackupOperation("operation-one"));
            assertThrows(LinuxOperationException.class, () -> activation.inspectRestoreActivation("demo", 1024));
            int calls = fixture.calls.get();
            session.close();
            assertThrows(Exception.class, () -> databases.inspect(request));
            assertThrows(Exception.class, () -> artifacts.discardBackupOperation("operation-one"));
            assertThrows(Exception.class, () -> activation.inspectRestoreActivation("demo", 1024));
            assertEquals(calls, fixture.calls.get(), "closed capabilities must not reconnect");
            assertTrue(fixture.client.isClosed());
        }
    }

    @Test void knownAndPersistedRuntimeEntriesShareActionsAndPostconditionFailures() throws Exception {
        try (var fixture = new Fixture(directory)) {
            var app = ManagedApplication.forManaged("demo", new ServerIdentity("server", "127.0.0.1", 22, "SHA256:fixture"), "a".repeat(64));
            var health = new HealthCheck.Tcp(18080, 1, 1);
            var runtime = new DeploymentRuntimeSpecification.NodeService(22, health);
            for (boolean persisted : new boolean[]{false, true}) {
                fixture.running = false; fixture.owner = true; fixture.enabled = false;
                for (var action : new LifecycleAction[]{LifecycleAction.START, LifecycleAction.RESTART,
                        LifecycleAction.STOP, LifecycleAction.ENABLE_AUTOSTART, LifecycleAction.DISABLE_AUTOSTART}) {
                    var after = persisted ? fixture.session.executeLifecycle(app, action, health)
                            : fixture.session.executeDeploymentLifecycle(app, runtime, action);
                    assertTrue(after.ownershipVerified());
                    if (action == LifecycleAction.START || action == LifecycleAction.RESTART) assertEquals(RuntimeState.RUNNING, after.runtimeState());
                    if (action == LifecycleAction.STOP) assertEquals(RuntimeState.STOPPED, after.runtimeState());
                    if (action == LifecycleAction.ENABLE_AUTOSTART) assertEquals(AutostartState.ENABLED, after.autostartState());
                    if (action == LifecycleAction.DISABLE_AUTOSTART) assertEquals(AutostartState.DISABLED, after.autostartState());
                }
                fixture.running = true;
                var start = assertThrows(LinuxOperationException.class, () -> {
                    if (persisted) fixture.session.executeLifecycle(app, LifecycleAction.START, health);
                    else fixture.session.executeDeploymentLifecycle(app, runtime, LifecycleAction.START);
                });
                assertTrue(start.getMessage().contains("stopped"));
                fixture.ignoreAction = true;
                assertThrows(LinuxOperationException.class, () -> {
                    if (persisted) fixture.session.executeLifecycle(app, LifecycleAction.STOP, health);
                    else fixture.session.executeDeploymentLifecycle(app, runtime, LifecycleAction.STOP);
                });
                fixture.ignoreAction = false; fixture.unhealthy = true;
                assertThrows(LinuxOperationException.class, () -> {
                    if (persisted) fixture.session.executeLifecycle(app, LifecycleAction.RESTART, health);
                    else fixture.session.executeDeploymentLifecycle(app, runtime, LifecycleAction.RESTART);
                });
                fixture.unhealthy = false; fixture.owner = false;
                int mutations = fixture.mutations;
                var rejected = persisted ? fixture.session.executeLifecycle(app, LifecycleAction.STOP, health)
                        : fixture.session.executeDeploymentLifecycle(app, runtime, LifecycleAction.STOP);
                assertFalse(rejected.ownershipVerified());
                assertEquals(mutations, fixture.mutations);
            }
        }
    }

    /** In-process SSH transport fixture; no host commands or Linux service are executed. / 进程内 SSH 传输夹具，不执行宿主命令或 Linux 服务。 */
    private static final class Fixture implements AutoCloseable {
        final LoopbackSshServer loopback;
        final SshServer server;
        final SshClient client = SshClient.setUpDefaultClient();
        final AtomicInteger calls = new AtomicInteger();
        final SshdLinuxRemoteSession session;
        volatile boolean rejected, running, enabled, ignoreAction, unhealthy;
        volatile boolean owner = true;
        volatile boolean failed;
        volatile boolean oldHelper, queryFailed;
        int mutations;
        volatile String lastCommand = "", probeOutput = "WTL_SSH_READY\n";

        Fixture(Path directory) throws Exception {
            loopback = new LoopbackSshServer(directory, this::configure);
            server = loopback.server();
            try {
                client.setServerKeyVerifier((connection, address, key) -> true); client.start();
                var connection = client.connect("root", "127.0.0.1", server.getPort()).verify(Duration.ofSeconds(10)).getSession();
                connection.addPasswordIdentity("fixture"); connection.auth().verify(Duration.ofSeconds(10));
                session = new SshdLinuxRemoteSession(client, connection,
                        new SshEndpoint("server", "127.0.0.1", server.getPort(), "root"), "SHA256:fixture");
            } catch (Exception | Error failure) {
                SshSessionLifecycleExecutor.closeQuietly(client);
                try { loopback.close(); } catch (Throwable cleanup) { failure.addSuppressed(cleanup); }
                throw failure;
            }
        }

        private void configure(SshServer server) {
            server.setPasswordAuthenticator((user, password, connection) -> password.equals("fixture"));
            server.setCommandFactory((channel, command) -> new Command() {
                private OutputStream output;
                private org.apache.sshd.server.ExitCallback exit;
                @Override public void setInputStream(InputStream input) { }
                @Override public void setOutputStream(OutputStream output) { this.output = output; }
                @Override public void setErrorStream(OutputStream error) { }
                @Override public void setExitCallback(org.apache.sshd.server.ExitCallback exit) { this.exit = exit; }
                @Override public void start(org.apache.sshd.server.channel.ChannelSession channel,
                        org.apache.sshd.server.Environment environment) throws IOException {
                    calls.incrementAndGet();
                    lastCommand = command;
                    output.write(reply(command).getBytes(StandardCharsets.UTF_8)); output.flush();
                    exit.onExit(rejected || (queryFailed && command.contains("observe-deployment")) ? 64 : 0);
                }
                @Override public void destroy(org.apache.sshd.server.channel.ChannelSession channel) { }
            });
        }

        private String reply(String command) {
            if (rejected) return "";
            if (command.equals("printf 'WTL_SSH_READY\\n'")) return probeOutput;
            if (command.contains("database-inspect")) return "TYPE=sqlite\nENGINE_VERSION=3.40\nTOOL_VERSION=3.40\nTOOL_AVAILABLE=1\nENGINE_COMPATIBLE=1\nONLINE_BACKUP_AVAILABLE=1\nALL_TABLES_TRANSACTIONAL=1\n";
            if (command.contains("restore-preflight")) return "AVAILABLE_BYTES=1048576\nMANAGED_ROOT_WRITABLE=1\nFOREIGN_CONFLICT=0\n";
            if (command.contains("inspect-runtime")) return "KIND=deployment\n";
            if (command.contains("observe-deployment") && oldHelper) return "OWNER=1\nRUNNING=0\nENABLED=disabled\n";
            if (command.contains("observe-deployment")) return "OWNER="+(owner ? 1 : 0)+"\nRUNNING="+(running ? 1 : 0)+"\nENABLED="+(enabled ? "enabled" : "disabled")+"\n"
                    + "QUERY_OK=1\nActiveState="+(failed ? "failed" : running ? "active" : "inactive")+"\nSubState="+(failed ? "failed" : running ? "running" : "dead")
                    +"\nResult="+(failed ? "exit-code" : "success")+"\nExecMainCode=1\nExecMainStatus="+(failed ? 129 : 0)+"\nMainPID="+(running ? 123 : 0)+"\n";
            if (command.contains("lifecycle-deployment")) {
                mutations++;
                if (!ignoreAction) {
                    if (command.contains("'start'") || command.contains("'restart'")) running = true;
                    if (command.contains("'stop'")) { running = false; failed = false; }
                    if (command.contains("'enable'")) enabled = true;
                    if (command.contains("'disable'")) enabled = false;
                }
                if (command.contains("'stop'")) return "STOP_BEFORE_QUERY_OK=1\nSTOP_BEFORE_ExecMainStatus=129\nSTOP_AFTER_ExecMainStatus=129\n";
            }
            return "HEALTHY="+(unhealthy ? 0 : 1)+"\n";
        }

        @Override public void close() throws Exception {
            try (loopback) { session.close(); assertTrue(client.isClosed()); }
            assertTrue(server.isClosed());
        }
    }

    @Test void failedNativeServiceRequiresVerifiedStopBeforeRecoveryForBothEntries() throws Exception {
        try (var fixture = new Fixture(directory)) {
            var app = ManagedApplication.forManaged("demo", new ServerIdentity("server", "127.0.0.1", 22, "SHA256:fixture"), "a".repeat(64));
            var health = new HealthCheck.Tcp(18080, 1, 1);
            var runtime = new DeploymentRuntimeSpecification.NodeService(22, health);
            for (boolean persisted : new boolean[]{false, true}) {
                fixture.failed = true; fixture.running = false;
                assertEquals(RuntimeState.ERROR, fixture.session.observe(app).runtimeState());
                int mutations = fixture.mutations;
                for (var action : new LifecycleAction[]{LifecycleAction.START, LifecycleAction.RESTART, LifecycleAction.ENABLE_AUTOSTART}) {
                    assertThrows(LinuxOperationException.class, () -> {
                        if (persisted) fixture.session.executeLifecycle(app, action, health);
                        else fixture.session.executeDeploymentLifecycle(app, runtime, action);
                    });
                }
                fixture.session.executeLifecycle(app, LifecycleAction.REFRESH_STATUS, health);
                assertEquals(mutations, fixture.mutations);
                var disabled = fixture.session.executeLifecycle(app, LifecycleAction.DISABLE_AUTOSTART, health);
                assertEquals(RuntimeState.ERROR, disabled.runtimeState());
                assertEquals(AutostartState.DISABLED, disabled.autostartState());
                var stopped = persisted ? fixture.session.executeLifecycle(app, LifecycleAction.STOP, health)
                        : fixture.session.executeDeploymentLifecycle(app, runtime, LifecycleAction.STOP);
                assertEquals(RuntimeState.STOPPED, stopped.runtimeState());
                assertTrue(stopped.evidence().contains("STOP_BEFORE_ExecMainStatus=129"));
                assertEquals(RuntimeState.STOPPED, fixture.session.executeLifecycle(app, LifecycleAction.STOP, health).runtimeState());
                assertEquals(RuntimeState.RUNNING, fixture.session.executeLifecycle(app, LifecycleAction.START, health).runtimeState());
            }
        }
    }

    @Test void missingHelperFieldsAndQueryFailureCannotMasqueradeAsStopped() throws Exception {
        try (var fixture = new Fixture(directory)) {
            var app = ManagedApplication.forManaged("demo", new ServerIdentity("server", "127.0.0.1", 22, "SHA256:fixture"), "a".repeat(64));
            fixture.oldHelper = true;
            var old = fixture.session.observe(app);
            assertEquals(RuntimeState.UNKNOWN, old.runtimeState());
            assertTrue(old.evidence().contains("environment preparation"));
            assertThrows(LinuxOperationException.class, () -> fixture.session.executeLifecycle(app,
                    LifecycleAction.STOP, new HealthCheck.Tcp(18080, 1, 1)));
            fixture.oldHelper = false; fixture.queryFailed = true;
            assertEquals(RuntimeState.UNKNOWN, fixture.session.observe(app).runtimeState());
            fixture.session.executeLifecycle(app, LifecycleAction.STOP, new HealthCheck.Tcp(18080, 1, 1));
            assertEquals(0, fixture.mutations);
        }
    }
}
