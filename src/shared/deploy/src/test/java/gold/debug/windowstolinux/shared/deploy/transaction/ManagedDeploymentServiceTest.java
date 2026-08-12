package gold.debug.windowstolinux.shared.deploy.transaction;

import gold.debug.windowstolinux.shared.deploy.lifecycle.ManagedLifecycleService;
import gold.debug.windowstolinux.shared.deploy.plan.DeploymentApproval;
import gold.debug.windowstolinux.shared.deploy.plan.DeploymentRequest;
import gold.debug.windowstolinux.shared.deploy.result.DeploymentResult;
import gold.debug.windowstolinux.shared.deploy.result.LifecycleActionResult;

import gold.debug.windowstolinux.shared.model.deployment.BuildLimits;
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
import gold.debug.windowstolinux.shared.model.server.ServerCapabilities;
import gold.debug.windowstolinux.shared.linux.connection.SshCredential;
import gold.debug.windowstolinux.shared.linux.connection.SshEndpoint;
import gold.debug.windowstolinux.shared.linux.transfer.UploadReceipt;
import gold.debug.windowstolinux.shared.model.lifecycle.AutostartState;
import gold.debug.windowstolinux.shared.model.deployment.DeploymentStatus;
import gold.debug.windowstolinux.shared.model.health.HealthCheck;
import gold.debug.windowstolinux.shared.model.lifecycle.LifecycleAction;
import gold.debug.windowstolinux.shared.model.lifecycle.LifecycleObservation;
import gold.debug.windowstolinux.shared.model.managed.ManagedApplication;
import gold.debug.windowstolinux.shared.model.lifecycle.RuntimeState;
import gold.debug.windowstolinux.shared.model.server.ServerIdentity;
import gold.debug.windowstolinux.shared.model.archive.SourceArchiveDescriptor;
import gold.debug.windowstolinux.shared.model.project.SourceProjectFacts;
import gold.debug.windowstolinux.shared.model.message.LocalizedMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManagedDeploymentServiceTest {
    private static final String DIGEST = "a".repeat(64);

    @TempDir
    Path temporaryDirectory;

    @Test
    void reportsSuccessOnlyAfterRemoteHealthAndOwnershipVerification() {
        FakeSession session = new FakeSession(true, true);

        DeploymentResult result = new ManagedDeploymentService().deploy(
                request(), gateway(session), endpoint(), password(), acceptingHostKey()
        );

        assertEquals(DeploymentStatus.SUCCEEDED, result.status());
        assertTrue(result.finalObservation().orElseThrow().ownershipVerified());
        assertEquals(DIGEST, result.publishedArtifactSha256().orElseThrow());
        assertFalse(session.rollbackCalled);
        assertTrue(session.retentionCalled);
        assertTrue(session.cleanupCalled);
    }

    @Test
    void distinguishesCandidateHealthFailureFromSuccessfulRollback() {
        FakeSession session = new FakeSession(true, false);

        DeploymentResult result = new ManagedDeploymentService().deploy(
                request(), gateway(session), endpoint(), password(), acceptingHostKey()
        );

        assertEquals(DeploymentStatus.FAILED_ROLLED_BACK, result.status());
        assertTrue(session.rollbackCalled);
        assertEquals(2, session.healthChecks);
    }

    @Test
    void distinguishesFirstDeploymentHealthFailureFromSuccessfulCandidateCleanup() {
        FakeSession session = new FakeSession(false, false);

        DeploymentResult result = new ManagedDeploymentService().deploy(
                request(), gateway(session), endpoint(), password(), acceptingHostKey()
        );

        assertEquals(DeploymentStatus.FAILED_FIRST_DEPLOYMENT, result.status());
        assertTrue(session.rollbackCalled);
        assertEquals(1, session.healthChecks);
    }

    @Test
    void reconnectsAndRollsBackWhenTheDeploymentSessionDropsAfterTheSnapshot() {
        FakeSession session = new FakeSession(true, true);
        session.disconnectOnFirstCandidateHealth = true;
        FakeGateway gateway = gateway(session);

        DeploymentResult result = new ManagedDeploymentService().deploy(
                request(), gateway, endpoint(), password(), acceptingHostKey()
        );

        assertEquals(DeploymentStatus.FAILED_ROLLED_BACK, result.status());
        assertEquals(2, gateway.connections);
        assertTrue(session.rollbackCalled);
        assertTrue(result.events().stream().anyMatch(event -> event.step().equals("recovery-reconnect") && event.succeeded()));
    }

    @Test
    void reportsManualRecoveryWhenTheInterruptedDeploymentCannotReconnect() {
        FakeSession session = new FakeSession(true, true);
        session.disconnectOnFirstCandidateHealth = true;
        FakeGateway gateway = gateway(session);
        gateway.failReconnect = true;

        DeploymentResult result = new ManagedDeploymentService().deploy(
                request(), gateway, endpoint(), password(), acceptingHostKey()
        );

        assertEquals(DeploymentStatus.MANUAL_RECOVERY_REQUIRED, result.status());
        assertEquals(2, gateway.connections);
        assertFalse(session.rollbackCalled);
    }

    @Test
    void rollbackRestoresAnAlreadyStoppedPreviousServiceWithoutStartingIt() {
        FakeSession session = new FakeSession(true, false);
        session.previousWasRunning = false;

        DeploymentResult result = new ManagedDeploymentService().deploy(
                request(), gateway(session), endpoint(), password(), acceptingHostKey()
        );

        assertEquals(DeploymentStatus.FAILED_ROLLED_BACK, result.status());
        assertEquals(1, session.healthChecks);
        assertEquals(RuntimeState.STOPPED, result.finalObservation().orElseThrow().runtimeState());
    }

    @Test
    void lifecycleDoesNotExecuteAgainstAnUnverifiedResource() {
        FakeSession session = new FakeSession(true, true);
        session.ownershipVerified = false;

        LifecycleActionResult result = new ManagedLifecycleService().execute(
                application(), LifecycleAction.STOP, new HealthCheck.Tcp(8080, 5, 1),
                gateway(session), endpoint(), password(), acceptingHostKey()
        );

        assertFalse(result.accepted());
        assertFalse(session.lifecycleCalled);
        assertEquals(RuntimeState.UNKNOWN, result.observation().orElseThrow().runtimeState());
        assertEquals(AutostartState.UNKNOWN, result.observation().orElseThrow().autostartState());
    }

    @Test
    void queryFailureReturnsNoObservationInsteadOfPretendingTheCachedStateIsLive() {
        FakeSession session = new FakeSession(true, true);
        FakeGateway gateway = gateway(session);
        gateway.failConnection = true;

        LifecycleActionResult result = new ManagedLifecycleService().execute(
                application(), LifecycleAction.REFRESH_STATUS, new HealthCheck.Tcp(8080, 5, 1),
                gateway, endpoint(), password(), acceptingHostKey()
        );

        assertFalse(result.accepted());
        assertTrue(result.observation().isEmpty());
        assertFalse(session.lifecycleCalled);
    }

    @Test
    void startRejectsAnAlreadyRunningManagedApplication() {
        FakeSession session = new FakeSession(true, true);

        LifecycleActionResult result = new ManagedLifecycleService().execute(
                application(), LifecycleAction.START, new HealthCheck.Tcp(8080, 5, 1),
                gateway(session), endpoint(), password(), acceptingHostKey()
        );

        assertFalse(result.accepted());
        assertFalse(session.lifecycleCalled);
    }

    @Test
    void rejectsTargetThatCannotAccommodateTheReviewedWorkspaceLimit() {
        FakeSession session = new FakeSession(true, true);
        session.availableBytes = 1024;

        DeploymentResult result = new ManagedDeploymentService().deploy(
                request(), gateway(session), endpoint(), password(), acceptingHostKey()
        );

        assertEquals(DeploymentStatus.PRECONDITION_REJECTED, result.status());
        assertFalse(session.uploadCalled);
    }

    @Test
    void rejectsArchiveWhoseTarGzipAndExtractedSourceExceedTheReviewedWorkspaceLimit() {
        FakeSession session = new FakeSession(true, true);
        SourceArchiveDescriptor archive = new SourceArchiveDescriptor(
                temporaryDirectory.resolve("source.tar.gz"), DIGEST, 40L * 1024 * 1024, 40L * 1024 * 1024
        );
        DeploymentApproval approval = new DeploymentApproval("demo", DIGEST, "server-one", false, Instant.now());
        DeploymentRequest request = new DeploymentRequest(application(), new SourceProjectFacts(
                temporaryDirectory, "demo", true, true, List.of(LocalizedMessage.of("test.staticOnly"))
        ), archive, new HealthCheck.Tcp(8080, 5, 1),
                new BuildLimits(60, 8, 256, 4096, 64L * 1024 * 1024, false), approval);

        DeploymentResult result = new ManagedDeploymentService().deploy(
                request, gateway(session), endpoint(), password(), acceptingHostKey()
        );

        assertEquals(DeploymentStatus.PRECONDITION_REJECTED, result.status());
        assertFalse(session.uploadCalled);
        assertTrue(result.events().stream().anyMatch(event -> event.step().equals("source-workspace")));
    }

    @Test
    void rejectsConfirmedRootBuildBeforeOpeningANonRootSshSession() {
        FakeSession session = new FakeSession(true, true);
        FakeGateway gateway = gateway(session);

        DeploymentResult result = new ManagedDeploymentService().deploy(
                rootBuildRequest(), gateway, endpoint(), password(), acceptingHostKey()
        );

        assertEquals(DeploymentStatus.PRECONDITION_REJECTED, result.status());
        assertEquals(0, gateway.connections);
        assertFalse(session.uploadCalled);
        assertTrue(result.events().stream().anyMatch(event -> event.step().equals("root-build-session")
                && !event.succeeded()
                && event.evidence().contains("managed service root builds require a root SSH session")));
    }

    @Test
    void rejectsAnUnconfirmedRootSshBuildBeforeOpeningTheSession() {
        FakeSession session = new FakeSession(true, true);
        FakeGateway gateway = gateway(session);

        DeploymentResult result = new ManagedDeploymentService().deploy(
                request(), gateway, rootEndpoint(), password(), acceptingHostKey()
        );

        assertEquals(DeploymentStatus.PRECONDITION_REJECTED, result.status());
        assertEquals(0, gateway.connections);
        assertFalse(session.uploadCalled);
        assertTrue(result.events().stream().anyMatch(event -> event.step().equals("root-build-session")
                && !event.succeeded()
                && event.evidence().contains("A root SSH session may be used only for a separately confirmed")));
    }

    @Test
    void acceptsAnExplicitlyConfirmedRootBuildOnARootSshSession() {
        FakeSession session = new FakeSession(true, true);
        FakeGateway gateway = gateway(session);

        DeploymentResult result = new ManagedDeploymentService().deploy(
                rootBuildRequest(), gateway, rootEndpoint(), password(), acceptingHostKey()
        );

        assertEquals(DeploymentStatus.SUCCEEDED, result.status());
        assertEquals(1, gateway.connections);
        assertTrue(session.uploadCalled);
    }

    @Test
    void cleansTheHelperCreatedCandidateAfterABuildFailureWithoutHidingTheBuildDiagnostic() {
        FakeSession session = new FakeSession(true, true);
        session.buildSucceeds = false;

        DeploymentResult result = new ManagedDeploymentService().deploy(
                request(), gateway(session), endpoint(), password(), acceptingHostKey()
        );

        assertEquals(DeploymentStatus.FAILED_BUILD, result.status());
        assertTrue(session.cleanupCalled);
        assertTrue(result.events().stream().anyMatch(event -> event.step().equals("remote-build") && !event.succeeded()
                && event.evidence().contains("controlled build failure")));
        assertTrue(result.events().stream().anyMatch(event -> event.step().equals("candidate-cleanup") && event.succeeded()));
    }

    @Test
    void cleansTheCandidateInTheCurrentSessionWhenSnapshotOwnershipVerificationFails() {
        FakeSession session = new FakeSession(true, true);
        session.snapshotFails = true;
        FakeGateway gateway = gateway(session);

        DeploymentResult result = new ManagedDeploymentService().deploy(
                request(), gateway, endpoint(), password(), acceptingHostKey()
        );

        assertEquals(DeploymentStatus.PRECONDITION_REJECTED, result.status());
        assertEquals(1, gateway.connections);
        assertTrue(session.cleanupCalled);
        assertFalse(session.rollbackCalled);
        assertTrue(result.events().stream().anyMatch(event -> event.step().equals("snapshot") && !event.succeeded()
                && event.evidence().contains("controlled snapshot failure")));
        assertTrue(result.events().stream().anyMatch(event -> event.step().equals("candidate-cleanup") && event.succeeded()));
    }

    @Test
    void reconnectsToCleanTheCandidateWhenCurrentSessionCleanupFailsAfterSnapshotFailure() {
        FakeSession session = new FakeSession(true, true);
        session.snapshotFails = true;
        session.cleanupExceptionsRemaining = 1;
        FakeGateway gateway = gateway(session);

        DeploymentResult result = new ManagedDeploymentService().deploy(
                request(), gateway, endpoint(), password(), acceptingHostKey()
        );

        assertEquals(DeploymentStatus.PRECONDITION_REJECTED, result.status());
        assertEquals(2, gateway.connections);
        assertEquals(2, session.cleanupCalls);
        assertTrue(result.events().stream().anyMatch(event -> event.step().equals("candidate-cleanup-reconnect")
                && event.succeeded()));
        assertTrue(result.events().stream().anyMatch(event -> event.step().equals("candidate-cleanup") && event.succeeded()));
    }

    @Test
    void requiresManualRecoveryWhenSnapshotFailureCandidateCleanupCannotReconnect() {
        FakeSession session = new FakeSession(true, true);
        session.snapshotFails = true;
        session.cleanupFailuresRemaining = 1;
        FakeGateway gateway = gateway(session);
        gateway.failReconnect = true;

        DeploymentResult result = new ManagedDeploymentService().deploy(
                request(), gateway, endpoint(), password(), acceptingHostKey()
        );

        assertEquals(DeploymentStatus.MANUAL_RECOVERY_REQUIRED, result.status());
        assertEquals(2, gateway.connections);
        assertEquals(1, session.cleanupCalls);
        assertTrue(result.events().stream().anyMatch(event -> event.step().equals("candidate-cleanup-reconnect")
                && !event.succeeded()));
    }

    private DeploymentRequest request() {
        SourceProjectFacts facts = new SourceProjectFacts(
                temporaryDirectory, "demo", true, true, List.of(LocalizedMessage.of("test.staticOnly"))
        );
        SourceArchiveDescriptor archive = new SourceArchiveDescriptor(
                temporaryDirectory.resolve("source.tar.gz"), DIGEST, 100, 100
        );
        DeploymentApproval approval = new DeploymentApproval("demo", DIGEST, "server-one", false, Instant.now());
        return new DeploymentRequest(
                application(), facts, archive, new HealthCheck.Tcp(8080, 5, 1), BuildLimits.defaultNonRoot(), approval
        );
    }

    private DeploymentRequest rootBuildRequest() {
        SourceProjectFacts facts = new SourceProjectFacts(
                temporaryDirectory, "demo", true, true, List.of(LocalizedMessage.of("test.staticOnly"))
        );
        SourceArchiveDescriptor archive = new SourceArchiveDescriptor(
                temporaryDirectory.resolve("source.tar.gz"), DIGEST, 100, 100
        );
        DeploymentApproval approval = new DeploymentApproval("demo", DIGEST, "server-one", true, Instant.now());
        BuildLimits limits = new BuildLimits(1800, 1024, 4096, 4L * 1024 * 1024,
                4L * 1024 * 1024 * 1024, true);
        return new DeploymentRequest(application(), facts, archive, new HealthCheck.Tcp(8080, 5, 1), limits, approval);
    }

    private static ManagedApplication application() {
        ServerIdentity server = new ServerIdentity("server-one", "example.test", 22, "SHA256:exampleFingerprint");
        return ManagedApplication.forManaged("demo", server, DIGEST);
    }

    private static SshEndpoint endpoint() {
        return new SshEndpoint("server-one", "example.test", 22, "deployer");
    }

    private static SshEndpoint rootEndpoint() {
        return new SshEndpoint("server-one", "example.test", 22, "root");
    }

    private static SshCredential password() {
        return new SshCredential.Password("test-password".toCharArray());
    }

    private static HostKeyVerifier acceptingHostKey() {
        return (endpoint, fingerprint) -> HostKeyDecision.ACCEPT_EXISTING;
    }

    private static FakeGateway gateway(FakeSession session) {
        return new FakeGateway(session);
    }

    private static final class FakeGateway implements LinuxGateway {
        private final FakeSession session;
        private int connections;
        private boolean failReconnect;
        private boolean failConnection;

        private FakeGateway(FakeSession session) {
            this.session = session;
        }

        @Override
        public LinuxRemoteSession connect(SshEndpoint endpoint, SshCredential credential, HostKeyVerifier verifier)
                throws LinuxOperationException {
            connections++;
            if (failConnection) {
                throw LinuxOperationException.localized("diagnostic.unexpected", "simulated query failure");
            }
            if (failReconnect && connections > 1) {
                throw LinuxOperationException.localized("diagnostic.unexpected", "simulated reconnect failure");
            }
            return session;
        }
    }

    private static final class FakeSession implements LinuxRemoteSession {
        private final boolean existingRelease;
        private final boolean candidateHealthy;
        private int healthChecks;
        private boolean rollbackCalled;
        private boolean retentionCalled;
        private boolean lifecycleCalled;
        private boolean uploadCalled;
        private boolean cleanupCalled;
        private int cleanupCalls;
        private int cleanupFailuresRemaining;
        private int cleanupExceptionsRemaining;
        private boolean buildSucceeds = true;
        private boolean ownershipVerified = true;
        private boolean previousWasRunning = true;
        private boolean disconnectOnFirstCandidateHealth;
        private boolean snapshotFails;
        private long availableBytes = 10L * 1024 * 1024 * 1024;

        private FakeSession(boolean existingRelease, boolean candidateHealthy) {
            this.existingRelease = existingRelease;
            this.candidateHealthy = candidateHealthy;
        }

        @Override
        public ServerCapabilities collectCapabilities() {
            return new ServerCapabilities("Ubuntu 24.04.1 LTS", "x86_64", true, true, true, true, true, true, true, true,
                    availableBytes, "capabilities collected");
        }

        @Override
        public gold.debug.windowstolinux.shared.model.deployment.EnvironmentPreparationResult prepareEnvironment(
                gold.debug.windowstolinux.shared.model.deployment.EnvironmentPreparationApproval approval
        ) {
            approval.requireAcceptedFor("server-one");
            return new gold.debug.windowstolinux.shared.model.deployment.EnvironmentPreparationResult(
                    collectCapabilities(), "fixed toolset prepared"
            );
        }

        @Override
        public UploadReceipt uploadSource(SourceArchiveDescriptor archive, RemoteWorkspace workspace) {
            uploadCalled = true;
            return new UploadReceipt(workspace.candidateRoot() + "/mutable/source.tar.gz", archive.byteCount(), archive.contentSha256(), "upload verified");
        }

        @Override
        public RemoteBuildResult build(RemoteWorkspace workspace, BuildLimits limits) {
            return buildSucceeds
                    ? RemoteBuildResult.succeeded(workspace.candidateRoot() + "/mutable/source/target/app.jar", DIGEST, "one JAR verified")
                    : RemoteBuildResult.failed("controlled build failure");
        }

        @Override
        public RemoteStepResult cleanupCandidate(RemoteWorkspace workspace) throws LinuxOperationException {
            cleanupCalled = true;
            cleanupCalls++;
            if (cleanupExceptionsRemaining > 0) {
                cleanupExceptionsRemaining--;
                throw LinuxOperationException.localized("diagnostic.unexpected",
                        "controlled candidate cleanup transport failure");
            }
            if (cleanupFailuresRemaining > 0) {
                cleanupFailuresRemaining--;
                return new RemoteStepResult(false, false, "controlled candidate cleanup failure");
            }
            return new RemoteStepResult(true, false, "candidate cleaned");
        }

        @Override
        public ReleaseSnapshot snapshot(ManagedApplication application) throws LinuxOperationException {
            if (snapshotFails) {
                throw LinuxOperationException.localized("diagnostic.unexpected", "controlled snapshot failure");
            }
            return existingRelease ? ReleaseSnapshot.withPreviousRelease("rollback-token", previousWasRunning, "snapshot saved")
                    : ReleaseSnapshot.firstDeployment("first deployment");
        }

        @Override
        public RemoteStepResult publish(
                ManagedApplication application,
                RemoteWorkspace workspace,
                RemoteBuildResult build,
                ReleaseSnapshot snapshot
        ) {
            return new RemoteStepResult(true, false, "published");
        }

        @Override
        public HealthCheckResult checkHealth(ManagedApplication application, HealthCheck healthCheck) throws LinuxOperationException {
            healthChecks++;
            if (disconnectOnFirstCandidateHealth && healthChecks == 1) {
                throw LinuxOperationException.localized("diagnostic.unexpected",
                        "simulated post-publish connection loss");
            }
            return new HealthCheckResult(healthChecks > 1 || candidateHealthy, "health checked");
        }

        @Override
        public RemoteStepResult retainRecentSuccessfulReleases(ManagedApplication application) {
            retentionCalled = true;
            return new RemoteStepResult(true, false, "retention applied");
        }

        @Override
        public RemoteStepResult rollback(ManagedApplication application, ReleaseSnapshot snapshot, RemoteBuildResult build) {
            rollbackCalled = true;
            return new RemoteStepResult(true, false, "rollback applied");
        }

        @Override
        public LifecycleObservation observe(ManagedApplication application) {
            return new LifecycleObservation(
                    application,
                    ownershipVerified ? (rollbackCalled && !previousWasRunning ? RuntimeState.STOPPED : RuntimeState.RUNNING)
                            : RuntimeState.UNKNOWN,
                    ownershipVerified ? AutostartState.ENABLED : AutostartState.UNKNOWN,
                    ownershipVerified,
                    Instant.now(),
                    "observed"
            );
        }

        @Override
        public LifecycleObservation executeLifecycle(ManagedApplication application, LifecycleAction action, HealthCheck healthCheck) {
            lifecycleCalled = true;
            return observe(application);
        }

        @Override
        public void close() {
        }
    }
}
