package gold.debug.windowstolinux.shared.backup.execution.migration;

import gold.debug.windowstolinux.shared.backup.contract.spi.OfflineMigrationPort;
import gold.debug.windowstolinux.shared.backup.contract.spi.OfflineMigrationRequest;
import gold.debug.windowstolinux.shared.backup.contract.validation.BackupException;
import gold.debug.windowstolinux.shared.backup.contract.validation.BackupFailureType;
import gold.debug.windowstolinux.shared.model.failure.FailureDescriptor;
import gold.debug.windowstolinux.shared.model.failure.FailureRecoveryAction;
import gold.debug.windowstolinux.shared.model.failure.FailureRecoveryDisposition;
import gold.debug.windowstolinux.shared.model.failure.OperationIdentity;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Prepares an offline migration through final verification and stops before manual traffic switching. / 将离线迁移准备到最终验证并在人工切流前停止。
 */
public final class OfflineMigrationCoordinator {
    /**
     * TARGET SPACE MULTIPLIER.
     * <p>目标SPACEMULTIPLIER。
     */
    private static final long TARGET_SPACE_MULTIPLIER = 2;
    /**
     * Network port number in the reviewed endpoint.
     * <p>已审阅端点中的网络端口号。
     */
    private final OfflineMigrationPort port;

    /**
     * Creates a coordinator over one bounded platform port. / 基于一个受限平台端口创建协调器。
     *
     * @param port network port number in the reviewed endpoint / 已审阅端点中的网络端口号
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public OfflineMigrationCoordinator(OfflineMigrationPort port) {
        this.port = Objects.requireNonNull(port, "port");
    }

    /**
     * Executes deterministic preflight, synchronization, quiesce and target verification. / 执行确定性的预检、同步、停写及目标验证。
     *
     * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
     * @return constructed or resolved offline migration result / 构造或解析得到的离线迁移结果
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public OfflineMigrationResult prepare(OfflineMigrationRequest request) {
        Objects.requireNonNull(request, "request");
        OperationIdentity operation = OperationIdentity.create();
        List<OfflineMigrationEvent> events = new ArrayList<>();
        OfflineMigrationState state = OfflineMigrationState.TARGET_PREFLIGHT_VERIFIED;
        Optional<OfflineMigrationPort.SourceQuiesceEvidence> quiesced = Optional.empty();
        boolean targetMutationAttempted = false;
        boolean sourceMutationAttempted = false;
        try {
            if (!request.stopWindowApproved()) {
                throw BackupException.create(BackupFailureType.MIGRATION_PREFLIGHT_FAILED,
                        "offline migration requires an explicitly approved stopped-write window");
            }
            OfflineMigrationPort.TargetPreflightEvidence target = port.preflightTarget(request);
            long required = Math.multiplyExact(request.estimatedBytes(), TARGET_SPACE_MULTIPLIER);
            if (!target.ownershipVerified() || !target.portsAvailable() || !target.restoreCompatible()
                    || target.availableBytes() < required) {
                throw BackupException.create(BackupFailureType.MIGRATION_PREFLIGHT_FAILED,
                        "target ownership, ports, restore compatibility or two-copy space is incomplete");
            }
            events.add(success(state, target.evidence()));

            state = OfflineMigrationState.INITIAL_SYNC_VERIFIED;
            targetMutationAttempted = true;
            OfflineMigrationPort.SyncEvidence initial = port.initialSync(request);
            requireSync(initial, request, false, "initial migration synchronization is unverified");
            events.add(success(state, initial.evidence()));

            state = OfflineMigrationState.SOURCE_WRITES_STOPPED;
            sourceMutationAttempted = true;
            OfflineMigrationPort.SourceQuiesceEvidence stopped = port.stopSourceWrites(request);
            quiesced = Optional.of(stopped);
            if (!stopped.writesStopped() || !stopped.noActiveWriters()) {
                throw BackupException.create(BackupFailureType.MIGRATION_QUIESCE_FAILED,
                        "source writes were not stopped and verified");
            }
            events.add(success(state, stopped.evidence()));

            state = OfflineMigrationState.FINAL_SYNC_VERIFIED;
            OfflineMigrationPort.SyncEvidence finalSync = port.finalSync(request, initial, stopped);
            requireSync(finalSync, request, true, "final stopped-write synchronization is unverified");
            events.add(success(state, finalSync.evidence()));

            state = OfflineMigrationState.TARGET_CANDIDATE_VERIFIED;
            OfflineMigrationPort.TargetCandidateEvidence candidate = port.restoreAndVerifyTarget(request, finalSync);
            String expectedCandidate = request.applicationId() + "-" + finalSync.contentSha256().substring(0, 16);
            if (!candidate.candidateId().equals(expectedCandidate) || !candidate.componentsHealthy()
                    || !candidate.applicationHealthy() || !candidate.externalTrafficUnchanged()) {
                throw BackupException.create(BackupFailureType.MIGRATION_TARGET_FAILED,
                        "target candidate identity, health or unchanged external traffic evidence is incomplete");
            }
            events.add(success(state, candidate.evidence()));
            events.add(new OfflineMigrationEvent(OfflineMigrationState.MANUAL_TRAFFIC_SWITCH_REQUIRED, true,
                    "target is verified; external traffic must be switched manually and the source remains retained"));
            return new OfflineMigrationResult(operation, OfflineMigrationStatus.READY_FOR_MANUAL_TRAFFIC_SWITCH,
                    events, true, true, false, true, Optional.of(candidate.candidateId()),
                    Optional.of(stopped.recoveryToken()), Optional.empty());
        } catch (Exception exception) {
            FailureDescriptor original = failure(exception).withOperationIdentity(operation);
            if (events.isEmpty() || events.get(events.size() - 1).state() != state
                    || events.get(events.size() - 1).succeeded()) {
                events.add(new OfflineMigrationEvent(state, false, original.diagnostic()));
            }
            return recover(request, operation, events, quiesced, targetMutationAttempted,
                    sourceMutationAttempted, original);
        }
    }

    /**
     * Restores the source-side running state after migration failure and records whether recovery is verified or requires manual intervention.
     * <p>迁移失败后恢复源端运行状态，并记录恢复已验证还是需要人工干预。
     *
     * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
     * @param operation operation / 操作
     * @param events ordered progress or transaction events / 有序进度或事务事件
     * @param quiesced quiesced / 已停写
     * @param targetMutationAttempted target mutation attempted / 目标变更已尝试
     * @param sourceMutationAttempted source mutation attempted / 源码变更已尝试
     * @param original original / 原始
     * @return constructed or resolved offline migration result / 构造或解析得到的离线迁移结果
     */
    private OfflineMigrationResult recover(
            OfflineMigrationRequest request,
            OperationIdentity operation,
            List<OfflineMigrationEvent> events,
            Optional<OfflineMigrationPort.SourceQuiesceEvidence> quiesced,
            boolean targetMutationAttempted,
            boolean sourceMutationAttempted,
            FailureDescriptor original
    ) {
        List<Throwable> failures = new ArrayList<>();
        if (targetMutationAttempted) {
            try {
                OfflineMigrationPort.RecoveryEvidence target = port.discardTargetCandidate(request);
                if (!target.completed() || !target.verified()) {
                    failures.add(new IllegalStateException("target candidate cleanup is unverified"));
                    events.add(new OfflineMigrationEvent(
                            OfflineMigrationState.TARGET_CANDIDATE_RECOVERY_VERIFIED, false,
                            "target candidate cleanup could not be verified"));
                } else {
                    events.add(success(OfflineMigrationState.TARGET_CANDIDATE_RECOVERY_VERIFIED, target.evidence()));
                }
            } catch (Exception exception) {
                failures.add(exception);
                events.add(new OfflineMigrationEvent(
                        OfflineMigrationState.TARGET_CANDIDATE_RECOVERY_VERIFIED, false,
                        "target candidate cleanup failed before absence could be verified"));
            }
        }
        if (sourceMutationAttempted) {
            if (quiesced.isEmpty()) {
                failures.add(new IllegalStateException("source recovery token was not returned after stop began"));
                events.add(new OfflineMigrationEvent(OfflineMigrationState.SOURCE_RECOVERY_VERIFIED, false,
                        "source stop began without returning a verifiable recovery token"));
            } else {
                try {
                    OfflineMigrationPort.RecoveryEvidence source = port.recoverSource(request, quiesced.orElseThrow());
                    if (!source.completed() || !source.verified()) {
                        failures.add(new IllegalStateException("source recovery is unverified"));
                        events.add(new OfflineMigrationEvent(OfflineMigrationState.SOURCE_RECOVERY_VERIFIED, false,
                                "source recovery completed without verified runtime evidence"));
                    } else {
                        events.add(success(OfflineMigrationState.SOURCE_RECOVERY_VERIFIED, source.evidence()));
                    }
                } catch (Exception exception) {
                    failures.add(exception);
                    events.add(new OfflineMigrationEvent(OfflineMigrationState.SOURCE_RECOVERY_VERIFIED, false,
                            "source recovery failed before runtime state could be verified"));
                }
            }
        }
        if (failures.isEmpty()) {
            FailureRecoveryAction action = sourceMutationAttempted ? FailureRecoveryAction.ROLLBACK
                    : targetMutationAttempted ? FailureRecoveryAction.CLEANUP : FailureRecoveryAction.NONE;
            FailureRecoveryDisposition disposition = targetMutationAttempted || sourceMutationAttempted
                    ? FailureRecoveryDisposition.SUCCEEDED : FailureRecoveryDisposition.NOT_REQUIRED;
            FailureDescriptor safe = original.withRecovery(action, disposition);
            OfflineMigrationStatus status = sourceMutationAttempted
                    ? OfflineMigrationStatus.FAILED_SOURCE_RECOVERED
                    : targetMutationAttempted ? OfflineMigrationStatus.FAILED_TARGET_CLEANED
                    : OfflineMigrationStatus.PRECONDITION_REJECTED;
            return failed(operation, status, events, safe);
        }
        BackupException recovery = BackupException.create(BackupFailureType.MIGRATION_RECOVERY_FAILED,
                "target cleanup or source recovery could not be verified");
        failures.forEach(recovery::addSuppressed);
        FailureDescriptor failed = recovery.failure().withOperationIdentity(operation).withRecovery(
                FailureRecoveryAction.REQUIRE_MANUAL_RECOVERY, FailureRecoveryDisposition.FAILED);
        return failed(operation, OfflineMigrationStatus.MANUAL_RECOVERY_REQUIRED, events, failed);
    }

    /**
     * Builds the failure outcome while retaining available classified evidence.
     * <p>构建失败结果并保留可用的分类证据。
     *
     * @param operation operation / 操作
     * @param status classification of the current operation result / 当前操作结果的分类
     * @param events ordered progress or transaction events / 有序进度或事务事件
     * @param failure structured failure occurrence retained for safe reporting / 保留用于安全报告的结构化失败实例
     * @return the failure outcome while retaining available classified evidence / 失败结果并保留可用的分类证据
     */
    private static OfflineMigrationResult failed(
            OperationIdentity operation,
            OfflineMigrationStatus status,
            List<OfflineMigrationEvent> events,
            FailureDescriptor failure
    ) {
        return new OfflineMigrationResult(operation, status, events, false, false, false, true,
                Optional.empty(), Optional.empty(), Optional.of(failure));
    }

    /**
     * Requires sync.
     * <p>要求同步。
     *
     * @param sync sync / 同步
     * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
     * @param writesStopped writes stopped / 写入集合已停止
     * @param diagnostic bounded non-secret detail for diagnostic reporting / 用于诊断报告的有界非秘密详情
     * @throws BackupException if backup validation or the controlled backup operation fails / 备份校验或受控备份操作失败时
     */
    private static void requireSync(
            OfflineMigrationPort.SyncEvidence sync,
            OfflineMigrationRequest request,
            boolean writesStopped,
            String diagnostic
    ) throws BackupException {
        if (!sync.digestVerified() || sync.sourceWritesStopped() != writesStopped) {
            throw BackupException.create(BackupFailureType.MIGRATION_SYNC_FAILED, diagnostic);
        }
    }

    /**
     * Creates or preserves the module-owned failure for the supplied cause and diagnostic evidence.
     * <p>为所提供原因及诊断证据创建或保留模块自有失败。
     *
     * @param exception original exception being classified or translated / 正在分类或转换的原始异常
     * @return or preserves the module-owned failure for the supplied cause and diagnostic evidence / 为所提供原因及诊断证据创建或保留模块自有失败
     */
    private static FailureDescriptor failure(Exception exception) {
        if (exception instanceof BackupException backup) return backup.failure();
        BackupFailureType type = exception instanceof ArithmeticException
                ? BackupFailureType.MIGRATION_PREFLIGHT_FAILED : BackupFailureType.MIGRATION_SYNC_FAILED;
        return BackupException.create(type, "unexpected offline migration failure", exception).failure();
    }

    /**
     * Builds a successful outcome from the supplied completion evidence.
     * <p>根据所提供的完成证据构建成功结果。
     *
     * @param state current lifecycle or workflow state / 当前生命周期或工作流状态
     * @param evidence observations supporting the reported result / 支持所报告结果的观测证据
     * @return a successful outcome from the supplied completion evidence / 根据所提供的完成证据构建成功结果
     */
    private static OfflineMigrationEvent success(OfflineMigrationState state, List<String> evidence) {
        String joined = String.join("; ", evidence);
        if (joined.length() > 1024) joined = joined.substring(0, 1024);
        return new OfflineMigrationEvent(state, true, joined);
    }
}
