package gold.debug.windowstolinux.app.main.startup;

import gold.debug.windowstolinux.shared.linux.connection.*;
import gold.debug.windowstolinux.shared.linux.sshd.command.SshCommandExecutor;
import gold.debug.windowstolinux.shared.linux.sshd.connection.SshdLinuxGateway;
import gold.debug.windowstolinux.shared.linux.sshd.session.SshSessionLifecycleExecutor;
import gold.debug.windowstolinux.shared.model.health.HealthCheck;
import gold.debug.windowstolinux.shared.model.lifecycle.*;
import gold.debug.windowstolinux.shared.model.managed.ManagedApplication;
import gold.debug.windowstolinux.shared.model.server.ServerIdentity;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.digest.BuiltinDigests;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.time.Duration;
import java.util.Objects;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in recovery of explicitly named existing Yarn releases, through the production session. */
@EnabledIfSystemProperty(named = "managed.runtime.yarn.existing", matches = ".+")
class UbuntuYarnStopAcceptanceTest {
    @Test void observesFailedReleasesThenStopsAndVerifiesIdempotenceWithoutReplacingData() throws Exception {
        String host = Objects.requireNonNull(System.getProperty("managed.ssh.host"));
        String pin = Objects.requireNonNull(System.getProperty("managed.ssh.fingerprint"));
        String password = Objects.requireNonNull(System.getenv("WINDOWSTOLINUX_TEST_SSH_PASSWORD"));
        int port = Integer.getInteger("managed.ssh.port", 22);
        var endpoint = new SshEndpoint("yarn-stop-acceptance", host, port, "root");
        var identity = new ServerIdentity(endpoint.serverId(), host, port, pin);
        try (var client = SshClient.setUpDefaultClient()) {
            client.setServerKeyVerifier((session, address, key) -> pin.equals(KeyUtils.getFingerPrint(BuiltinDigests.sha256, key)));
            client.start();
            try (var connection = client.connect("root", host, port).verify(Duration.ofSeconds(20)).getSession()) {
                connection.addPasswordIdentity(password); connection.auth().verify(Duration.ofSeconds(20));
                var diagnostics = new SshCommandExecutor(connection);
                try (var session = new SshdLinuxGateway().connect(endpoint, new SshCredential.Password(password.toCharArray()),
                        (remote, fingerprint) -> pin.equals(fingerprint) ? HostKeyDecision.ACCEPT_EXISTING : HostKeyDecision.REJECT)) {
                    for (String id : System.getProperty("managed.runtime.yarn.existing").split(",")) {
                        assertTrue(id.matches("[a-z0-9][a-z0-9-]{0,62}"));
                        String root = "/opt/windowstolinux/apps/" + id;
                        var manifest = diagnostics.execProtocol("cat " + root + "/current/.windowstolinux-owner", Duration.ofSeconds(10), true);
                        assertTrue(manifest.succeeded(), manifest::failureEvidence);
                        var application = ManagedApplication.forManaged(id, identity, manifest.output().strip());
                        var release = diagnostics.execProtocol("readlink -f " + root + "/current", Duration.ofSeconds(10), true);
                        assertTrue(release.succeeded(), release::failureEvidence);
                        var before = session.observe(application);
                        assertTrue(before.ownershipVerified(), before::toString);
                        assertEquals(RuntimeState.ERROR, before.runtimeState(), before::toString);
                        System.out.println("LIVE_YARN_BEFORE " + id + " " + before.evidence());
                        for (int attempt = 1; attempt <= 2; attempt++) {
                            var stopped = session.executeLifecycle(application, LifecycleAction.STOP, new HealthCheck.Tcp(18080, 1, 1));
                            assertTrue(stopped.ownershipVerified()); assertEquals(RuntimeState.STOPPED, stopped.runtimeState());
                            if (attempt == 1) assertTrue(stopped.evidence().contains("STOP_BEFORE_ExecMainStatus=129"), stopped::toString);
                            String unit = application.systemdUnit();
                            String script = "set -eu\nunit=" + SshCommandExecutor.quote(unit) + "\n"
                                    + "test \"$(systemctl show --value --property ActiveState \"$unit\")\" = inactive\n"
                                    + "test \"$(systemctl show --value --property MainPID \"$unit\")\" = 0\n"
                                    + "group=$(systemctl show --value --property ControlGroup \"$unit\")\n"
                                    + "if [ -n \"$group\" ] && [ -d \"/sys/fs/cgroup$group\" ]; then pids=$(find \"/sys/fs/cgroup$group\" -name cgroup.procs -exec cat {} +); test -z \"$pids\"; fi\n"
                                    + "test \"$(readlink -f " + root + "/current)\" = " + SshCommandExecutor.quote(release.output().strip()) + "\n"
                                    + "printf 'ACTIVE=inactive\\nMainPID=0\\nCGROUP_EMPTY=1\\nRELEASE_UNCHANGED=1\\n'\n";
                            var checked = diagnostics.execProtocol(script, Duration.ofSeconds(20), true);
                            assertTrue(checked.succeeded(), checked::failureEvidence);
                            System.out.println("LIVE_YARN_STOP " + id + " attempt=" + attempt + " " + stopped.evidence() + " " + checked.output().strip());
                        }
                    }
                } finally { SshSessionLifecycleExecutor.closeQuietly(connection); }
            } finally { SshSessionLifecycleExecutor.closeQuietly(client); }
        }
    }
}
