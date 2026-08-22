package gold.debug.windowstolinux.shared.deploy.execution.transaction;

import gold.debug.windowstolinux.shared.deploy.contract.spi.CandidatePortMode;
import gold.debug.windowstolinux.shared.deploy.contract.spi.CandidatePortPlan;
import gold.debug.windowstolinux.shared.deploy.contract.spi.RestoreDeploymentComponent;
import gold.debug.windowstolinux.shared.deploy.contract.spi.RestoreDeploymentPort;
import gold.debug.windowstolinux.shared.deploy.contract.spi.RestoreDeploymentRequest;
import gold.debug.windowstolinux.shared.linux.error.LinuxOperationException;
import gold.debug.windowstolinux.shared.linux.protocol.restore.RemoteRestoreActivationComponent;
import gold.debug.windowstolinux.shared.linux.protocol.restore.RemoteRestoreActivationMode;
import gold.debug.windowstolinux.shared.linux.protocol.restore.RemoteRestoreActivationPort;
import gold.debug.windowstolinux.shared.linux.protocol.restore.RemoteRestoreActivationRequest;
import gold.debug.windowstolinux.shared.linux.protocol.restore.RemoteRestorePortBinding;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Production deploy implementation for one fail-closed remote restore transaction. / 单个故障关闭远程恢复事务的生产 deploy 实现。 */
public final class ManagedRestoreDeploymentPort implements RestoreDeploymentPort {
    private final RemoteRestoreActivationPort remote;
    private final RestoreCandidatePortPlanner planner;
    private final Map<String, RemoteRestoreActivationRequest> attempts = new LinkedHashMap<>();

    /** Creates a deploy-owned transaction over the fixed Linux activation capability. / 基于固定 Linux 激活能力创建 deploy 持有的事务。 */
    public ManagedRestoreDeploymentPort(RemoteRestoreActivationPort remote) {
        this(remote, new RestoreCandidatePortPlanner());
    }

    ManagedRestoreDeploymentPort(RemoteRestoreActivationPort remote, RestoreCandidatePortPlanner planner) {
        this.remote = Objects.requireNonNull(remote, "remote");
        this.planner = Objects.requireNonNull(planner, "planner");
    }

    /** Starts once and verifies every candidate component. / 仅启动一次并验证每个候选组件。 */
    @Override
    public synchronized HealthEvidence verifyComponents(
            RestoreDeploymentRequest request, Optional<String> databaseToken) {
        requireDatabaseToken(databaseToken);
        try {
            RemoteRestoreActivationRequest activation = attempts.get(request.candidateId());
            if (activation == null) {
                var preflight = remote.inspectRestoreActivation(request.applicationId(), 0);
                if (!preflight.managedRootWritable() || preflight.foreignApplicationConflict()) {
                    return new HealthEvidence(false, preflight.evidence());
                }
                CandidatePortPlan ports = planner.plan(request, preflight.occupiedTcpPorts());
                activation = activation(request, ports);
                RemoteRestoreActivationPort.StepEvidence started = remote.startRestoreActivation(activation);
                if (!started.completed()) return new HealthEvidence(false, started.evidence());
                attempts.put(request.candidateId(), activation);
            }
            RemoteRestoreActivationPort.StepEvidence verified = remote.verifyRestoreComponents(activation);
            return new HealthEvidence(verified.completed(), verified.evidence());
        } catch (LinuxOperationException | RuntimeException exception) {
            throw operation("remote restore component activation failed", exception);
        }
    }

    /** Verifies the candidate application gate without accepting a different request. / 验证候选整应用门且不接受不同请求。 */
    @Override
    public synchronized HealthEvidence verifyApplication(
            RestoreDeploymentRequest request, Optional<String> databaseToken) {
        requireDatabaseToken(databaseToken);
        try {
            RemoteRestoreActivationRequest activation = requireAttempt(request);
            RemoteRestoreActivationPort.StepEvidence verified = remote.verifyRestoreApplication(activation);
            return new HealthEvidence(verified.completed(), verified.evidence());
        } catch (LinuxOperationException | RuntimeException exception) {
            throw operation("remote restore application health failed", exception);
        }
    }

    /** Commits formal identities and requires the final formal health pass. / 提交正式身份并要求最终正式健康通过。 */
    @Override
    public synchronized CommitEvidence commit(
            RestoreDeploymentRequest request, Optional<String> databaseToken) {
        requireDatabaseToken(databaseToken);
        try {
            RemoteRestoreActivationPort.CommitEvidence committed = remote.commitRestoreActivation(requireAttempt(request));
            if (!committed.formalComponentsHealthy() || !committed.formalApplicationHealthy()) {
                return new CommitEvidence(false, committed.previousReleaseRetained(),
                        committed.activeReleaseToken(), committed.evidence());
            }
            if (committed.committed()) attempts.remove(request.candidateId());
            return new CommitEvidence(committed.committed(), committed.previousReleaseRetained(),
                    committed.activeReleaseToken(), committed.evidence());
        } catch (LinuxOperationException | RuntimeException exception) {
            throw operation("remote restore formal commit failed", exception);
        }
    }

    /** Recovers idempotently even when activation failed before local state was retained. / 即使激活在保存本地状态前失败也执行幂等恢复。 */
    @Override
    public synchronized RecoveryEvidence recoverExisting(RestoreDeploymentRequest request) {
        try {
            RemoteRestoreActivationRequest activation = attempts.get(request.candidateId());
            if (activation == null) {
                var preflight = remote.inspectRestoreActivation(request.applicationId(), 0);
                activation = activation(request, planner.plan(request, preflight.occupiedTcpPorts()));
            }
            RemoteRestoreActivationPort.RecoveryEvidence recovered = remote.recoverRestoreActivation(activation);
            if (recovered.candidateRemoved() && recovered.previousGraphVerified()) {
                attempts.remove(request.candidateId());
            }
            return new RecoveryEvidence(recovered.previousGraphVerified(), recovered.evidence());
        } catch (LinuxOperationException | RuntimeException exception) {
            throw operation("remote restore recovery failed", exception);
        }
    }

    private RemoteRestoreActivationRequest requireAttempt(RestoreDeploymentRequest request) {
        RemoteRestoreActivationRequest activation = attempts.get(request.candidateId());
        if (activation == null || !activation.archiveSha256().equals(request.archiveSha256())) {
            throw new IllegalStateException("restore activation was not started by this transaction");
        }
        return activation;
    }

    private static RemoteRestoreActivationRequest activation(
            RestoreDeploymentRequest request, CandidatePortPlan plan) {
        List<RemoteRestoreActivationComponent> components = new ArrayList<>();
        for (RestoreDeploymentComponent component : request.components()) {
            var inputs = component.inputManifest().orElseThrow(() ->
                    new IllegalStateException("exact deployment inputs were not staged before restore activation"));
            var ports = plan.components().get(component.componentId()).stream().map(binding ->
                    new RemoteRestorePortBinding(binding.officialPort(), binding.candidatePort())).toList();
            components.add(new RemoteRestoreActivationComponent(component.componentId(),
                    component.managedApplicationId(), component.ownershipManifestSha256(), component.releaseSha256(),
                    component.releaseManifestPath(), component.persistentArchivePaths(), component.ociArchivePath(),
                    component.dependsOn(), component.runtime(), inputs, ports));
        }
        return new RemoteRestoreActivationRequest(request.applicationId(), request.candidateId(),
                request.candidateToken(), request.archiveSha256(), request.remoteCandidateRoot(),
                plan.mode() == CandidatePortMode.PARALLEL_LOOPBACK
                        ? RemoteRestoreActivationMode.PARALLEL_LOOPBACK : RemoteRestoreActivationMode.SHORT_STOP,
                components, request.applicationHealthComponentId(), request.applicationHealthCheck());
    }

    private static void requireDatabaseToken(Optional<String> token) {
        Objects.requireNonNull(token, "databaseToken").ifPresent(value -> {
            if (!value.matches("[a-z0-9][a-z0-9-]{0,62}")) {
                throw new IllegalArgumentException("databaseToken is invalid");
            }
        });
    }

    private static IllegalStateException operation(String diagnostic, Exception exception) {
        return new IllegalStateException(diagnostic, exception);
    }
}
