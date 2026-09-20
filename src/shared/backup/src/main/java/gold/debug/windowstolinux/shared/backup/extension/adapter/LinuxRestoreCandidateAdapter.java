package gold.debug.windowstolinux.shared.backup.extension.adapter;

import gold.debug.windowstolinux.shared.backup.contract.spi.RestoreCandidatePort;
import gold.debug.windowstolinux.shared.backup.contract.spi.RestoreCandidateRequest;
import gold.debug.windowstolinux.shared.backup.contract.validation.BackupException;
import gold.debug.windowstolinux.shared.backup.contract.validation.BackupFailureType;
import gold.debug.windowstolinux.shared.backup.manifest.BackupComponent;
import gold.debug.windowstolinux.shared.deploy.contract.spi.RestoreDeploymentComponent;
import gold.debug.windowstolinux.shared.deploy.contract.spi.RestoreDeploymentPort;
import gold.debug.windowstolinux.shared.deploy.contract.spi.RestoreDeploymentRequest;
import gold.debug.windowstolinux.shared.config.revision.DeploymentInputManifest;
import gold.debug.windowstolinux.shared.linux.error.LinuxOperationException;
import gold.debug.windowstolinux.shared.linux.protocol.RemoteStepResult;
import gold.debug.windowstolinux.shared.linux.protocol.restore.RemoteRestoreFilePort;
import gold.debug.windowstolinux.shared.linux.protocol.restore.RemoteRestoreMember;
import gold.debug.windowstolinux.shared.linux.protocol.restore.RemoteRestoreStagingEvidence;
import gold.debug.windowstolinux.shared.linux.protocol.restore.RemoteRestoreStagingRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Map;

/** Maps portable restore evidence onto deploy-owned activation and Linux-owned file staging. / 将可移植恢复证据映射到 deploy 持有的激活及 Linux 持有的文件暂存。 */
public final class LinuxRestoreCandidateAdapter implements RestoreCandidatePort {
    private final RemoteRestoreFilePort files;
    private final RestoreDeploymentPort deployments;
    private final Map<String, DeploymentInputManifest> activationInputs;

    /** Creates the one-way backup-to-deploy-to-Linux adapter. / 创建 backup 到 deploy 再到 Linux 的单向适配器。 */
    public LinuxRestoreCandidateAdapter(RemoteRestoreFilePort files, RestoreDeploymentPort deployments) {
        this(files, deployments, Map.of());
    }

    /** Creates an adapter with exact short-lived inputs already staged on the target. / 使用已暂存到目标端的精确短生命周期输入创建适配器。 */
    public LinuxRestoreCandidateAdapter(
            RemoteRestoreFilePort files,
            RestoreDeploymentPort deployments,
            Map<String, DeploymentInputManifest> activationInputs
    ) {
        this.files = Objects.requireNonNull(files, "files");
        this.deployments = Objects.requireNonNull(deployments, "deployments");
        this.activationInputs = Map.copyOf(Objects.requireNonNull(activationInputs, "activationInputs"));
    }

    /** Stages every validated archive member without addressing the active release. / 暂存每个已验证归档成员且不寻址活跃发布。 */
    @Override
    public FileEvidence stageFiles(RestoreCandidateRequest request) throws BackupException {
        requireAutomaticActivation(request);
        try {
            RemoteRestoreStagingEvidence staged = files.stageRestoreFiles(stagingRequest(request));
            return new FileEvidence(staged.candidateId(), staged.candidateToken(), staged.stagedBytes(),
                    staged.isolated(), staged.integrityVerified(), staged.existingReleaseUntouched(), staged.evidence());
        } catch (LinuxOperationException | RuntimeException exception) {
            throw BackupException.create(BackupFailureType.RESTORE_FILE_FAILED,
                    "Linux restore candidate staging failed", exception);
        }
    }

    /** Delegates component activation and health only from the closed typed component schema. / 仅从封闭类型化组件 schema 委派组件激活及健康检查。 */
    @Override
    public HealthEvidence verifyComponents(
            RestoreCandidateRequest request, FileEvidence staged, Optional<String> databaseToken)
            throws BackupException {
        requireAutomaticActivation(request);
        try {
            RestoreDeploymentPort.HealthEvidence health = deployments.verifyComponents(
                    deploymentRequest(request, staged.candidateToken()), checked(databaseToken));
            return new HealthEvidence(health.healthy(), health.evidence());
        } catch (RuntimeException exception) {
            throw BackupException.create(BackupFailureType.RESTORE_HEALTH_FAILED,
                    "typed restored component activation or health failed", exception);
        }
    }

    /** Delegates the declared whole-application health gate. / 委派已声明的整应用健康门。 */
    @Override
    public HealthEvidence verifyApplication(
            RestoreCandidateRequest request, FileEvidence staged, Optional<String> databaseToken)
            throws BackupException {
        requireAutomaticActivation(request);
        try {
            RestoreDeploymentPort.HealthEvidence health = deployments.verifyApplication(
                    deploymentRequest(request, staged.candidateToken()), checked(databaseToken));
            return new HealthEvidence(health.healthy(), health.evidence());
        } catch (RuntimeException exception) {
            throw BackupException.create(BackupFailureType.RESTORE_HEALTH_FAILED,
                    "typed whole-application restore health failed", exception);
        }
    }

    @Override
    public HealthEvidence prepareCommit(
            RestoreCandidateRequest request, FileEvidence staged, Optional<String> databaseToken)
            throws BackupException {
        requireAutomaticActivation(request);
        try {
            RestoreDeploymentPort.HealthEvidence prepared = deployments.prepareCommit(
                    deploymentRequest(request, staged.candidateToken()), checked(databaseToken));
            return new HealthEvidence(prepared.healthy(), prepared.evidence());
        } catch (RuntimeException exception) {
            throw BackupException.create(BackupFailureType.RESTORE_COMMIT_FAILED,
                    "typed restore stopped-write boundary failed", exception);
        }
    }

    /** Commits only through deploy and retains its rollback token. / 仅通过 deploy 提交并保留其回滚令牌。 */
    @Override
    public CommitEvidence commit(
            RestoreCandidateRequest request, FileEvidence staged, Optional<String> databaseToken)
            throws BackupException {
        requireAutomaticActivation(request);
        try {
            RestoreDeploymentPort.CommitEvidence committed = deployments.commit(
                    deploymentRequest(request, staged.candidateToken()), checked(databaseToken));
            return new CommitEvidence(committed.committed(), committed.previousReleaseRetained(),
                    committed.activeReleaseToken(), committed.evidence());
        } catch (RuntimeException exception) {
            throw BackupException.create(BackupFailureType.RESTORE_COMMIT_FAILED,
                    "typed restore commit failed", exception);
        }
    }

    @Override
    public HealthEvidence quiesceForRecovery(
            RestoreCandidateRequest request, Optional<FileEvidence> staged) throws BackupException {
        requireAutomaticActivation(request);
        String token = staged.map(FileEvidence::candidateToken)
                .orElseGet(() -> request.archiveSha256().substring(0, 32));
        try {
            RestoreDeploymentPort.HealthEvidence stopped = deployments.quiesceForRecovery(
                    deploymentRequest(request, token));
            return new HealthEvidence(stopped.healthy(), stopped.evidence());
        } catch (RuntimeException exception) {
            throw BackupException.create(BackupFailureType.RESTORE_RECOVERY_FAILED,
                    "typed restore recovery quiesce failed", exception);
        }
    }

    /** Attempts deploy rollback and candidate cleanup independently. / 独立尝试 deploy 回滚和候选清理。 */
    @Override
    public RecoveryEvidence recoverExisting(
            RestoreCandidateRequest request, Optional<FileEvidence> staged) throws BackupException {
        requireAutomaticActivation(request);
        List<String> evidence = new ArrayList<>();
        boolean existingVerified = false;
        boolean candidateRemoved = false;
        List<Throwable> failures = new ArrayList<>();
        String token = staged.map(FileEvidence::candidateToken)
                .orElseGet(() -> request.archiveSha256().substring(0, 32));
        try {
            RestoreDeploymentPort.HealthEvidence stopped = deployments.quiesceForRecovery(
                    deploymentRequest(request, token));
            if (!stopped.healthy()) throw new IllegalStateException("restore recovery quiesce was not verified");
            evidence.addAll(stopped.evidence());
        } catch (RuntimeException exception) {
            failures.add(exception);
        }
        try {
            RestoreDeploymentPort.RecoveryEvidence recovered = deployments.recoverExisting(
                    deploymentRequest(request, token));
            existingVerified = recovered.existingReleaseVerified();
            evidence.addAll(recovered.evidence());
        } catch (RuntimeException exception) {
            failures.add(exception);
        }
        try {
            RemoteStepResult discarded = files.discardRestoreFiles(stagingRequest(request));
            candidateRemoved = discarded.succeeded();
            evidence.add(discarded.evidence());
        } catch (LinuxOperationException | RuntimeException exception) {
            failures.add(exception);
        }
        if (!failures.isEmpty()) {
            BackupException failure = BackupException.create(BackupFailureType.RESTORE_RECOVERY_FAILED,
                    "deploy rollback or Linux restore candidate cleanup failed");
            failures.forEach(failure::addSuppressed);
            throw failure;
        }
        return new RecoveryEvidence(candidateRemoved, existingVerified, evidence);
    }

    private static RemoteRestoreStagingRequest stagingRequest(RestoreCandidateRequest request) {
        List<RemoteRestoreMember> members = request.manifest().members().stream()
                .map(member -> new RemoteRestoreMember(member.path(), member.size(), member.sha256())).toList();
        return new RemoteRestoreStagingRequest(request.manifest().applicationId(), request.archiveSha256(),
                request.localCandidateRoot(), request.verifiedBytes(), members);
    }

    private RestoreDeploymentRequest deploymentRequest(RestoreCandidateRequest request, String candidateToken) {
        List<RestoreDeploymentComponent> components = request.manifest().inventory().components().stream()
                .map(component -> component(component, request)).toList();
        String remoteRoot = "/var/lib/windowstolinux/work/" + request.candidateId() + "/mutable/restore";
        return new RestoreDeploymentRequest(request.manifest().applicationId(), request.targetServerId(),
                request.candidateId(), request.archiveSha256(),
                request.manifest().inventory().identity().releaseSetSha256().orElseThrow(),
                request.manifest().inventory().secretReferences(), remoteRoot, candidateToken, components,
                request.manifest().inventory().applicationHealthComponentId(),
                request.manifest().inventory().applicationHealthCheck().toHealthCheck(),
                request.manifest().inventory().database().type() == gold.debug.windowstolinux.shared.backup.manifest.BackupDatabaseType.SQLITE);
    }

    private RestoreDeploymentComponent component(BackupComponent component, RestoreCandidateRequest request) {
        String prefix = "data/" + component.componentId() + "/";
        List<String> persistent = request.manifest().members().stream()
                .map(member -> member.path()).filter(path -> path.startsWith(prefix)).sorted().toList();
        Optional<String> oci = request.manifest().members().stream().map(member -> member.path())
                .filter(path -> path.equals("runtime/" + component.componentId() + ".oci")).findFirst();
        return new RestoreDeploymentComponent(component.componentId(), component.managedApplicationId(),
                component.ownershipManifestSha256(), component.releaseSha256().orElseThrow(),
                component.secretReferences().orElseThrow(), component.releaseManifestPath(),
                component.configurationSnapshotPath(), component.serviceDefinitionPath(), component.dependsOn(),
                component.runtime().toSpecification(), Optional.ofNullable(activationInputs.get(component.componentId())),
                persistent, oci);
    }

    private static void requireAutomaticActivation(RestoreCandidateRequest request) throws BackupException {
        if (!Objects.requireNonNull(request, "request").manifest().supportsAutomaticActivation()) {
            throw BackupException.create(BackupFailureType.RESTORE_PREFLIGHT_FAILED,
                    "restore manifest lacks schema-v5 activation bindings or required encrypted secrets");
        }
    }

    private static Optional<String> checked(Optional<String> token) {
        token = Objects.requireNonNull(token, "databaseToken");
        token.ifPresent(value -> {
            if (!value.matches("[A-Za-z0-9][A-Za-z0-9._:@/-]{0,255}")) {
                throw new IllegalArgumentException("databaseToken is invalid");
            }
        });
        return token;
    }
}
