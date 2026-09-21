package gold.debug.windowstolinux.shared.deploy.execution.environment;

import gold.debug.windowstolinux.shared.linux.error.LinuxOperationException;

import gold.debug.windowstolinux.shared.linux.connection.HostKeyDecision;
import gold.debug.windowstolinux.shared.linux.connection.HostKeyEvaluator;
import gold.debug.windowstolinux.shared.linux.connection.LinuxGateway;
import gold.debug.windowstolinux.shared.linux.session.LinuxRemoteSession;
import gold.debug.windowstolinux.shared.linux.runtime.HealthCheckResult;
import gold.debug.windowstolinux.shared.linux.protocol.ManagedHelperProtocol;
import gold.debug.windowstolinux.shared.linux.transfer.RemoteWorkspace;
import gold.debug.windowstolinux.shared.linux.connection.SshCredential;
import gold.debug.windowstolinux.shared.linux.connection.SshEndpoint;
import gold.debug.windowstolinux.shared.linux.transfer.SourceUploadResult;
import gold.debug.windowstolinux.shared.model.health.HealthCheck;
import gold.debug.windowstolinux.shared.model.lifecycle.LifecycleAction;
import gold.debug.windowstolinux.shared.model.lifecycle.LifecycleObservation;
import gold.debug.windowstolinux.shared.model.managed.ManagedApplication;
import gold.debug.windowstolinux.shared.model.deployment.DeploymentApprovalException;
import gold.debug.windowstolinux.shared.model.deployment.EnvironmentSetupApproval;
import gold.debug.windowstolinux.shared.model.deployment.EnvironmentSetupResult;
import gold.debug.windowstolinux.shared.model.capability.ServerCapabilityFacts;
import gold.debug.windowstolinux.shared.model.archive.SourceArchiveDescriptor;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnvironmentSetupServiceTest {
    @Test
    void recordsFailureBeforeAnyMutationForBothPreparationEntryPoints() {
        AtomicInteger connections = new AtomicInteger();
        LinuxGateway gateway = (endpoint, credential, verifier) -> {
            connections.incrementAndGet();
            credential.clear();
            throw LinuxOperationException.create(
                    gold.debug.windowstolinux.shared.linux.error.LinuxOperationFailureType.CONNECTION_FAILED,
                    "initial connection unavailable");
        };
        for (boolean systemPreparation : new boolean[] {false, true}) {
            var password = new SshCredential.Password("test-only".toCharArray());
            var approval = new EnvironmentSetupApproval("server-one", true, Instant.now());
            LinuxOperationException failure = assertThrows(LinuxOperationException.class, () -> {
                if (systemPreparation) new EnvironmentSetupService().prepare(approval, gateway, endpoint(), password,
                        acceptingHostKey(), plan -> { throw new AssertionError("no system change can be proposed before SSH"); });
                else new EnvironmentSetupService().prepare(approval, gateway, endpoint(), password, acceptingHostKey());
            });
            assertTrue(failure.environmentNotStarted());
            assertTrue(failure.completedEnvironment().isEmpty());
            assertCleared(password);
        }
        assertEquals(2, connections.get());
    }

    @Test
    void refusesAnUnconfirmedPreparationBeforeOpeningSshAndClearsTheCredential() {
        AtomicInteger connections = new AtomicInteger();
        SshCredential.Password password = new SshCredential.Password("not-reused".toCharArray());
        LinuxGateway gateway = (endpoint, credential, verifier) -> {
            connections.incrementAndGet();
            throw new AssertionError("unconfirmed preparation must not connect");
        };

        DeploymentApprovalException failure = assertThrows(DeploymentApprovalException.class, () ->
                new EnvironmentSetupService().prepare(
                        new EnvironmentSetupApproval("server-one", false, Instant.now()), gateway, endpoint(), password,
                        acceptingHostKey()
                )
        );

        assertEquals("deployment.error.confirmationRequired", failure.failure().userMessage().key());
        assertEquals(0, connections.get());
        assertCleared(password);
    }

    @Test
    void invokesOnlyTheFixedPreparationOperationAfterTargetBoundConfirmation() throws Exception {
        FakeSession session = new FakeSession();
        AtomicInteger connections = new AtomicInteger();
        LinuxGateway gateway = (endpoint, credential, verifier) -> {
            connections.incrementAndGet();
            assertEquals("server-one", endpoint.serverId());
            assertEquals(HostKeyDecision.ACCEPT_EXISTING, verifier.verify(endpoint, "SHA256:known-key"));
            return session;
        };
        SshCredential.Password password = new SshCredential.Password("not-reused".toCharArray());

        EnvironmentSetupResult result = new EnvironmentSetupService().prepare(
                new EnvironmentSetupApproval("server-one", true, Instant.now()), gateway, endpoint(), password,
                acceptingHostKey()
        );

        assertEquals(2, connections.get());
        assertTrue(session.prepared);
        assertTrue(result.capabilities().supportsManagedDeployment(false, new HealthCheck.Tcp(8080, 1, 1)));
        assertCleared(password);
    }

    @Test
    void successfulInstallationDoesNotHideAFailedFreshSshLogin() throws Exception {
        FakeSession session = new FakeSession();
        AtomicInteger connections = new AtomicInteger();
        LinuxGateway gateway = (endpoint, credential, verifier) -> {
            if (connections.incrementAndGet() == 1) return session;
            throw LinuxOperationException.create(
                    gold.debug.windowstolinux.shared.linux.error.LinuxOperationFailureType.CONNECTION_FAILED,
                    "post-install SSH unavailable");
        };
        SshCredential.Password password = new SshCredential.Password("test-only".toCharArray());
        LinuxOperationException failure = assertThrows(LinuxOperationException.class, () ->
                new EnvironmentSetupService().prepare(new EnvironmentSetupApproval("server-one", true, Instant.now()),
                        gateway, endpoint(), password, acceptingHostKey()));
        assertTrue(session.prepared);
        assertEquals("post-install SSH unavailable", failure.failure().diagnostic());
        assertTrue(failure.completedEnvironment().isPresent());
        assertEquals(2, connections.get());
        assertCleared(password);
    }

    private static SshEndpoint endpoint() {
        return new SshEndpoint("server-one", "example.test", 22, "deployer");
    }

    private static HostKeyEvaluator acceptingHostKey() {
        return (endpoint, fingerprint) -> HostKeyDecision.ACCEPT_EXISTING;
    }

    private static void assertCleared(SshCredential.Password password) {
        char[] remaining = password.copy();
        try {
            for (char value : remaining) {
                assertEquals('\0', value);
            }
        } finally {
            Arrays.fill(remaining, '\0');
        }
    }

    private static final class FakeSession implements LinuxRemoteSession {
        private boolean prepared;

        /** Performs the {@code collectCapabilities} operation. / 执行 {@code collectCapabilities} 操作。 */
        @Override
        public ServerCapabilityFacts collectCapabilities() {
            return capabilities();
        }

        /** Performs the {@code prepareEnvironment} operation. / 执行 {@code prepareEnvironment} 操作。 */
        @Override
        public EnvironmentSetupResult prepareEnvironment(EnvironmentSetupApproval approval) {
            approval.requireAcceptedFor("server-one");
            prepared = true;
            return new EnvironmentSetupResult(capabilities(), "fixed Ubuntu toolset installed");
        }

        /** Performs the {@code uploadSource} operation. / 执行 {@code uploadSource} 操作。 */
        @Override
        public SourceUploadResult uploadSource(SourceArchiveDescriptor archive, RemoteWorkspace workspace, long maxWorkspaceBytes) {
            throw unsupported();
        }

        /** Performs the {@code checkHealth} operation. / 执行 {@code checkHealth} 操作。 */
        @Override
        public HealthCheckResult checkHealth(ManagedApplication application, HealthCheck healthCheck) {
            throw unsupported();
        }

        /** Performs the {@code observe} operation. / 执行 {@code observe} 操作。 */
        @Override
        public LifecycleObservation observe(ManagedApplication application) {
            throw unsupported();
        }

        /** Performs the {@code executeLifecycle} operation. / 执行 {@code executeLifecycle} 操作。 */
        @Override
        public LifecycleObservation executeLifecycle(ManagedApplication application, LifecycleAction action,
                                                      HealthCheck healthCheck) {
            throw unsupported();
        }

        /** Closes this resource. / 关闭此资源。 */
        @Override
        public void close() {
        }

        private static UnsupportedOperationException unsupported() {
            return new UnsupportedOperationException("not part of environment preparation");
        }

        private static ServerCapabilityFacts capabilities() {
            return new ServerCapabilityFacts("Ubuntu 24.04.1 LTS", "x86_64", true, true, true, true, true, true, true, true,
                    ManagedHelperProtocol.VERSION, 10L * 1024 * 1024 * 1024, "capabilities freshly collected");
        }
    }
}
