package gold.debug.windowstolinux.shared.deploy.transaction;

import gold.debug.windowstolinux.shared.deploy.plan.DeploymentRequest;
import gold.debug.windowstolinux.shared.deploy.result.DeploymentEvent;
import gold.debug.windowstolinux.shared.deploy.result.DeploymentResult;

import gold.debug.windowstolinux.shared.linux.runtime.HealthCheckResult;
import gold.debug.windowstolinux.shared.linux.connection.LinuxOperationException;
import gold.debug.windowstolinux.shared.linux.connection.PhaseOneLinuxGateway;
import gold.debug.windowstolinux.shared.linux.connection.PhaseOneRemoteSession;
import gold.debug.windowstolinux.shared.linux.protocol.ReleaseSnapshot;
import gold.debug.windowstolinux.shared.linux.build.RemoteBuildResult;
import gold.debug.windowstolinux.shared.linux.protocol.RemoteStepResult;
import gold.debug.windowstolinux.shared.linux.transfer.RemoteWorkspace;
import gold.debug.windowstolinux.shared.model.server.ServerCapabilities;
import gold.debug.windowstolinux.shared.linux.connection.SshCredential;
import gold.debug.windowstolinux.shared.linux.connection.SshEndpoint;
import gold.debug.windowstolinux.shared.linux.connection.HostKeyVerifier;
import gold.debug.windowstolinux.shared.model.deployment.DeploymentStatus;
import gold.debug.windowstolinux.shared.model.lifecycle.LifecycleObservation;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Orchestrates the fixed phase-one short-downtime publish transaction.
 *
 * <p>编排固定的一期短停机发布事务。
 */
public final class PhaseOneDeploymentService {
    private static final long MINIMUM_FREE_SPACE_MULTIPLIER = 3;

    /**
     * Performs the {@code deploy} operation.
     *
     * <p>执行 {@code deploy} 操作。
     *
     * @param request the {@code request} value / {@code request} 值
     * @param gateway the {@code gateway} value / {@code gateway} 值
     * @param endpoint the {@code endpoint} value / {@code endpoint} 值
     * @param credential the {@code credential} value / {@code credential} 值
     * @param hostKeyVerifier the {@code hostKeyVerifier} value / {@code hostKeyVerifier} 值
     * @return the operation result / 操作结果
     */
    public DeploymentResult deploy(
            DeploymentRequest request,
            PhaseOneLinuxGateway gateway,
            SshEndpoint endpoint,
            SshCredential credential,
            HostKeyVerifier hostKeyVerifier
    ) {
        List<DeploymentEvent> events = new ArrayList<>();
        if (request.approval().rootBuildAccepted() && !"root".equals(endpoint.username())) {
            try {
                return rejected(events, "root-build-session",
                        "Phase-one root builds require a root SSH session; the current SSH user is not root, so no "
                                + "connection, candidate directory, or source upload was attempted");
            } finally {
                clearCredential(credential);
            }
        }
        if ("root".equals(endpoint.username()) && !request.approval().rootBuildAccepted()) {
            try {
                return rejected(events, "root-build-session",
                        "A root SSH session may be used only for a separately confirmed phase-one root build; the "
                                + "current request was not confirmed, so no connection, candidate directory, or source "
                                + "upload was attempted");
            } finally {
                clearCredential(credential);
            }
        }
        RemoteWorkspace workspace = new RemoteWorkspace(request.application().id(), request.archive().contentSha256());
        ReleaseSnapshot snapshot = null;
        RemoteBuildResult build = null;
        try (PhaseOneRemoteSession session = gateway.connect(endpoint, connectionCredential(credential), hostKeyVerifier)) {
            ServerCapabilities capabilities = session.collectCapabilities();
            if (!capabilities.supportsPhaseOne(request.source().usesMavenWrapper(), request.healthCheck())) {
                return rejected(events, "target-capabilities", capabilities.evidence());
            }
            long sourceFootprint = Math.addExact(request.archive().byteCount(), request.archive().uncompressedByteCount());
            if (sourceFootprint > request.buildLimits().maxWorkspaceBytes()) {
                return rejected(events, "source-workspace",
                        "The archive and extracted source would exceed the confirmed target workspace limit; no "
                                + "candidate directory was created");
            }
            long requiredBytes = Math.max(Math.multiplyExact(request.archive().byteCount(), MINIMUM_FREE_SPACE_MULTIPLIER),
                    request.buildLimits().maxWorkspaceBytes());
            if (capabilities.availableBytes() < requiredBytes) {
                return rejected(events, "target-space",
                        "Target free space is insufficient; no candidate directory was created");
            }
            events.add(new DeploymentEvent("target-capabilities", true, capabilities.evidence()));

            var receipt = session.uploadSource(request.archive(), workspace);
            if (!receipt.contentSha256().equals(request.archive().contentSha256())
                    || receipt.byteCount() != request.archive().byteCount()) {
                return rejected(events, "source-upload",
                        "Target archive digest or size does not match the locally verified result");
            }
            events.add(new DeploymentEvent("source-upload", true, receipt.evidence()));

            build = session.build(workspace, request.buildLimits());
            events.add(new DeploymentEvent("remote-build", build.succeeded(), build.evidence()));
            if (!build.succeeded()) {
                cleanupCandidate(session, workspace, events);
                return new DeploymentResult(DeploymentStatus.FAILED_BUILD, events, Optional.empty(), Optional.empty());
            }

            try {
                snapshot = session.snapshot(request.application());
            } catch (LinuxOperationException snapshotFailure) {
                events.add(new DeploymentEvent("snapshot", false, safeMessage(snapshotFailure)));
                if (cleanupCandidate(session, workspace, events)) {
                    return new DeploymentResult(DeploymentStatus.PRECONDITION_REJECTED, events, Optional.empty(), Optional.empty());
                }
                session.close();
                return cleanupCandidateAfterSnapshotFailure(gateway, endpoint, credential, hostKeyVerifier, workspace, events);
            }
            events.add(new DeploymentEvent("snapshot", true, snapshot.evidence()));
            RemoteStepResult publish = session.publish(request.application(), workspace, build, snapshot);
            events.add(new DeploymentEvent("publish", publish.succeeded(), publish.evidence()));
            if (!publish.succeeded()) {
                return recover(session, request, workspace, snapshot, build, events, "Publish step failed");
            }

            HealthCheckResult health = session.checkHealth(request.application(), request.healthCheck());
            events.add(new DeploymentEvent("candidate-health", health.healthy(), health.evidence()));
            if (!health.healthy()) {
                return recover(session, request, workspace, snapshot, build, events,
                        "Candidate release health check failed");
            }

            LifecycleObservation observation = session.observe(request.application());
            events.add(new DeploymentEvent("final-observation", observation.ownershipVerified(), observation.evidence()));
            if (!observation.ownershipVerified()) {
                return recover(session, request, workspace, snapshot, build, events,
                        "Candidate resource ownership could not be verified");
            }
            RemoteStepResult retention = session.retainRecentSuccessfulReleases(request.application());
            events.add(new DeploymentEvent("release-retention", retention.succeeded(), retention.evidence()));
            cleanupCandidate(session, workspace, events);
            return new DeploymentResult(DeploymentStatus.SUCCEEDED, events, Optional.of(observation),
                    Optional.of(build.executableJarSha256().orElseThrow()));
        } catch (LinuxOperationException | ArithmeticException exception) {
            events.add(new DeploymentEvent("linux-operation", false, safeMessage(exception)));
            if (snapshot != null && build != null && build.succeeded()) {
                return recoverAfterInterruptedSession(request, gateway, endpoint, credential, hostKeyVerifier, workspace,
                        snapshot, build, events);
            }
            return new DeploymentResult(DeploymentStatus.PRECONDITION_REJECTED, events, Optional.empty(), Optional.empty());
        } finally {
            clearCredential(credential);
        }
    }

    private static DeploymentResult rejected(List<DeploymentEvent> events, String step, String evidence) {
        events.add(new DeploymentEvent(step, false, evidence));
        return new DeploymentResult(DeploymentStatus.PRECONDITION_REJECTED, events, Optional.empty(), Optional.empty());
    }

    private static DeploymentResult recover(
            PhaseOneRemoteSession session,
            DeploymentRequest request,
            RemoteWorkspace workspace,
            ReleaseSnapshot snapshot,
            RemoteBuildResult build,
            List<DeploymentEvent> events,
            String cause
    ) throws LinuxOperationException {
        RemoteStepResult rollback = session.rollback(request.application(), snapshot, build);
        events.add(new DeploymentEvent("rollback", rollback.succeeded(), rollback.evidence()));
        cleanupCandidate(session, workspace, events);
        if (!snapshot.hasPreviousRelease()) {
            return new DeploymentResult(rollback.succeeded()
                    ? DeploymentStatus.FAILED_FIRST_DEPLOYMENT
                    : DeploymentStatus.MANUAL_RECOVERY_REQUIRED, events, Optional.empty(), Optional.empty());
        }
        if (!rollback.succeeded()) {
            return new DeploymentResult(DeploymentStatus.MANUAL_RECOVERY_REQUIRED, events, Optional.empty(), Optional.empty());
        }
        if (snapshot.previousWasRunning()) {
            HealthCheckResult restoredHealth = session.checkHealth(request.application(), request.healthCheck());
            events.add(new DeploymentEvent("rollback-health", restoredHealth.healthy(), restoredHealth.evidence()));
            if (!restoredHealth.healthy()) {
                return new DeploymentResult(DeploymentStatus.MANUAL_RECOVERY_REQUIRED, events, Optional.empty(), Optional.empty());
            }
        } else {
            events.add(new DeploymentEvent("rollback-health", true,
                    "Previous release was stopped before publish; rollback did not start it unexpectedly"));
        }
        LifecycleObservation restored = session.observe(request.application());
        events.add(new DeploymentEvent("rollback-observation", restored.ownershipVerified(), restored.evidence()));
        boolean restoredRuntime = snapshot.previousWasRunning()
                ? restored.runtimeState() == gold.debug.windowstolinux.shared.model.lifecycle.RuntimeState.RUNNING
                : restored.runtimeState() == gold.debug.windowstolinux.shared.model.lifecycle.RuntimeState.STOPPED;
        if (!restored.ownershipVerified() || !restoredRuntime) {
            return new DeploymentResult(DeploymentStatus.MANUAL_RECOVERY_REQUIRED, events, Optional.of(restored), Optional.empty());
        }
        return new DeploymentResult(DeploymentStatus.FAILED_ROLLED_BACK, events, Optional.of(restored), Optional.empty());
    }

    private static DeploymentResult recoverAfterInterruptedSession(
            DeploymentRequest request,
            PhaseOneLinuxGateway gateway,
            SshEndpoint endpoint,
            SshCredential credential,
            HostKeyVerifier hostKeyVerifier,
            RemoteWorkspace workspace,
            ReleaseSnapshot snapshot,
            RemoteBuildResult build,
            List<DeploymentEvent> events
    ) {
        try (PhaseOneRemoteSession recoverySession = gateway.connect(endpoint, connectionCredential(credential), hostKeyVerifier)) {
            events.add(new DeploymentEvent("recovery-reconnect", true,
                    "SSH host was reverified after the publish session disconnected and rollback began"));
            return recover(recoverySession, request, workspace, snapshot, build, events,
                    "Publish session disconnected");
        } catch (LinuxOperationException exception) {
            events.add(new DeploymentEvent("rollback", false,
                    "Could not reconnect and complete rollback after the publish session disconnected: "
                            + safeMessage(exception)));
            return new DeploymentResult(DeploymentStatus.MANUAL_RECOVERY_REQUIRED, events, Optional.empty(), Optional.empty());
        }
    }

    private static DeploymentResult cleanupCandidateAfterSnapshotFailure(
            PhaseOneLinuxGateway gateway,
            SshEndpoint endpoint,
            SshCredential credential,
            HostKeyVerifier hostKeyVerifier,
            RemoteWorkspace workspace,
            List<DeploymentEvent> events
    ) {
        try (PhaseOneRemoteSession cleanupSession = gateway.connect(
                endpoint, connectionCredential(credential), hostKeyVerifier
        )) {
            events.add(new DeploymentEvent("candidate-cleanup-reconnect", true,
                    "SSH host was reverified after snapshot verification failed, and candidate cleanup was attempted"));
            if (cleanupCandidate(cleanupSession, workspace, events)) {
                return new DeploymentResult(DeploymentStatus.PRECONDITION_REJECTED, events, Optional.empty(), Optional.empty());
            }
        } catch (LinuxOperationException exception) {
            events.add(new DeploymentEvent("candidate-cleanup-reconnect", false,
                    "Could not reconnect and verify candidate cleanup after snapshot verification failed: "
                            + safeMessage(exception)));
        }
        return new DeploymentResult(DeploymentStatus.MANUAL_RECOVERY_REQUIRED, events, Optional.empty(), Optional.empty());
    }

    private static boolean cleanupCandidate(
            PhaseOneRemoteSession session,
            RemoteWorkspace workspace,
            List<DeploymentEvent> events
    ) {
        try {
            RemoteStepResult cleanup = session.cleanupCandidate(workspace);
            events.add(new DeploymentEvent("candidate-cleanup", cleanup.succeeded(), cleanup.evidence()));
            return cleanup.succeeded();
        } catch (LinuxOperationException exception) {
            events.add(new DeploymentEvent("candidate-cleanup", false,
                    "Candidate directory cleanup failed; original deployment diagnostic was preserved: "
                            + safeMessage(exception)));
            return false;
        }
    }

    private static void clearCredential(SshCredential credential) {
        if (credential instanceof SshCredential.Password password) {
            password.clear();
        }
    }

    private static SshCredential connectionCredential(SshCredential credential) {
        if (credential instanceof SshCredential.Password password) {
            char[] value = password.copy();
            try {
                return new SshCredential.Password(value);
            } finally {
                java.util.Arrays.fill(value, '\0');
            }
        }
        return credential;
    }

    private static String safeMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? "Controlled Linux operation failed" : message;
    }
}
