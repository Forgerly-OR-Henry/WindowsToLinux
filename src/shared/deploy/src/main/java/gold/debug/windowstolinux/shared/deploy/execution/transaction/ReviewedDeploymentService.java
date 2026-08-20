package gold.debug.windowstolinux.shared.deploy.execution.transaction;

import gold.debug.windowstolinux.shared.deploy.contract.ReviewedDeploymentRequest;
import gold.debug.windowstolinux.shared.deploy.plan.ReviewedReleaseIdentityResolver;
import gold.debug.windowstolinux.shared.config.secretref.ResolvedSecretRevision;
import gold.debug.windowstolinux.shared.config.revision.DeploymentInputManifest;
import gold.debug.windowstolinux.shared.deploy.contract.result.deployment.DeploymentEvent;
import gold.debug.windowstolinux.shared.deploy.contract.result.deployment.DeploymentResult;
import gold.debug.windowstolinux.shared.deploy.contract.result.compatibility.HostSupportStatus;
import gold.debug.windowstolinux.shared.deploy.contract.result.compatibility.HostSupportDecision;
import gold.debug.windowstolinux.shared.deploy.support.HostSupportEvaluator;
import gold.debug.windowstolinux.shared.linux.build.DeploymentBuildResult;
import gold.debug.windowstolinux.shared.linux.connection.DeploymentLinuxGateway;
import gold.debug.windowstolinux.shared.linux.session.DeploymentRemoteSession;
import gold.debug.windowstolinux.shared.linux.connection.HostKeyEvaluator;
import gold.debug.windowstolinux.shared.linux.error.LinuxOperationException;
import gold.debug.windowstolinux.shared.linux.connection.SshCredential;
import gold.debug.windowstolinux.shared.linux.connection.SshEndpoint;
import gold.debug.windowstolinux.shared.linux.protocol.ReleaseSnapshot;
import gold.debug.windowstolinux.shared.linux.protocol.RemoteStepResult;
import gold.debug.windowstolinux.shared.linux.protocol.ManagedHelperProtocol;
import gold.debug.windowstolinux.shared.linux.runtime.HealthCheckResult;
import gold.debug.windowstolinux.shared.linux.transfer.RemoteWorkspace;
import gold.debug.windowstolinux.shared.model.deployment.DeploymentStatus;
import gold.debug.windowstolinux.shared.model.lifecycle.LifecycleObservation;
import gold.debug.windowstolinux.shared.model.lifecycle.RuntimeState;
import gold.debug.windowstolinux.shared.model.managed.ManagedApplication;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Executes one reviewed, type-specific deployment through the same bounded transaction semantics as every managed application.
 *
 * <p>通过与所有受管应用相同的有界事务语义执行一个经审阅、类型专属的部署。
 */
public final class ReviewedDeploymentService {
    private static final long MINIMUM_FREE_SPACE_MULTIPLIER = 3;

    /**
     * Deploys one fully reviewed request without accepting a raw command or an inferred runtime.
     *
     * <p>部署一个完整审阅的请求，不接受原始命令或推断的运行时。
     *
     * @param request the reviewed deployment input / 经审阅的部署输入
     * @param application the stable managed identity / 稳定的受管身份
     * @param gateway the typed SSH gateway / 类型化 SSH 网关
     * @param endpoint the verified endpoint / 已验证的端点
     * @param credential the selected credential / 选定的凭据
     * @param hostKeyVerifier the host-key verifier / 主机密钥验证器
     * @return the terminal deployment result / 部署终态结果
     */
    public DeploymentResult deploy(ReviewedDeploymentRequest request, ManagedApplication application,
                                   DeploymentLinuxGateway gateway, SshEndpoint endpoint, SshCredential credential,
                                   HostKeyEvaluator hostKeyVerifier) {
        if (!request.secretReferences().isEmpty()) {
            throw new IllegalArgumentException("resolved secret revisions are required for this reviewed request");
        }
        return deploy(request, application, gateway, endpoint, credential, hostKeyVerifier, List.of());
    }

    /** Executes a reviewed transaction with short-lived exact secret revisions. / 使用短生命周期精确秘密修订执行经审阅事务。 */
    public DeploymentResult deploy(ReviewedDeploymentRequest request, ManagedApplication application,
                                   DeploymentLinuxGateway gateway, SshEndpoint endpoint, SshCredential credential,
                                   HostKeyEvaluator hostKeyVerifier, List<ResolvedSecretRevision> resolvedSecrets) {
        request = java.util.Objects.requireNonNull(request, "request");
        application = java.util.Objects.requireNonNull(application, "application");
        gateway = java.util.Objects.requireNonNull(gateway, "gateway");
        endpoint = java.util.Objects.requireNonNull(endpoint, "endpoint");
        credential = java.util.Objects.requireNonNull(credential, "credential");
        hostKeyVerifier = java.util.Objects.requireNonNull(hostKeyVerifier, "hostKeyVerifier");
        resolvedSecrets = List.copyOf(java.util.Objects.requireNonNull(resolvedSecrets, "resolvedSecrets"));
        if (!resolvedSecrets.stream().map(ResolvedSecretRevision::reference).toList().equals(request.secretReferences())) {
            throw new IllegalArgumentException("resolved secret revisions must exactly match the reviewed references");
        }
        List<DeploymentEvent> events = new ArrayList<>();
        if (!application.server().equals(request.server()) || !application.id().equals(request.facts().applicationId())) {
            return rejected(events, "managed-identity", "Reviewed request and managed application identity do not match");
        }
        if (request.limits().runAsRoot() != "root".equals(endpoint.username())) {
            return rejected(events, "root-build-session",
                    "Root build approval and the SSH account must agree before a candidate is created");
        }
        RemoteWorkspace workspace = new RemoteWorkspace(application.id(), request.archive().contentSha256());
        String releaseIdentity = ReviewedReleaseIdentityResolver.from(request);
        ReleaseSnapshot snapshot = null;
        DeploymentBuildResult build = null;
        DeploymentInputManifest inputs = null;
        boolean candidateMayExist = false;
        try (DeploymentRemoteSession session = gateway.connect(endpoint, credential.duplicate(), hostKeyVerifier)) {
            long sourceFootprint = Math.addExact(request.archive().byteCount(), request.archive().uncompressedByteCount());
            if (sourceFootprint > request.limits().maxWorkspaceBytes()) {
                return rejected(events, "source-workspace", "The reviewed archive exceeds the confirmed workspace limit");
            }
            long requiredBytes = Math.max(Math.multiplyExact(request.archive().byteCount(), MINIMUM_FREE_SPACE_MULTIPLIER),
                    request.limits().maxWorkspaceBytes());
            var serverCapabilities = session.collectCapabilities();
            if (serverCapabilities.managedHelperProtocolVersion() != ManagedHelperProtocol.VERSION) {
                return rejected(events, "helper-protocol",
                        "The target helper protocol is stale; run product Environment Preparation before uploading source");
            }
            events.add(new DeploymentEvent("helper-protocol", true,
                    "Target helper protocol " + ManagedHelperProtocol.VERSION + " was verified before source upload"));
            long availableBytes = serverCapabilities.availableBytes();
            if (availableBytes < requiredBytes) {
                return rejected(events, "target-space", "Target free space is insufficient for the reviewed candidate");
            }
            events.add(new DeploymentEvent("target-capabilities", true,
                    "Target capabilities were collected before the reviewed deployment"));
            HostSupportDecision typedCompatibility = HostSupportEvaluator.evaluate(
                    session.collectDeploymentCapabilities(), request.facts(), request.runtime());
            events.add(new DeploymentEvent("typed-host-compatibility",
                    typedCompatibility.support() == HostSupportStatus.READY_FOR_RUNTIME_VALIDATION,
                    String.join("; ", typedCompatibility.evidence())));
            if (typedCompatibility.support() != HostSupportStatus.READY_FOR_RUNTIME_VALIDATION) {
                return new DeploymentResult(DeploymentStatus.PRECONDITION_REJECTED, events, Optional.empty(), Optional.empty());
            }

            candidateMayExist = true;
            var receipt = session.uploadSource(request.archive(), workspace);
            if (!receipt.contentSha256().equals(request.archive().contentSha256())
                    || receipt.byteCount() != request.archive().byteCount()) {
                cleanup(session, workspace, events);
                return rejected(events, "source-upload", "Target archive digest or size differs from the reviewed archive");
            }
            events.add(new DeploymentEvent("source-upload", true, receipt.evidence()));

            build = session.buildDeployment(request.facts(), request.runtime(), workspace, request.limits(),
                    request.configuration());
            events.add(new DeploymentEvent("remote-build", build.succeeded(), build.evidence()));
            if (!build.succeeded()) {
                cleanup(session, workspace, events);
                return new DeploymentResult(DeploymentStatus.FAILED_BUILD, events, Optional.empty(), Optional.empty());
            }
            if (!request.archive().contentSha256().equals(build.sourceSha256())) {
                cleanup(session, workspace, events);
                return rejected(events, "build-provenance", "The build result is not bound to the reviewed source archive");
            }

            inputs = session.stageDeploymentInputs(application, request.configuration(), resolvedSecrets);
            events.add(new DeploymentEvent("deployment-inputs", true,
                    "Immutable configuration and exact secret revisions were sealed outside the release tree"));

            snapshot = session.snapshotDeployment(application, request.runtime());
            events.add(new DeploymentEvent("snapshot", true, snapshot.evidence()));
            RemoteStepResult publish = session.publishDeployment(application, request.facts(), workspace, build, releaseIdentity,
                    request.runtime(), inputs, snapshot);
            events.add(new DeploymentEvent("publish", publish.succeeded(), publish.evidence()));
            if (!publish.succeeded()) {
                return recover(session, request, application, workspace, snapshot, build, releaseIdentity, inputs, events);
            }
            HealthCheckResult health = session.checkDeploymentHealth(application, request.runtime(), request.runtime().healthCheck());
            events.add(new DeploymentEvent("candidate-health", health.healthy(), health.evidence()));
            if (!health.healthy()) {
                return recover(session, request, application, workspace, snapshot, build, releaseIdentity, inputs, events);
            }
            LifecycleObservation observation = session.observeDeployment(application, request.runtime());
            events.add(new DeploymentEvent("final-observation", observation.ownershipVerified(), observation.evidence()));
            if (!observation.ownershipVerified()) {
                return recover(session, request, application, workspace, snapshot, build, releaseIdentity, inputs, events);
            }
            RemoteStepResult retention = session.retainRecentSuccessfulReleases(application);
            events.add(new DeploymentEvent("release-retention", retention.succeeded(), retention.evidence()));
            cleanup(session, workspace, events);
            return new DeploymentResult(DeploymentStatus.SUCCEEDED, events, Optional.of(observation),
                    Optional.of(releaseIdentity));
        } catch (LinuxOperationException | ArithmeticException exception) {
            events.add(new DeploymentEvent("linux-operation", false, safeMessage(exception)));
            if (snapshot != null && build != null && build.succeeded() && inputs != null) {
                return recoverAfterInterruptedSession(request, application, gateway, endpoint, credential,
                        hostKeyVerifier, workspace, snapshot, build, releaseIdentity, inputs, events);
            }
            if (candidateMayExist) {
                return cleanupAfterInterruptedSession(gateway, endpoint, credential, hostKeyVerifier, workspace, events);
            }
            return new DeploymentResult(DeploymentStatus.PRECONDITION_REJECTED, events, Optional.empty(), Optional.empty());
        } finally {
            credential.clear();
        }
    }

    private static DeploymentResult recover(DeploymentRemoteSession session, ReviewedDeploymentRequest request,
                                             ManagedApplication application, RemoteWorkspace workspace,
                                             ReleaseSnapshot snapshot, DeploymentBuildResult build,
                                             String releaseIdentity, DeploymentInputManifest inputs,
                                             List<DeploymentEvent> events)
            throws LinuxOperationException {
        RemoteStepResult rollback = session.rollbackDeployment(application, snapshot, build, releaseIdentity,
                request.runtime(), inputs);
        events.add(new DeploymentEvent("rollback", rollback.succeeded(), rollback.evidence()));
        cleanup(session, workspace, events);
        if (!rollback.succeeded()) {
            return new DeploymentResult(DeploymentStatus.MANUAL_RECOVERY_REQUIRED, events, Optional.empty(), Optional.empty());
        }
        if (!snapshot.hasPreviousRelease()) {
            return new DeploymentResult(DeploymentStatus.FAILED_FIRST_DEPLOYMENT, events, Optional.empty(), Optional.empty());
        }
        LifecycleObservation restored;
        try {
            restored = session.observe(application);
        } catch (LinuxOperationException exception) {
            events.add(new DeploymentEvent("rollback-observation", false,
                    "Rollback completed but its restored runtime could not be observed: " + safeMessage(exception)));
            return new DeploymentResult(DeploymentStatus.MANUAL_RECOVERY_REQUIRED, events,
                    Optional.empty(), Optional.empty());
        }
        events.add(new DeploymentEvent("rollback-observation", restored.ownershipVerified(), restored.evidence()));
        boolean expectedState = snapshot.previousWasRunning()
                ? restored.runtimeState() == RuntimeState.RUNNING : restored.runtimeState() == RuntimeState.STOPPED;
        if (!restored.ownershipVerified() || !expectedState) {
            return new DeploymentResult(DeploymentStatus.MANUAL_RECOVERY_REQUIRED, events, Optional.of(restored), Optional.empty());
        }
        return new DeploymentResult(DeploymentStatus.FAILED_ROLLED_BACK, events, Optional.of(restored), Optional.empty());
    }

    private static DeploymentResult recoverAfterInterruptedSession(
            ReviewedDeploymentRequest request, ManagedApplication application, DeploymentLinuxGateway gateway,
            SshEndpoint endpoint, SshCredential credential, HostKeyEvaluator hostKeyVerifier, RemoteWorkspace workspace,
            ReleaseSnapshot snapshot, DeploymentBuildResult build, String releaseIdentity, DeploymentInputManifest inputs,
            List<DeploymentEvent> events
    ) {
        try (DeploymentRemoteSession recoverySession = gateway.connect(
                endpoint, credential.duplicate(), hostKeyVerifier)) {
            events.add(new DeploymentEvent("recovery-reconnect", true,
                    "SSH host was reverified after the typed publication session was interrupted"));
            return recover(recoverySession, request, application, workspace, snapshot, build, releaseIdentity, inputs,
                    events);
        } catch (LinuxOperationException exception) {
            events.add(new DeploymentEvent("rollback", false,
                    "Could not reconnect and complete typed rollback: " + safeMessage(exception)));
            return new DeploymentResult(DeploymentStatus.MANUAL_RECOVERY_REQUIRED, events,
                    Optional.empty(), Optional.empty());
        }
    }

    private static DeploymentResult cleanupAfterInterruptedSession(
            DeploymentLinuxGateway gateway, SshEndpoint endpoint, SshCredential credential,
            HostKeyEvaluator hostKeyVerifier, RemoteWorkspace workspace, List<DeploymentEvent> events
    ) {
        try (DeploymentRemoteSession cleanupSession = gateway.connect(
                endpoint, credential.duplicate(), hostKeyVerifier)) {
            events.add(new DeploymentEvent("candidate-cleanup-reconnect", true,
                    "SSH host was reverified after the typed candidate session was interrupted"));
            if (cleanup(cleanupSession, workspace, events)) {
                return new DeploymentResult(DeploymentStatus.PRECONDITION_REJECTED, events,
                        Optional.empty(), Optional.empty());
            }
        } catch (LinuxOperationException exception) {
            events.add(new DeploymentEvent("candidate-cleanup-reconnect", false,
                    "Could not reconnect and verify typed candidate cleanup: " + safeMessage(exception)));
        }
        return new DeploymentResult(DeploymentStatus.MANUAL_RECOVERY_REQUIRED, events,
                Optional.empty(), Optional.empty());
    }

    private static DeploymentResult rejected(List<DeploymentEvent> events, String step, String evidence) {
        events.add(new DeploymentEvent(step, false, evidence));
        return new DeploymentResult(DeploymentStatus.PRECONDITION_REJECTED, events, Optional.empty(), Optional.empty());
    }

    private static boolean cleanup(DeploymentRemoteSession session, RemoteWorkspace workspace, List<DeploymentEvent> events) {
        try {
            RemoteStepResult cleanup = session.cleanupCandidate(workspace);
            events.add(new DeploymentEvent("candidate-cleanup", cleanup.succeeded(), cleanup.evidence()));
            return cleanup.succeeded();
        } catch (LinuxOperationException exception) {
            events.add(new DeploymentEvent("candidate-cleanup", false, safeMessage(exception)));
            return false;
        }
    }

    private static String safeMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? "Controlled Linux operation failed" : message;
    }
}
