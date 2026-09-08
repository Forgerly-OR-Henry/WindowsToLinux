package gold.debug.windowstolinux.shared.deploy.execution.transaction;

import gold.debug.windowstolinux.shared.deploy.contract.ReviewedDeploymentRequest;
import gold.debug.windowstolinux.shared.deploy.plan.ReviewedReleaseIdentityResolver;
import gold.debug.windowstolinux.shared.config.secretref.ResolvedSecretRevision;
import gold.debug.windowstolinux.shared.config.revision.DeploymentInputManifest;
import gold.debug.windowstolinux.shared.deploy.contract.result.deployment.DeploymentEvent;
import gold.debug.windowstolinux.shared.model.deployment.DeploymentTraceEvent;
import gold.debug.windowstolinux.shared.deploy.contract.result.deployment.DeploymentResult;
import gold.debug.windowstolinux.shared.deploy.contract.result.compatibility.HostSupportStatus;
import gold.debug.windowstolinux.shared.deploy.contract.result.compatibility.HostSupportDecision;
import gold.debug.windowstolinux.shared.deploy.support.HostSupportEvaluator;
import gold.debug.windowstolinux.shared.deploy.error.DeploymentExecutionFailureType;
import gold.debug.windowstolinux.shared.linux.build.DeploymentBuildResult;
import gold.debug.windowstolinux.shared.linux.connection.DeploymentLinuxGateway;
import gold.debug.windowstolinux.shared.linux.session.DeploymentRemoteSession;
import gold.debug.windowstolinux.shared.linux.connection.HostKeyEvaluator;
import gold.debug.windowstolinux.shared.linux.error.LinuxOperationException;
import gold.debug.windowstolinux.shared.linux.connection.SshCredential;
import gold.debug.windowstolinux.shared.linux.connection.SshEndpoint;
import gold.debug.windowstolinux.shared.linux.protocol.ReleaseSnapshot;
import gold.debug.windowstolinux.shared.linux.protocol.RemoteStepResult;
import gold.debug.windowstolinux.shared.linux.protocol.backup.ManagedContentPublication;
import gold.debug.windowstolinux.shared.linux.protocol.ManagedHelperProtocol;
import gold.debug.windowstolinux.shared.linux.runtime.HealthCheckResult;
import gold.debug.windowstolinux.shared.linux.transfer.RemoteWorkspace;
import gold.debug.windowstolinux.shared.model.deployment.DeploymentStatus;
import gold.debug.windowstolinux.shared.model.lifecycle.LifecycleObservation;
import gold.debug.windowstolinux.shared.model.lifecycle.RuntimeState;
import gold.debug.windowstolinux.shared.model.managed.ManagedApplication;
import gold.debug.windowstolinux.shared.model.failure.FailureDescriptor;
import gold.debug.windowstolinux.shared.model.failure.OperationIdentity;

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
        return deploy(request, application, gateway, endpoint, credential, hostKeyVerifier, resolvedSecrets, ignored -> { });
    }

    /** Executes with a per-operation observer of real transaction events. */
    public DeploymentResult deploy(ReviewedDeploymentRequest request, ManagedApplication application,
            DeploymentLinuxGateway gateway, SshEndpoint endpoint, SshCredential credential,
            HostKeyEvaluator hostKeyVerifier, List<ResolvedSecretRevision> resolvedSecrets,
            java.util.function.Consumer<gold.debug.windowstolinux.shared.deploy.contract.result.deployment.DeploymentEvent> progress) {
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
        List<DeploymentEvent> events = new DeploymentEventJournal(progress);
        DeploymentResult initialRejection = validateRequestBinding(request, application, endpoint, events);
        if (initialRejection != null) return initialRejection;
        RemoteWorkspace workspace = new RemoteWorkspace(application.id(), request.archive().contentSha256());
        String releaseIdentity = ReviewedReleaseIdentityResolver.from(request);
        ReleaseSnapshot snapshot = null;
        DeploymentBuildResult build = null;
        DeploymentInputManifest inputs = null;
        boolean candidateMayExist = false;
        try (DeploymentRemoteSession session = gateway.connect(endpoint, credential.duplicate(), hostKeyVerifier)) {
            DeploymentResult preflightRejection = preflight(request, session, events);
            if (preflightRejection != null) return preflightRejection;

            candidateMayExist = true;
            var receipt = session.uploadSource(request.archive(), workspace);
            if (!receipt.contentSha256().equals(request.archive().contentSha256())
                    || receipt.byteCount() != request.archive().byteCount()) {
                cleanup(session, workspace, events);
                return rejected(events, DeploymentTraceEvent.SOURCE_UPLOAD,
                        "Target archive digest or size differs from the reviewed archive");
            }
            events.add(DeploymentEvent.result(DeploymentTraceEvent.SOURCE_UPLOAD, true, receipt.evidence()));

            build = session.buildDeployment(request.facts(), request.runtime(), workspace, request.limits(),
                    request.configuration());
            events.add(DeploymentEvent.result(DeploymentTraceEvent.REMOTE_BUILD, build.succeeded(), build.evidence()));
            if (!build.succeeded()) {
                cleanup(session, workspace, events);
                return new DeploymentResult(DeploymentStatus.FAILED_BUILD, events, Optional.empty(), Optional.empty());
            }
            if (!request.archive().contentSha256().equals(build.sourceSha256())) {
                cleanup(session, workspace, events);
                return rejected(events, DeploymentTraceEvent.BUILD_PROVENANCE,
                        "The build result is not bound to the reviewed source archive");
            }

            inputs = session.stageDeploymentInputs(application, request.configuration(), resolvedSecrets);
            events.add(DeploymentEvent.result(DeploymentTraceEvent.DEPLOYMENT_INPUTS, true,
                    "Immutable configuration and exact secret revisions were sealed outside the release tree"));

            snapshot = session.snapshotDeployment(application, request.runtime());
            events.add(DeploymentEvent.result(DeploymentTraceEvent.SNAPSHOT, true, snapshot.evidence()));
            RemoteStepResult publish = session.publishDeployment(application, request.facts(), workspace, build, releaseIdentity,
                    request.runtime(), inputs,
                    new ManagedContentPublication(application.id(), application.id(), java.util.List.of()), snapshot);
            events.add(DeploymentEvent.result(DeploymentTraceEvent.PUBLISH, publish.succeeded(), publish.evidence()));
            if (!publish.succeeded()) {
                return recover(session, request, application, workspace, snapshot, build, releaseIdentity, inputs, events);
            }
            HealthCheckResult health = session.checkDeploymentHealth(application, request.runtime(), request.runtime().healthCheck());
            events.add(DeploymentEvent.result(DeploymentTraceEvent.CANDIDATE_HEALTH, health.healthy(), health.evidence()));
            if (!health.healthy()) {
                return recover(session, request, application, workspace, snapshot, build, releaseIdentity, inputs, events);
            }
            LifecycleObservation observation = session.observeDeployment(application, request.runtime());
            events.add(DeploymentEvent.result(DeploymentTraceEvent.FINAL_OBSERVATION, observation.ownershipVerified(), observation.evidence()));
            if (!observation.ownershipVerified()) {
                return recover(session, request, application, workspace, snapshot, build, releaseIdentity, inputs, events);
            }
            RemoteStepResult retention = session.retainRecentSuccessfulReleases(application);
            events.add(DeploymentEvent.result(DeploymentTraceEvent.RELEASE_RETENTION, retention.succeeded(), retention.evidence()));
            cleanup(session, workspace, events);
            return new DeploymentResult(DeploymentStatus.SUCCEEDED, events, Optional.of(observation),
                    Optional.of(releaseIdentity));
        } catch (LinuxOperationException exception) {
            events.add(DeploymentEvent.failed(DeploymentTraceEvent.LINUX_OPERATION, exception.failure()));
            if (snapshot != null && build != null && build.succeeded() && inputs != null) {
                return recoverAfterInterruptedSession(request, application, gateway, endpoint, credential,
                        hostKeyVerifier, workspace, snapshot, build, releaseIdentity, inputs, events);
            }
            if (candidateMayExist) {
                return cleanupAfterInterruptedSession(gateway, endpoint, credential, hostKeyVerifier, workspace, events);
            }
            return new DeploymentResult(DeploymentStatus.PRECONDITION_REJECTED, events, Optional.empty(), Optional.empty());
        } catch (ArithmeticException exception) {
            events.add(DeploymentEvent.failed(DeploymentTraceEvent.APPLICATION_PREFLIGHT,
                    failure(DeploymentExecutionFailureType.ARITHMETIC_OVERFLOW,
                            "Deployment size arithmetic exceeded its bounded range")));
            if (candidateMayExist) {
                return cleanupAfterInterruptedSession(gateway, endpoint, credential, hostKeyVerifier, workspace, events);
            }
            return new DeploymentResult(DeploymentStatus.PRECONDITION_REJECTED, events, Optional.empty(), Optional.empty());
        } finally {
            credential.clear();
        }
    }

    private static DeploymentResult validateRequestBinding(
            ReviewedDeploymentRequest request, ManagedApplication application, SshEndpoint endpoint,
            List<DeploymentEvent> events) {
        if (!application.server().equals(request.server())
                || !application.id().equals(request.facts().applicationId())) {
            return rejected(events, DeploymentTraceEvent.MANAGED_IDENTITY,
                    "Reviewed request and managed application identity do not match");
        }
        if (request.limits().runAsRoot() != "root".equals(endpoint.username())) {
            return rejected(events, DeploymentTraceEvent.ROOT_BUILD_SESSION,
                    "Root build approval and the SSH account must agree before a candidate is created");
        }
        return null;
    }

    private static DeploymentResult preflight(
            ReviewedDeploymentRequest request, DeploymentRemoteSession session, List<DeploymentEvent> events)
            throws LinuxOperationException {
        long sourceFootprint = Math.addExact(request.archive().byteCount(), request.archive().uncompressedByteCount());
        if (sourceFootprint > request.limits().maxWorkspaceBytes()) {
            return rejected(events, DeploymentTraceEvent.SOURCE_WORKSPACE,
                    "The reviewed archive exceeds the confirmed workspace limit");
        }
        long requiredBytes = Math.max(Math.multiplyExact(request.archive().byteCount(), MINIMUM_FREE_SPACE_MULTIPLIER),
                request.limits().maxWorkspaceBytes());
        var serverCapabilities = session.collectCapabilities();
        if (serverCapabilities.managedHelperProtocolVersion() != ManagedHelperProtocol.VERSION) {
            return rejected(events, DeploymentTraceEvent.HELPER_PROTOCOL,
                    "The target helper protocol is stale; run product Environment Preparation before uploading source");
        }
        events.add(DeploymentEvent.result(DeploymentTraceEvent.HELPER_PROTOCOL, true,
                "Target helper protocol " + ManagedHelperProtocol.VERSION + " was verified before source upload"));
        if (serverCapabilities.availableBytes() < requiredBytes) {
            return rejected(events, DeploymentTraceEvent.TARGET_SPACE,
                    "Target free space is insufficient for the reviewed candidate");
        }
        events.add(DeploymentEvent.result(DeploymentTraceEvent.TARGET_CAPABILITIES, true,
                "Target capabilities were collected before the reviewed deployment"));
        HostSupportDecision typedCompatibility = HostSupportEvaluator.evaluate(
                session.collectDeploymentCapabilities(), request.facts(), request.runtime());
        events.add(DeploymentEvent.result(DeploymentTraceEvent.TYPED_HOST_COMPATIBILITY,
                typedCompatibility.support() == HostSupportStatus.READY_FOR_RUNTIME_VALIDATION,
                String.join("; ", typedCompatibility.evidence())));
        return typedCompatibility.support() == HostSupportStatus.READY_FOR_RUNTIME_VALIDATION ? null
                : new DeploymentResult(DeploymentStatus.PRECONDITION_REJECTED, events,
                Optional.empty(), Optional.empty());
    }

    private static DeploymentResult recover(DeploymentRemoteSession session, ReviewedDeploymentRequest request,
                                             ManagedApplication application, RemoteWorkspace workspace,
                                             ReleaseSnapshot snapshot, DeploymentBuildResult build,
                                             String releaseIdentity, DeploymentInputManifest inputs,
                                             List<DeploymentEvent> events)
            throws LinuxOperationException {
        RemoteStepResult rollback = session.rollbackDeployment(application, snapshot, build, releaseIdentity,
                request.runtime(), inputs);
        events.add(DeploymentEvent.result(DeploymentTraceEvent.ROLLBACK, rollback.succeeded(), rollback.evidence()));
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
            events.add(DeploymentEvent.failed(DeploymentTraceEvent.ROLLBACK_OBSERVATION, exception.failure()));
            return new DeploymentResult(DeploymentStatus.MANUAL_RECOVERY_REQUIRED, events,
                    Optional.empty(), Optional.empty());
        }
        events.add(DeploymentEvent.result(DeploymentTraceEvent.ROLLBACK_OBSERVATION, restored.ownershipVerified(), restored.evidence()));
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
            events.add(DeploymentEvent.result(DeploymentTraceEvent.RECOVERY_RECONNECT, true,
                    "SSH host was reverified after the typed publication session was interrupted"));
            return recover(recoverySession, request, application, workspace, snapshot, build, releaseIdentity, inputs,
                    events);
        } catch (LinuxOperationException exception) {
            events.add(DeploymentEvent.failed(DeploymentTraceEvent.ROLLBACK, exception.failure()));
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
            events.add(DeploymentEvent.result(DeploymentTraceEvent.CANDIDATE_CLEANUP_RECONNECT, true,
                    "SSH host was reverified after the typed candidate session was interrupted"));
            if (cleanup(cleanupSession, workspace, events)) {
                return new DeploymentResult(DeploymentStatus.PRECONDITION_REJECTED, events,
                        Optional.empty(), Optional.empty());
            }
        } catch (LinuxOperationException exception) {
            events.add(DeploymentEvent.failed(
                    DeploymentTraceEvent.CANDIDATE_CLEANUP_RECONNECT, exception.failure()));
        }
        return new DeploymentResult(DeploymentStatus.MANUAL_RECOVERY_REQUIRED, events,
                Optional.empty(), Optional.empty());
    }

    private static DeploymentResult rejected(
            List<DeploymentEvent> events, DeploymentTraceEvent step, String evidence) {
        events.add(DeploymentEvent.failed(step,
                failure(DeploymentExecutionFailureType.PRECONDITION_REJECTED, evidence)));
        return new DeploymentResult(DeploymentStatus.PRECONDITION_REJECTED, events, Optional.empty(), Optional.empty());
    }

    private static boolean cleanup(DeploymentRemoteSession session, RemoteWorkspace workspace, List<DeploymentEvent> events) {
        try {
            RemoteStepResult cleanup = session.cleanupCandidate(workspace);
            events.add(DeploymentEvent.result(DeploymentTraceEvent.CANDIDATE_CLEANUP, cleanup.succeeded(), cleanup.evidence()));
            return cleanup.succeeded();
        } catch (LinuxOperationException exception) {
            events.add(DeploymentEvent.failed(DeploymentTraceEvent.CANDIDATE_CLEANUP, exception.failure()));
            return false;
        }
    }

    private static FailureDescriptor failure(DeploymentExecutionFailureType type, String diagnostic) {
        return FailureDescriptor.create(type, OperationIdentity.create(), diagnostic);
    }
}
