package gold.debug.windowstolinux.shared.backup.restore;

import gold.debug.windowstolinux.shared.backup.contract.spi.DatabaseBackupAdapter;
import gold.debug.windowstolinux.shared.backup.contract.spi.DatabaseRestoreEvidence;
import gold.debug.windowstolinux.shared.backup.contract.spi.RestoreCandidatePort;
import gold.debug.windowstolinux.shared.backup.contract.spi.RestoreCandidateRequest;
import gold.debug.windowstolinux.shared.backup.contract.validation.BackupException;
import gold.debug.windowstolinux.shared.backup.contract.validation.BackupFailureType;
import gold.debug.windowstolinux.shared.backup.extension.registry.DatabaseAdapterRegistry;
import gold.debug.windowstolinux.shared.backup.manifest.BackupDatabaseType;
import gold.debug.windowstolinux.shared.model.failure.FailureDescriptor;
import gold.debug.windowstolinux.shared.model.failure.FailureRecoveryAction;
import gold.debug.windowstolinux.shared.model.failure.FailureRecoveryDisposition;
import gold.debug.windowstolinux.shared.model.failure.OperationIdentity;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Coordinates preflight, isolated restore, health, commit and verified recovery. / 编排前置检查、隔离恢复、健康、提交和已验证恢复。
 */
public final class BackupRestoreCoordinator {
    /**
     * Preflight.
     * <p>预检。
     */
    private final BackupRestorePreflight preflight;
    /**
     * Candidates.
     * <p>候选集合。
     */
    private final RestoreCandidatePort candidates;
    /**
     * Databases.
     * <p>数据库集合。
     */
    private final DatabaseAdapterRegistry databases;

    /**
     * Creates a restore coordinator over platform and database seams. / 基于平台及数据库接缝创建恢复协调器。
     *
     * @param preflight preflight / 预检
     * @param candidates candidates / 候选集合
     * @param databases databases / 数据库集合
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public BackupRestoreCoordinator(
            BackupRestorePreflight preflight,
            RestoreCandidatePort candidates,
            DatabaseAdapterRegistry databases
    ) {
        this.preflight = Objects.requireNonNull(preflight, "preflight");
        this.candidates = Objects.requireNonNull(candidates, "candidates");
        this.databases = Objects.requireNonNull(databases, "databases");
    }

    /**
     * Executes one fail-closed candidate restore without overwriting the current release. / 执行一次不覆盖当前发布的故障关闭候选恢复。
     *
     * @param plan deterministic reviewed execution order and targets / 确定的已审阅执行顺序及目标
     * @return constructed or resolved backup restore result / 构造或解析得到的备份恢复结果
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public BackupRestoreResult restore(BackupRestorePlan plan) {
        Objects.requireNonNull(plan, "plan");
        OperationIdentity operation = OperationIdentity.create();
        List<RestoreCandidateEvent> events = new ArrayList<>();
        Optional<RestoreCandidatePort.FileEvidence> files = Optional.empty();
        Optional<DatabaseRestoreEvidence> database = Optional.empty();
        RestoreCandidateRequest candidateRequest = plan.candidateRequest();
        RestoreCandidateState current = RestoreCandidateState.PREFLIGHT_VERIFIED;
        boolean mutationAttempted = false;
        boolean databaseMutationAttempted = false;
        boolean databaseCommitAttempted = false;
        try {
            events.add(success(current, preflight.verify(plan)));
            current = RestoreCandidateState.FILES_STAGED;
            mutationAttempted = true;
            RestoreCandidatePort.FileEvidence staged = candidates.stageFiles(candidateRequest);
            requireFiles(plan, staged);
            files = Optional.of(staged);
            events.add(success(current, staged.evidence()));

            if (plan.databaseRestore().isPresent()) {
                current = RestoreCandidateState.DATABASE_RESTORED;
                DatabaseBackupAdapter adapter = databases.require(
                        plan.validation().manifest().inventory().database().type());
                databaseMutationAttempted = true;
                DatabaseRestoreEvidence restored = adapter.restore(plan.databaseRestore().orElseThrow());
                database = Optional.of(restored);
                events.add(success(current, restored.evidence()));

                if (plan.validation().manifest().inventory().database().type() != gold.debug.windowstolinux.shared.backup.manifest.BackupDatabaseType.SQLITE) {
                current = RestoreCandidateState.DATABASE_COMMITTED;
                RestoreCandidatePort.HealthEvidence prepared = candidates.prepareCommit(
                        candidateRequest, staged, Optional.of(restored.connectionToken()));
                requireHealth(prepared, "stopped-write database activation boundary failed");
                databaseCommitAttempted = true;
                var committedDatabase = adapter.commitCandidate(plan.databaseRestore().orElseThrow());
                events.add(success(current, committedDatabase.evidence()));
                }
            }

            Optional<String> databaseToken = database.map(DatabaseRestoreEvidence::connectionToken);
            current = RestoreCandidateState.COMPONENTS_HEALTHY;
            RestoreCandidatePort.HealthEvidence components = candidates.verifyComponents(
                    candidateRequest, staged, databaseToken);
            requireHealth(components, "restored component health failed");
            events.add(success(current, components.evidence()));

            current = RestoreCandidateState.APPLICATION_HEALTHY;
            RestoreCandidatePort.HealthEvidence application = candidates.verifyApplication(
                    candidateRequest, staged, databaseToken);
            requireHealth(application, "whole-application restore health failed");
            events.add(success(current, application.evidence()));

            if (plan.databaseRestore().isPresent() && plan.validation().manifest().inventory().database().type()
                    == gold.debug.windowstolinux.shared.backup.manifest.BackupDatabaseType.SQLITE) {
                current = RestoreCandidateState.DATABASE_COMMITTED;
                var prepared = candidates.prepareCommit(candidateRequest,staged,databaseToken);
                requireHealth(prepared,"isolated SQLite candidate and formal graph must stop before activation");
                databaseCommitAttempted = true;
                var committedDatabase = databases.require(gold.debug.windowstolinux.shared.backup.manifest.BackupDatabaseType.SQLITE)
                        .commitCandidate(plan.databaseRestore().orElseThrow());
                events.add(success(current,committedDatabase.evidence()));
            }

            current = RestoreCandidateState.COMMITTED;
            RestoreCandidatePort.CommitEvidence committed = candidates.commit(
                    candidateRequest, staged, databaseToken);
            if (!committed.committed() || !committed.previousReleaseRetained()) {
                throw BackupException.create(BackupFailureType.RESTORE_COMMIT_FAILED,
                        "restore commit did not retain a verified previous release");
            }
            events.add(success(current, committed.evidence()));
            return new BackupRestoreResult(operation, BackupRestoreStatus.SUCCEEDED, events, database,
                    Optional.of(committed.activeReleaseToken()), Optional.empty());
        } catch (Exception exception) {
            FailureDescriptor original = failure(exception).withOperationIdentity(operation);
            if (events.isEmpty() || events.get(events.size() - 1).state() != current
                    || events.get(events.size() - 1).succeeded()) {
                events.add(new RestoreCandidateEvent(current, false, original.diagnostic()));
            }
            return recover(plan, operation, events, files, database, mutationAttempted,
                    databaseMutationAttempted, databaseCommitAttempted, original);
        }
    }

    /**
     * Attempts candidate cleanup and restoration of the prior target state, retaining failure evidence when recovery cannot be verified.
     * <p>尝试候选清理及此前目标状态恢复，无法验证恢复时保留失败证据。
     *
     * @param plan deterministic reviewed execution order and targets / 确定的已审阅执行顺序及目标
     * @param operation operation / 操作
     * @param events ordered progress or transaction events / 有序进度或事务事件
     * @param files controlled filesystem access or reviewed file inventory / 受控文件系统访问或已审阅文件清单
     * @param database reviewed database identity or database operation boundary / 已审阅数据库身份或数据库操作边界
     * @param mutationAttempted mutation attempted / 变更已尝试
     * @param databaseMutationAttempted database mutation attempted / 数据库变更已尝试
     * @param databaseCommitAttempted database commit attempted / 数据库提交已尝试
     * @param original original / 原始
     * @return constructed or resolved backup restore result / 构造或解析得到的备份恢复结果
     * @throws IllegalStateException if the required state or runtime facility is unavailable / 所需状态或运行设施不可用时
     */
    private BackupRestoreResult recover(
            BackupRestorePlan plan,
            OperationIdentity operation,
            List<RestoreCandidateEvent> events,
            Optional<RestoreCandidatePort.FileEvidence> files,
            Optional<DatabaseRestoreEvidence> database,
            boolean mutationAttempted,
            boolean databaseMutationAttempted,
            boolean databaseCommitAttempted,
            FailureDescriptor original
    ) {
        if (!mutationAttempted) {
            FailureDescriptor safe = original.withRecovery(
                    FailureRecoveryAction.NONE, FailureRecoveryDisposition.NOT_REQUIRED);
            return new BackupRestoreResult(operation, BackupRestoreStatus.FAILED_EXISTING_PRESERVED,
                    events, database, Optional.empty(), Optional.of(safe));
        }
        List<Throwable> recoveryFailures = new ArrayList<>();
        boolean recoveryQuiesced = true;
        if (databaseMutationAttempted) {
            try {
                RestoreCandidatePort.HealthEvidence stopped = candidates.quiesceForRecovery(
                        plan.candidateRequest(), files);
                if (!stopped.healthy()) throw new IllegalStateException("database recovery quiesce is unverified");
            } catch (Exception exception) {
                recoveryFailures.add(exception); recoveryQuiesced = false;
            }
        }
        if (databaseCommitAttempted && recoveryQuiesced) {
            try {
                databases.require(plan.validation().manifest().inventory().database().type())
                        .recoverCandidate(plan.databaseRestore().orElseThrow());
            } catch (Exception exception) {
                recoveryFailures.add(exception);
            }
        } else if (databaseMutationAttempted && recoveryQuiesced && plan.databaseRestore().isPresent()) {
            try {
                databases.require(plan.validation().manifest().inventory().database().type())
                        .discardCandidate(plan.databaseRestore().orElseThrow());
            } catch (Exception exception) { recoveryFailures.add(exception); }
        }
        RestoreCandidatePort.RecoveryEvidence recovered = null;
        try {
            if (!recoveryQuiesced) throw new IllegalStateException("candidate processes may still be using database files; retain them for manual recovery");
            recovered = candidates.recoverExisting(plan.candidateRequest(), files);
            if (!recovered.candidateRemoved() || !recovered.existingReleaseVerified()) {
                recoveryFailures.add(new IllegalStateException("restore recovery evidence is incomplete"));
            }
        } catch (Exception exception) {
            recoveryFailures.add(exception);
        }
        if (recoveryFailures.isEmpty()) {
            events.add(success(RestoreCandidateState.RECOVERY_VERIFIED, recovered.evidence()));
            FailureDescriptor safe = original.withRecovery(
                    FailureRecoveryAction.ROLLBACK, FailureRecoveryDisposition.SUCCEEDED);
            return new BackupRestoreResult(operation, BackupRestoreStatus.FAILED_EXISTING_PRESERVED,
                    events, database, Optional.empty(), Optional.of(safe));
        }
        BackupException recovery = BackupException.create(BackupFailureType.RESTORE_RECOVERY_FAILED,
                "restore candidate cleanup or existing release verification failed");
        recoveryFailures.forEach(recovery::addSuppressed);
        FailureDescriptor failed = recovery.failure().withOperationIdentity(operation).withRecovery(
                FailureRecoveryAction.REQUIRE_MANUAL_RECOVERY, FailureRecoveryDisposition.FAILED);
        events.add(new RestoreCandidateEvent(RestoreCandidateState.RECOVERY_VERIFIED, false, failed.diagnostic()));
        return new BackupRestoreResult(operation, BackupRestoreStatus.MANUAL_RECOVERY_REQUIRED,
                events, database, Optional.empty(), Optional.of(failed));
    }

    /**
     * Requires controlled filesystem access or reviewed file inventory.
     * <p>要求受控文件系统访问或已审阅文件清单。
     *
     * @param plan deterministic reviewed execution order and targets / 确定的已审阅执行顺序及目标
     * @param files controlled filesystem access or reviewed file inventory / 受控文件系统访问或已审阅文件清单
     * @throws BackupException if backup validation or the controlled backup operation fails / 备份校验或受控备份操作失败时
     */
    private static void requireFiles(BackupRestorePlan plan, RestoreCandidatePort.FileEvidence files)
            throws BackupException {
        if (!files.candidateId().equals(plan.candidateId()) || files.stagedBytes() != plan.candidate().extractedBytes()
                || !files.isolated() || !files.integrityVerified() || !files.existingReleaseUntouched()) {
            throw BackupException.create(BackupFailureType.RESTORE_FILE_FAILED,
                    "staged restore files lack complete isolation, integrity or current-release evidence");
        }
    }

    /**
     * Requires health.
     * <p>要求健康。
     *
     * @param health health / 健康
     * @param diagnostic bounded non-secret detail for diagnostic reporting / 用于诊断报告的有界非秘密详情
     * @throws BackupException if backup validation or the controlled backup operation fails / 备份校验或受控备份操作失败时
     */
    private static void requireHealth(RestoreCandidatePort.HealthEvidence health, String diagnostic)
            throws BackupException {
        if (!health.healthy()) throw BackupException.create(BackupFailureType.RESTORE_HEALTH_FAILED, diagnostic);
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
        return BackupException.create(BackupFailureType.RESTORE_FILE_FAILED,
                "unexpected restore candidate failure", exception).failure();
    }

    /**
     * Builds a successful outcome from the supplied completion evidence.
     * <p>根据所提供的完成证据构建成功结果。
     *
     * @param state current lifecycle or workflow state / 当前生命周期或工作流状态
     * @param evidence observations supporting the reported result / 支持所报告结果的观测证据
     * @return a successful outcome from the supplied completion evidence / 根据所提供的完成证据构建成功结果
     */
    private static RestoreCandidateEvent success(RestoreCandidateState state, List<String> evidence) {
        String joined = String.join("; ", evidence);
        if (joined.length() > 1024) joined = joined.substring(0, 1024);
        return new RestoreCandidateEvent(state, true, joined);
    }
}
