package gold.debug.windowstolinux.shared.linux.sshd.connection;

import gold.debug.windowstolinux.shared.linux.sshd.command.SshCommandExecutor;

import gold.debug.windowstolinux.shared.linux.connection.HostKeyDecision;
import gold.debug.windowstolinux.shared.linux.error.LinuxOperationException;
import gold.debug.windowstolinux.shared.linux.error.LinuxOperationFailureType;
import gold.debug.windowstolinux.shared.linux.connection.SshCredential;
import gold.debug.windowstolinux.shared.linux.connection.SshEndpoint;
import gold.debug.windowstolinux.shared.linux.protocol.ManagedHelperProtocol;
import gold.debug.windowstolinux.shared.linux.sshd.distro.generation.script.SetupScriptRenderer;
import gold.debug.windowstolinux.shared.linux.sshd.execution.protocol.helper.ManagedHelperBundle;
import gold.debug.windowstolinux.shared.linux.sshd.runtime.systemd.SystemdUnitRenderer;
import gold.debug.windowstolinux.shared.linux.transfer.RemoteWorkspace;

import gold.debug.windowstolinux.shared.model.managed.ManagedApplication;
import gold.debug.windowstolinux.shared.model.server.ServerIdentity;
import gold.debug.windowstolinux.shared.model.deployment.BuildLimitConfiguration;
import gold.debug.windowstolinux.shared.model.capability.ServerCapabilityFacts;
import gold.debug.windowstolinux.shared.model.health.HealthCheck;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.common.keyprovider.KeyIdentityProvider;
import org.apache.sshd.core.CoreModuleProperties;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.time.Duration;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SshdLinuxGatewayTest {
    @Test
    void peerClosingBeforeHostKeyVerificationRetriesThreeConnectionsThenFails() throws Exception {
        var peerFailure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var attempts = new java.util.concurrent.atomic.AtomicInteger();
        try (var listener = new java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))) {
            listener.setSoTimeout(45000);
            Thread peer = Thread.ofPlatform().name("closing-ssh-peer").start(() -> {
                try {
                    for (int attempt = 0; attempt < 3; attempt++) {
                        try (var socket = listener.accept()) {
                            attempts.incrementAndGet();
                            socket.setSoTimeout(5000);
                            socket.getOutputStream().write("SSH-2.0-WindowsToLinuxClosingPeer\r\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                            socket.getOutputStream().flush();
                            new java.io.BufferedReader(new java.io.InputStreamReader(socket.getInputStream(),
                                    java.nio.charset.StandardCharsets.US_ASCII)).readLine();
                        }
                    }
                } catch (Throwable failure) { peerFailure.set(failure); }
            });
            try {
                var credential = new SshCredential.Password("test-only".toCharArray());
                LinuxOperationException failure = assertThrows(LinuxOperationException.class, () ->
                        new SshdLinuxGateway().connect(new SshEndpoint("closing-peer", "127.0.0.1", listener.getLocalPort(), "root"),
                                credential, (endpoint, fingerprint) -> { throw new AssertionError("No host key was sent"); }));
                assertEquals(LinuxOperationFailureType.CONNECTION_FAILED.code(), failure.failure().code(), failure::toString);
            } finally {
                listener.close();
                assertTrue(peer.join(Duration.ofSeconds(6)), "Closing SSH peer did not exit");
            }
            assertEquals(null, peerFailure.get());
            assertEquals(3, attempts.get());
        }
    }

    @Test
    void rendersTheCanonicalUnitWithExactlyOneTrailingNewline() {
        ManagedApplication application = ManagedApplication.forManaged("managed-hello",
                new ServerIdentity("ubuntu-managed", "192.0.2.1", 22, "SHA256:abc123456789"),
                "a".repeat(64));

        String unit = SystemdUnitRenderer.render("ubuntu", application);

        assertTrue(unit.endsWith("WantedBy=multi-user.target\n"));
        assertFalse(unit.endsWith("\n\n"));
        assertEquals("""
                [Unit]
                Description=WindowsToLinux managed managed-hello
                After=network.target

                [Service]
                Type=simple
                User=ubuntu
                WorkingDirectory=/opt/windowstolinux/apps/managed-hello/current
                ExecStart=/usr/local/lib/windowstolinux/java-21 -jar /opt/windowstolinux/apps/managed-hello/current/app.jar
                Restart=on-failure
                RestartSec=5
                SuccessExitStatus=143

                [Install]
                WantedBy=multi-user.target
                """, unit);
    }

    @Test
    void keepsBothSanitizedOutputChannelsInAFailedCommandEvidence() {
        SshCommandExecutor.CommandResult result = new SshCommandExecutor.CommandResult(
                false, false, "stage output", "stage output", "diagnostic error", 1);

        assertEquals("exitCode=1, error=diagnostic error, output=stage output", result.failureEvidence());
    }

    @Test
    void requiresTarForManagedCapabilities() {
        HealthCheck.Tcp health = new HealthCheck.Tcp(8080, 5, 1);

        assertTrue(capabilities(true).supportsManagedDeployment(false, health));
        assertFalse(capabilities(false).supportsManagedDeployment(false, health));
    }

    @Test
    void transportFailureDoesNotExposeOrRetainThePassword() {
        String secret = "managed-deployment-test-secret";
        SshCredential.Password credential = new SshCredential.Password(secret.toCharArray());

        LinuxOperationException failure = assertThrows(LinuxOperationException.class, () ->
                new SshdLinuxGateway().connect(
                        new SshEndpoint("local-test", "127.0.0.1", 1, "nobody"), credential,
                        (endpoint, fingerprint) -> HostKeyDecision.REJECT
                )
        );

        assertEquals("linux.error.connectionFailed", failure.failure().userMessage().key());
        assertFalse(failure.failure().diagnostic().contains(secret));
        char[] remaining = credential.copy();
        try {
            for (char value : remaining) {
                assertEquals('\0', value);
            }
        } finally {
            Arrays.fill(remaining, '\0');
        }
    }

    @Test
    void retriesTransportConnectionsAndExistingTimeoutsButNotAuthenticationOrTrustRejection() {
        LinuxOperationException timeout = LinuxOperationException.create(LinuxOperationFailureType.AUTHENTICATION_FAILED,
                "authentication timed out", new TimeoutException("timed out"));
        LinuxOperationException rejected = LinuxOperationException.create(LinuxOperationFailureType.AUTHENTICATION_FAILED,
                "authentication rejected");

        assertTrue(SshdLinuxGateway.isTransientConnectionFailure(timeout));
        assertFalse(SshdLinuxGateway.isTransientConnectionFailure(rejected));
        assertTrue(SshdLinuxGateway.isTransientConnectionFailure(LinuxOperationException.create(
                LinuxOperationFailureType.CONNECTION_FAILED, "connection closed")));
        assertFalse(SshdLinuxGateway.isTransientConnectionFailure(LinuxOperationException.create(
                LinuxOperationFailureType.HOST_KEY_REJECTED, "changed fingerprint")));
    }

    @Test
    void recognizesOnlyTimeoutShapedTransportFailures() {
        LinuxOperationException timeout = LinuxOperationException.create(LinuxOperationFailureType.SSH_COMMAND_FAILED, "fixture",
                new java.util.concurrent.TimeoutException("fixture"));
        LinuxOperationException other = LinuxOperationException.create(LinuxOperationFailureType.SSH_COMMAND_FAILED, "fixture",
                new IllegalStateException("fixture"));

        assertTrue(SshCommandExecutor.isTransientTransportFailure(timeout));
        assertFalse(SshCommandExecutor.isTransientTransportFailure(other));
    }

    @Test
    void passwordAuthenticationDoesNotOfferAmbientDesktopKeysFirst() throws Exception {
        SshCredential.Password credential = new SshCredential.Password("managed-deployment-test-secret".toCharArray());
        SshClient client = SshdLinuxGateway.credentialScopedClient(credential);
        try {
            assertEquals(List.of("password", "keyboard-interactive"),
                    client.getUserAuthFactories().stream().map(factory -> factory.getName()).toList());
            assertSame(KeyIdentityProvider.EMPTY_KEYS_PROVIDER, client.getKeyIdentityProvider());
            assertEquals(Duration.ofSeconds(30), CoreModuleProperties.HEARTBEAT_INTERVAL.getRequired(client));
            assertEquals(3, CoreModuleProperties.HEARTBEAT_NO_REPLY_MAX.getRequired(client));
        } finally {
            credential.clear();
            client.close(true);
        }
    }

    @Test
    void rootManagementDoesNotGrantPermanentProjectSudoPermissions() {
        String installation = SetupScriptRenderer.renderHelperInstallation("root");
        assertFalse(installation.contains("NOPASSWD"));
        assertFalse(installation.contains("sudoers"));
        assertTrue(installation.contains("root-management-required"));
        assertTrue(installation.contains("windowstolinux-workspace-recovery.service"));
        assertThrows(IllegalArgumentException.class, () -> SetupScriptRenderer.renderHelperInstallation("root;evil"));
    }

    @Test
    void helperAcceptsOnlyFixedHighLevelVerbsAndDerivedControlledPaths() {
        String helper = ManagedHelperBundle.renderScript();

        assertTrue(helper.contains("candidate-create) create_candidate \"$@\""));
        assertTrue(helper.contains("rollback-deployment) rollback_deployment \"$@\""));
        assertFalse(helper.contains("rollback-previous)"));
        assertTrue(helper.contains("inspect-runtime) inspect_managed_runtime \"$@\""));
        assertTrue(helper.contains("case \"$action\" in\n    start|stop|restart|enable|disable)"));
        assertTrue(helper.contains("require_candidate \"$app\" \"$candidate_id\""));
        assertTrue(helper.contains("candidate=\"$(candidate_root \"$candidate_id\")\""));
        assertTrue(helper.contains("assert_sealed_build"));
        assertTrue(helper.indexOf("printf 'SNAPSHOT_TOKEN=%s\\n' \"$token\"")
                        < helper.indexOf("printf 'PREVIOUS=1\\n'"),
                "snapshot protocol must emit the token before the previous-release marker");
        assertTrue(helper.contains("install -o root -g root -m 555 -- \"$artifact\" \"$release/app.jar\""));
        assertTrue(helper.contains("stop_application_unit()"));
        assertTrue(helper.contains("systemctl show --value --property MainPID"));
        assertTrue(helper.contains("reject stop-incomplete"));
        assertFalse(helper.contains("eval "));
        assertFalse(helper.contains("exec \"$@\""));
        assertFalse(helper.contains("/bin/bash -c \"$@\""));
        assertFalse(helper.contains("\"$engine\" inspect"));
        assertFalse(helper.contains("Substring"));
        assertFalse(helper.contains("FullyQualifiedErrorId"));
        assertTrue(helper.stripTrailing().endsWith("esac"), "helper resource must end at the allowlisted verb switch");
        assertFalse(helper.contains("sudo -n"));
    }

    private static ServerCapabilityFacts capabilities(boolean tarAvailable) {
        return new ServerCapabilityFacts("Ubuntu 24.04.1 LTS", "x86_64", true, true, true, tarAvailable,
                true, true, true, true, ManagedHelperProtocol.VERSION, 1024L * 1024 * 1024, "test capabilities");
    }

}
