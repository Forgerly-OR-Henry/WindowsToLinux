package gold.debug.windowstolinux.shared.deploy.execution.transaction;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

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

/**
 * Production deploy implementation for one fail-closed remote restore transaction. / 单个故障关闭远程恢复事务的生产 deploy 实现。
 */
public final class ManagedRestoreDeploymentPort implements RestoreDeploymentPort {
    /**
     * The credential-free remote.
     * <p>不含凭据的远端。
     */
    private final RemoteRestoreActivationPort remote;

    /**
     * Bound restore candidate port planner collaborator for planner.
     * <p>处理规划器的恢复候选端口规划器协作对象。
     */
    private final RestoreCandidatePortPlanner planner;

    /**
     * Attempts.
     * <p>尝试集合。
     */
    private final Map<String, RemoteRestoreActivationRequest> attempts = new LinkedHashMap<>();

    /**
     * Commit prepared.
     * <p>提交已准备。
     */
    private final Set<String> commitPrepared = new HashSet<>();

    /**
     * Formal started.
     * <p>正式已启动。
     */
    private final Set<String> formalStarted = new HashSet<>();

    /**
     * Creates a deploy-owned transaction over the fixed Linux activation capability. / 基于固定 Linux 激活能力创建 deploy 持有的事务。
     *
     * @param remote the credential-free remote / 不含凭据的远端
     */
    public ManagedRestoreDeploymentPort(RemoteRestoreActivationPort remote) {
        this(remote, new RestoreCandidatePortPlanner());
    }

    /**
     * Validates and binds the inputs required by managed restore deployment port.
     * <p>校验并绑定受管恢复部署端口所需输入。
     *
     * @param remote the credential-free remote / 不含凭据的远端
     * @param planner planner / 规划器
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    ManagedRestoreDeploymentPort(RemoteRestoreActivationPort remote, RestoreCandidatePortPlanner planner) {
        this.remote = Objects.requireNonNull(remote, "remote");
        this.planner = Objects.requireNonNull(planner, "planner");
    }

    /**
     * Starts once and verifies every candidate component. / 仅启动一次并验证每个候选组件。
     *
     * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
     * @param databaseToken database token / 数据库令牌
     * @return constructed or resolved health evidence / 构造或解析得到的健康证据
     */
    @Override
    public synchronized HealthEvidence verifyComponents(RestoreDeploymentRequest request,
            Optional<String> databaseToken) {
        requireDatabaseToken(databaseToken);
        try {
            RemoteRestoreActivationRequest activation = attempt(request, databaseToken.isPresent());
            if (activation.mode() == RemoteRestoreActivationMode.SHORT_STOP) {
                if (!commitPrepared.contains(request.candidateId())) {
                    if (databaseToken.isPresent()) {
                        return new HealthEvidence(false,
                                List.of("database activation requires prepareCommit before formal process health"));
                    }
                    prepareRemote(activation);
                }
                if (formalStarted.add(request.candidateId())) {
                    RemoteRestoreActivationPort.StepEvidence started = remote.startRestoreFormal(activation);
                    if (!started.completed())
                        return new HealthEvidence(false, started.evidence());
                }
            }
            RemoteRestoreActivationPort.StepEvidence verified = remote.verifyRestoreComponents(activation);
            return new HealthEvidence(verified.completed(), verified.evidence());
        } catch (LinuxOperationException | RuntimeException exception) {
            throw operation("remote restore component activation failed", exception);
        }
    }

    /**
     * Prepares the resolved full commit.
     * <p>准备已解析的完整 Commit。
     *
     * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
     * @param databaseToken database token / 数据库令牌
     * @return constructed or resolved health evidence / 构造或解析得到的健康证据
     */
    @Override
    public synchronized HealthEvidence prepareCommit(RestoreDeploymentRequest request, Optional<String> databaseToken) {
        requireDatabaseToken(databaseToken);
        try {
            RemoteRestoreActivationRequest activation = attempt(request, databaseToken.isPresent());
            if (!commitPrepared.contains(request.candidateId()))
                prepareRemote(activation);
            return new HealthEvidence(true, List.of("candidate and previous graph stopped before database activation",
                    "application-wide stopped-write boundary verified by managed helper"));
        } catch (LinuxOperationException | RuntimeException exception) {
            throw operation("remote restore commit preparation failed", exception);
        }
    }

    /**
     * Verifies the candidate application gate without accepting a different request. / 验证候选整应用门且不接受不同请求。
     *
     * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
     * @param databaseToken database token / 数据库令牌
     * @return constructed or resolved health evidence / 构造或解析得到的健康证据
     */
    @Override
    public synchronized HealthEvidence verifyApplication(RestoreDeploymentRequest request,
            Optional<String> databaseToken) {
        requireDatabaseToken(databaseToken);
        try {
            RemoteRestoreActivationRequest activation = requireAttempt(request);
            RemoteRestoreActivationPort.StepEvidence verified = remote.verifyRestoreApplication(activation);
            return new HealthEvidence(verified.completed(), verified.evidence());
        } catch (LinuxOperationException | RuntimeException exception) {
            throw operation("remote restore application health failed", exception);
        }
    }

    /**
     * Commits formal identities and requires the final formal health pass. / 提交正式身份并要求最终正式健康通过。
     *
     * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
     * @param databaseToken database token / 数据库令牌
     * @return constructed or resolved commit evidence / 构造或解析得到的提交证据
     */
    @Override
    public synchronized CommitEvidence commit(RestoreDeploymentRequest request, Optional<String> databaseToken) {
        requireDatabaseToken(databaseToken);
        try {
            RemoteRestoreActivationRequest activation = requireAttempt(request);
            if (!commitPrepared.contains(request.candidateId()))
                prepareRemote(activation);
            if (formalStarted.add(request.candidateId())) {
                RemoteRestoreActivationPort.StepEvidence started = remote.startRestoreFormal(activation);
                if (!started.completed())
                    return new CommitEvidence(false, true, request.candidateToken(), started.evidence());
            }
            RemoteRestoreActivationPort.CommitEvidence committed = remote.commitRestoreActivation(activation);
            if (!committed.formalComponentsHealthy() || !committed.formalApplicationHealthy()) {
                return new CommitEvidence(false, committed.previousReleaseRetained(), committed.activeReleaseToken(),
                        committed.evidence());
            }
            if (committed.committed())
                clear(request.candidateId());
            return new CommitEvidence(committed.committed(), committed.previousReleaseRetained(),
                    committed.activeReleaseToken(), committed.evidence());
        } catch (LinuxOperationException | RuntimeException exception) {
            throw operation("remote restore formal commit failed", exception);
        }
    }

    /**
     * Stops target writes before attempting to restore the previous state.
     * <p>在尝试恢复此前状态前停止目标写入。
     *
     * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
     * @return constructed or resolved health evidence / 构造或解析得到的健康证据
     */
    @Override
    public synchronized HealthEvidence quiesceForRecovery(RestoreDeploymentRequest request) {
        try {
            RemoteRestoreActivationRequest activation = attempts.get(request.candidateId());
            if (activation == null) {
                var preflight = remote.inspectRestoreActivation(request.applicationId(), 0);
                activation = activation(request,
                        planner.plan(request, preflight.occupiedTcpPorts(), preflight.occupiedUdpPorts(), true));
            }
            RemoteRestoreActivationPort.StepEvidence stopped = remote.quiesceRestoreRecovery(activation);
            return new HealthEvidence(stopped.completed(), stopped.evidence());
        } catch (LinuxOperationException | RuntimeException exception) {
            throw operation("remote restore recovery quiesce failed", exception);
        }
    }

    /**
     * Recovers idempotently even when activation failed before local state was retained. / 即使激活在保存本地状态前失败也执行幂等恢复。
     *
     * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
     * @return constructed or resolved recovery evidence / 构造或解析得到的恢复证据
     */
    @Override
    public synchronized RecoveryEvidence recoverExisting(RestoreDeploymentRequest request) {
        try {
            RemoteRestoreActivationRequest activation = attempts.get(request.candidateId());
            if (activation == null) {
                var preflight = remote.inspectRestoreActivation(request.applicationId(), 0);
                activation = activation(request,
                        planner.plan(request, preflight.occupiedTcpPorts(), preflight.occupiedUdpPorts(), false));
            }
            RemoteRestoreActivationPort.RecoveryEvidence recovered = remote.recoverRestoreActivation(activation);
            if (recovered.candidateRemoved() && recovered.previousGraphVerified()) {
                clear(request.candidateId());
            }
            return new RecoveryEvidence(recovered.previousGraphVerified(), recovered.evidence());
        } catch (LinuxOperationException | RuntimeException exception) {
            throw operation("remote restore recovery failed", exception);
        }
    }

    /**
     * Reuses an existing candidate activation attempt or prepares a new one after target preflight.
     * <p>复用既有候选激活尝试，或在目标预检后准备新尝试。
     *
     * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
     * @param databaseActivationRequired database activation required / 数据库激活必需
     * @return constructed or resolved remote restore activation request / 构造或解析得到的远端恢复激活请求
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     * @throws IllegalStateException if the required state or runtime facility is unavailable / 所需状态或运行设施不可用时
     */
    private RemoteRestoreActivationRequest attempt(RestoreDeploymentRequest request, boolean databaseActivationRequired)
            throws LinuxOperationException {
        RemoteRestoreActivationRequest activation = attempts.get(request.candidateId());
        if (activation != null)
            return requireAttempt(request);
        var preflight = remote.inspectRestoreActivation(request.applicationId(), 0);
        if (!preflight.managedRootWritable() || preflight.foreignApplicationConflict()) {
            throw new IllegalStateException(
                    "restore target preflight rejected activation: " + String.join("; ", preflight.evidence()));
        }
        CandidatePortPlan ports = planner.plan(request, preflight.occupiedTcpPorts(), preflight.occupiedUdpPorts(),
                databaseActivationRequired);
        activation = activation(request, ports);
        RemoteRestoreActivationPort.StepEvidence started = remote.startRestoreActivation(activation);
        if (!started.completed())
            throw new IllegalStateException("restore activation preparation was incomplete");
        attempts.put(request.candidateId(), activation);
        return activation;
    }

    /**
     * Prepares the credential-free remote.
     * <p>准备不含凭据的远端。
     *
     * @param activation activation / 激活
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     * @throws IllegalStateException if the required state or runtime facility is unavailable / 所需状态或运行设施不可用时
     */
    private void prepareRemote(RemoteRestoreActivationRequest activation) throws LinuxOperationException {
        RemoteRestoreActivationPort.StepEvidence prepared = remote.prepareRestoreCommit(activation);
        if (!prepared.completed())
            throw new IllegalStateException("restore stopped-write boundary was not verified");
        commitPrepared.add(activation.candidateId());
    }

    /**
     * Clears managed restore deployment.
     * <p>清空受管恢复部署。
     *
     * @param candidateId identity of the isolated deployment or restore candidate / 隔离部署或恢复候选的身份
     */
    private void clear(String candidateId) {
        attempts.remove(candidateId);
        commitPrepared.remove(candidateId);
        formalStarted.remove(candidateId);
    }

    /**
     * Validates and returns attempt and rejects inputs outside the declared constraints.
     * <p>校验并返回尝试并拒绝超出已声明约束的输入。
     *
     * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
     * @return constructed or resolved remote restore activation request / 构造或解析得到的远端恢复激活请求
     * @throws IllegalStateException if the required state or runtime facility is unavailable / 所需状态或运行设施不可用时
     */
    private RemoteRestoreActivationRequest requireAttempt(RestoreDeploymentRequest request) {
        RemoteRestoreActivationRequest activation = attempts.get(request.candidateId());
        if (activation == null || !activation.archiveSha256().equals(request.archiveSha256())) {
            throw new IllegalStateException("restore activation was not started by this transaction");
        }
        return activation;
    }

    /**
     * Builds remote restore activation request from the supplied activation inputs.
     * <p>根据所提供激活输入构建远端恢复激活请求。
     *
     * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
     * @param plan deterministic reviewed execution order and targets / 确定的已审阅执行顺序及目标
     * @return remote restore activation request from the supplied activation inputs / 根据所提供激活输入构建远端恢复激活请求
     */
    private static RemoteRestoreActivationRequest activation(RestoreDeploymentRequest request, CandidatePortPlan plan) {
        List<RemoteRestoreActivationComponent> components = new ArrayList<>();
        for (RestoreDeploymentComponent component : request.components()) {
            var inputs = component.inputManifest().orElseThrow(() -> new IllegalStateException(
                    "exact deployment inputs were not staged before restore activation"));
            var ports = plan.components().get(component.componentId()).stream()
                    .map(binding -> new RemoteRestorePortBinding(binding.officialPort(), binding.candidatePort(),
                            binding.protocol()))
                    .toList();
            components.add(new RemoteRestoreActivationComponent(component.componentId(),
                    component.managedApplicationId(), component.ownershipManifestSha256(), component.releaseSha256(),
                    component.releaseManifestPath(), component.persistentArchivePaths(), component.ociArchivePath(),
                    component.dependsOn(), component.runtime(), DeploymentInputMapper.manifest(inputs), ports));
        }
        return new RemoteRestoreActivationRequest(request.applicationId(), request.candidateId(),
                request.candidateToken(), request.archiveSha256(), request.remoteCandidateRoot(),
                RemoteRestoreActivationMode.valueOf(plan.mode().name()), components,
                request.applicationHealthComponentId(), request.applicationHealthCheck());
    }

    /**
     * Requires database token and rejects inputs outside the declared constraints.
     * <p>要求数据库令牌并拒绝超出已声明约束的输入。
     *
     * @param token token / 令牌
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    private static void requireDatabaseToken(Optional<String> token) {
        Objects.requireNonNull(token, "databaseToken").ifPresent(value -> {
            if (!value.matches("[a-z0-9][a-z0-9-]{0,62}")) {
                throw new IllegalArgumentException("databaseToken is invalid");
            }
        });
    }

    /**
     * Builds the structured failure descriptor for operation.
     * <p>为操作构建结构化失败描述。
     *
     * @param diagnostic bounded non-secret detail for diagnostic reporting / 用于诊断报告的有界非秘密详情
     * @param exception original exception being classified or translated / 正在分类或转换的原始异常
     * @return the structured failure descriptor for operation / 为操作构建结构化失败描述
     */
    private static IllegalStateException operation(String diagnostic, Exception exception) {
        return new IllegalStateException(diagnostic, exception);
    }
}
