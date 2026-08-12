package gold.debug.windowstolinux.shared.deploy.transaction;

import gold.debug.windowstolinux.shared.deploy.plan.ReviewedDeploymentRequest;
import gold.debug.windowstolinux.shared.deploy.result.DeploymentEvent;
import gold.debug.windowstolinux.shared.deploy.result.DeploymentResult;
import gold.debug.windowstolinux.shared.deploy.compatibility.HostCompatibility;
import gold.debug.windowstolinux.shared.deploy.compatibility.HostSupport;
import gold.debug.windowstolinux.shared.linux.build.DeploymentBuildResult;
import gold.debug.windowstolinux.shared.linux.connection.DeploymentLinuxGateway;
import gold.debug.windowstolinux.shared.linux.connection.DeploymentRemoteSession;
import gold.debug.windowstolinux.shared.linux.connection.HostKeyVerifier;
import gold.debug.windowstolinux.shared.linux.connection.LinuxOperationException;
import gold.debug.windowstolinux.shared.linux.connection.SshCredential;
import gold.debug.windowstolinux.shared.linux.connection.SshEndpoint;
import gold.debug.windowstolinux.shared.linux.protocol.ReleaseSnapshot;
import gold.debug.windowstolinux.shared.linux.protocol.RemoteStepResult;
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
                                   HostKeyVerifier hostKeyVerifier) {
        request = java.util.Objects.requireNonNull(request, "request");
        application = java.util.Objects.requireNonNull(application, "application");
        gateway = java.util.Objects.requireNonNull(gateway, "gateway");
        endpoint = java.util.Objects.requireNonNull(endpoint, "endpoint");
        credential = java.util.Objects.requireNonNull(credential, "credential");
        hostKeyVerifier = java.util.Objects.requireNonNull(hostKeyVerifier, "hostKeyVerifier");
        List<DeploymentEvent> events = new ArrayList<>();
        if (!application.server().equals(request.server()) || !application.id().equals(request.facts().applicationId())) {
            return rejected(events, "managed-identity", "Reviewed request and managed application identity do not match");
        }
        if (request.limits().runAsRoot() != "root".equals(endpoint.username())) {
            return rejected(events, "root-build-session",
                    "Root build approval and the SSH account must agree before a candidate is created");
        }
        RemoteWorkspace workspace = new RemoteWorkspace(application.id(), request.archive().contentSha256());
        String releaseIdentity = request.sourceRevision().sourceSha256();
        ReleaseSnapshot snapshot = null;
        DeploymentBuildResult build = null;
        try (DeploymentRemoteSession session = gateway.connect(endpoint, copyCredential(credential), hostKeyVerifier)) {
            long sourceFootprint = Math.addExact(request.archive().byteCount(), request.archive().uncompressedByteCount());
            if (sourceFootprint > request.limits().maxWorkspaceBytes()) {
                return rejected(events, "source-workspace", "The reviewed archive exceeds the confirmed workspace limit");
            }
            long requiredBytes = Math.max(Math.multiplyExact(request.archive().byteCount(), MINIMUM_FREE_SPACE_MULTIPLIER),
                    request.limits().maxWorkspaceBytes());
            long availableBytes = session.collectCapabilities().availableBytes();
            if (availableBytes < requiredBytes) {
                return rejected(events, "target-space", "Target free space is insufficient for the reviewed candidate");
            }
            events.add(new DeploymentEvent("target-capabilities", true,
                    "Target capabilities were collected before the reviewed deployment"));
            HostCompatibility.Result typedCompatibility = HostCompatibility.evaluate(
                    session.collectDeploymentCapabilities(), request.runtime());
            events.add(new DeploymentEvent("typed-host-compatibility",
                    typedCompatibility.support() == HostSupport.READY_FOR_RUNTIME_VALIDATION,
                    String.join("; ", typedCompatibility.evidence())));
            if (typedCompatibility.support() != HostSupport.READY_FOR_RUNTIME_VALIDATION) {
                return new DeploymentResult(DeploymentStatus.PRECONDITION_REJECTED, events, Optional.empty(), Optional.empty());
            }

            var receipt = session.uploadSource(request.archive(), workspace);
            if (!receipt.contentSha256().equals(request.archive().contentSha256())
                    || receipt.byteCount() != request.archive().byteCount()) {
                return rejected(events, "source-upload", "Target archive digest or size differs from the reviewed archive");
            }
            events.add(new DeploymentEvent("source-upload", true, receipt.evidence()));

            build = session.buildDeployment(request.facts(), request.runtime(), workspace, request.limits());
            events.add(new DeploymentEvent("remote-build", build.succeeded(), build.evidence()));
            if (!build.succeeded()) {
                cleanup(session, workspace, events);
                return new DeploymentResult(DeploymentStatus.FAILED_BUILD, events, Optional.empty(), Optional.empty());
            }
            if (!request.archive().contentSha256().equals(build.sourceSha256())) {
                cleanup(session, workspace, events);
                return rejected(events, "build-provenance", "The build result is not bound to the reviewed source archive");
            }

            snapshot = session.snapshotDeployment(application, request.runtime());
            events.add(new DeploymentEvent("snapshot", true, snapshot.evidence()));
            RemoteStepResult publish = session.publishDeployment(application, workspace, build, releaseIdentity,
                    request.runtime(), snapshot);
            events.add(new DeploymentEvent("publish", publish.succeeded(), publish.evidence()));
            if (!publish.succeeded()) {
                return recover(session, request, application, workspace, snapshot, build, releaseIdentity, events);
            }
            HealthCheckResult health = session.checkDeploymentHealth(application, request.runtime(), request.runtime().healthCheck());
            events.add(new DeploymentEvent("candidate-health", health.healthy(), health.evidence()));
            if (!health.healthy()) {
                return recover(session, request, application, workspace, snapshot, build, releaseIdentity, events);
            }
            LifecycleObservation observation = session.observeDeployment(application, request.runtime());
            events.add(new DeploymentEvent("final-observation", observation.ownershipVerified(), observation.evidence()));
            if (!observation.ownershipVerified()) {
                return recover(session, request, application, workspace, snapshot, build, releaseIdentity, events);
            }
            RemoteStepResult retention = session.retainRecentSuccessfulReleases(application);
            events.add(new DeploymentEvent("release-retention", retention.succeeded(), retention.evidence()));
            cleanup(session, workspace, events);
            return new DeploymentResult(DeploymentStatus.SUCCEEDED, events, Optional.of(observation),
                    Optional.of(releaseIdentity));
        } catch (LinuxOperationException | ArithmeticException exception) {
            events.add(new DeploymentEvent("linux-operation", false, safeMessage(exception)));
            return new DeploymentResult(DeploymentStatus.PRECONDITION_REJECTED, events, Optional.empty(), Optional.empty());
        } finally {
            clearCredential(credential);
        }
    }

    private static DeploymentResult recover(DeploymentRemoteSession session, ReviewedDeploymentRequest request,
                                             ManagedApplication application, RemoteWorkspace workspace,
                                             ReleaseSnapshot snapshot, DeploymentBuildResult build,
                                             String releaseIdentity, List<DeploymentEvent> events)
            throws LinuxOperationException {
        RemoteStepResult rollback = session.rollbackDeployment(application, snapshot, build, releaseIdentity, request.runtime());
        events.add(new DeploymentEvent("rollback", rollback.succeeded(), rollback.evidence()));
        cleanup(session, workspace, events);
        if (!rollback.succeeded()) {
            return new DeploymentResult(DeploymentStatus.MANUAL_RECOVERY_REQUIRED, events, Optional.empty(), Optional.empty());
        }
        if (!snapshot.hasPreviousRelease()) {
            return new DeploymentResult(DeploymentStatus.FAILED_FIRST_DEPLOYMENT, events, Optional.empty(), Optional.empty());
        }
        LifecycleObservation restored = session.observeDeployment(application, request.runtime());
        events.add(new DeploymentEvent("rollback-observation", restored.ownershipVerified(), restored.evidence()));
        boolean expectedState = snapshot.previousWasRunning()
                ? restored.runtimeState() == RuntimeState.RUNNING : restored.runtimeState() == RuntimeState.STOPPED;
        if (!restored.ownershipVerified() || !expectedState) {
            return new DeploymentResult(DeploymentStatus.MANUAL_RECOVERY_REQUIRED, events, Optional.of(restored), Optional.empty());
        }
        return new DeploymentResult(DeploymentStatus.FAILED_ROLLED_BACK, events, Optional.of(restored), Optional.empty());
    }

    private static DeploymentResult rejected(List<DeploymentEvent> events, String step, String evidence) {
        events.add(new DeploymentEvent(step, false, evidence));
        return new DeploymentResult(DeploymentStatus.PRECONDITION_REJECTED, events, Optional.empty(), Optional.empty());
    }

    private static void cleanup(DeploymentRemoteSession session, RemoteWorkspace workspace, List<DeploymentEvent> events) {
        try {
            RemoteStepResult cleanup = session.cleanupCandidate(workspace);
            events.add(new DeploymentEvent("candidate-cleanup", cleanup.succeeded(), cleanup.evidence()));
        } catch (LinuxOperationException exception) {
            events.add(new DeploymentEvent("candidate-cleanup", false, safeMessage(exception)));
        }
    }

    private static SshCredential copyCredential(SshCredential credential) {
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

    private static void clearCredential(SshCredential credential) {
        if (credential instanceof SshCredential.Password password) {
            password.clear();
        }
    }

    private static String safeMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? "Controlled Linux operation failed" : message;
    }
}
