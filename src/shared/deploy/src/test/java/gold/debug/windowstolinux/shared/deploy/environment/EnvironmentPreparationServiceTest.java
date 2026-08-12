package gold.debug.windowstolinux.shared.deploy.environment;

import gold.debug.windowstolinux.shared.linux.runtime.HealthCheckResult;
import gold.debug.windowstolinux.shared.linux.connection.HostKeyDecision;
import gold.debug.windowstolinux.shared.linux.connection.HostKeyVerifier;
import gold.debug.windowstolinux.shared.linux.connection.LinuxOperationException;
import gold.debug.windowstolinux.shared.linux.connection.LinuxGateway;
import gold.debug.windowstolinux.shared.linux.connection.LinuxRemoteSession;
import gold.debug.windowstolinux.shared.linux.protocol.ReleaseSnapshot;
import gold.debug.windowstolinux.shared.linux.build.RemoteBuildResult;
import gold.debug.windowstolinux.shared.linux.protocol.RemoteStepResult;
import gold.debug.windowstolinux.shared.linux.transfer.RemoteWorkspace;
import gold.debug.windowstolinux.shared.linux.connection.SshCredential;
import gold.debug.windowstolinux.shared.linux.connection.SshEndpoint;
import gold.debug.windowstolinux.shared.linux.transfer.UploadReceipt;
import gold.debug.windowstolinux.shared.model.deployment.BuildLimits;
import gold.debug.windowstolinux.shared.model.health.HealthCheck;
import gold.debug.windowstolinux.shared.model.lifecycle.LifecycleAction;
import gold.debug.windowstolinux.shared.model.lifecycle.LifecycleObservation;
import gold.debug.windowstolinux.shared.model.managed.ManagedApplication;
import gold.debug.windowstolinux.shared.model.message.LocalizedOperationException;
import gold.debug.windowstolinux.shared.model.deployment.EnvironmentPreparationApproval;
import gold.debug.windowstolinux.shared.model.deployment.EnvironmentPreparationResult;
import gold.debug.windowstolinux.shared.model.server.ServerCapabilities;
import gold.debug.windowstolinux.shared.model.archive.SourceArchiveDescriptor;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnvironmentPreparationServiceTest {
    @Test
    void refusesAnUnconfirmedPreparationBeforeOpeningSshAndClearsTheCredential() {
        AtomicInteger connections = new AtomicInteger();
        SshCredential.Password password = new SshCredential.Password("not-reused".toCharArray());
        LinuxGateway gateway = (endpoint, credential, verifier) -> {
            connections.incrementAndGet();
            throw new AssertionError("unconfirmed preparation must not connect");
        };

        LocalizedOperationException failure = assertThrows(LocalizedOperationException.class, () ->
                new EnvironmentPreparationService().prepare(
                        new EnvironmentPreparationApproval("server-one", false, Instant.now()), gateway, endpoint(), password,
                        acceptingHostKey()
                )
        );

        assertEquals("environment.confirmationRequired", failure.userMessage().key());
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

        EnvironmentPreparationResult result = new EnvironmentPreparationService().prepare(
                new EnvironmentPreparationApproval("server-one", true, Instant.now()), gateway, endpoint(), password,
                acceptingHostKey()
        );

        assertEquals(1, connections.get());
        assertTrue(session.prepared);
        assertTrue(result.capabilities().supportsManagedDeployment(false, new HealthCheck.Tcp(8080, 1, 1)));
        assertCleared(password);
    }

    private static SshEndpoint endpoint() {
        return new SshEndpoint("server-one", "example.test", 22, "deployer");
    }

    private static HostKeyVerifier acceptingHostKey() {
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

        @Override
        public ServerCapabilities collectCapabilities() {
            return capabilities();
        }

        @Override
        public EnvironmentPreparationResult prepareEnvironment(EnvironmentPreparationApproval approval) {
            approval.requireAcceptedFor("server-one");
            prepared = true;
            return new EnvironmentPreparationResult(capabilities(), "fixed Ubuntu toolset installed");
        }

        @Override
        public UploadReceipt uploadSource(SourceArchiveDescriptor archive, RemoteWorkspace workspace) {
            throw unsupported();
        }

        @Override
        public RemoteBuildResult build(RemoteWorkspace workspace, BuildLimits limits) {
            throw unsupported();
        }

        @Override
        public ReleaseSnapshot snapshot(ManagedApplication application) {
            throw unsupported();
        }

        @Override
        public RemoteStepResult publish(ManagedApplication application, RemoteWorkspace workspace, RemoteBuildResult build,
                                        ReleaseSnapshot snapshot) {
            throw unsupported();
        }

        @Override
        public HealthCheckResult checkHealth(ManagedApplication application, HealthCheck healthCheck) {
            throw unsupported();
        }

        @Override
        public RemoteStepResult retainRecentSuccessfulReleases(ManagedApplication application) {
            throw unsupported();
        }

        @Override
        public RemoteStepResult rollback(ManagedApplication application, ReleaseSnapshot snapshot, RemoteBuildResult build) {
            throw unsupported();
        }

        @Override
        public LifecycleObservation observe(ManagedApplication application) {
            throw unsupported();
        }

        @Override
        public LifecycleObservation executeLifecycle(ManagedApplication application, LifecycleAction action,
                                                      HealthCheck healthCheck) {
            throw unsupported();
        }

        @Override
        public void close() {
        }

        private static UnsupportedOperationException unsupported() {
            return new UnsupportedOperationException("not part of environment preparation");
        }

        private static ServerCapabilities capabilities() {
            return new ServerCapabilities("Ubuntu 24.04.1 LTS", "x86_64", true, true, true, true, true, true, true, true,
                    10L * 1024 * 1024 * 1024, "capabilities freshly collected");
        }
    }
}
