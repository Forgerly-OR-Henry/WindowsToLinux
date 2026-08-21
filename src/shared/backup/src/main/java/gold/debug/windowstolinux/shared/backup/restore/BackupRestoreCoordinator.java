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

/** Coordinates preflight, isolated restore, health, commit and verified recovery. / 编排前置检查、隔离恢复、健康、提交和已验证恢复。 */
public final class BackupRestoreCoordinator {
    private final BackupRestorePreflight preflight;
    private final RestoreCandidatePort candidates;
    private final DatabaseAdapterRegistry databases;

    /** Creates a restore coordinator over platform and database seams. / 基于平台及数据库接缝创建恢复协调器。 */
    public BackupRestoreCoordinator(
            BackupRestorePreflight preflight,
            RestoreCandidatePort candidates,
            DatabaseAdapterRegistry databases
    ) {
        this.preflight = Objects.requireNonNull(preflight, "preflight");
        this.candidates = Objects.requireNonNull(candidates, "candidates");
        this.databases = Objects.requireNonNull(databases, "databases");
    }

    /** Executes one fail-closed candidate restore without overwriting the current release. / 执行一次不覆盖当前发布的故障关闭候选恢复。 */
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
                    databaseMutationAttempted, original);
        }
    }

    private BackupRestoreResult recover(
            BackupRestorePlan plan,
            OperationIdentity operation,
            List<RestoreCandidateEvent> events,
            Optional<RestoreCandidatePort.FileEvidence> files,
            Optional<DatabaseRestoreEvidence> database,
            boolean mutationAttempted,
            boolean databaseMutationAttempted,
            FailureDescriptor original
    ) {
        if (!mutationAttempted) {
            FailureDescriptor safe = original.withRecovery(
                    FailureRecoveryAction.NONE, FailureRecoveryDisposition.NOT_REQUIRED);
            return new BackupRestoreResult(operation, BackupRestoreStatus.FAILED_EXISTING_PRESERVED,
                    events, database, Optional.empty(), Optional.of(safe));
        }
        List<Throwable> recoveryFailures = new ArrayList<>();
        if (databaseMutationAttempted && plan.databaseRestore().isPresent()) {
            try {
                databases.require(plan.validation().manifest().inventory().database().type())
                        .discardCandidate(plan.databaseRestore().orElseThrow());
            } catch (Exception exception) {
                recoveryFailures.add(exception);
            }
        }
        RestoreCandidatePort.RecoveryEvidence recovered = null;
        try {
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

    private static void requireFiles(BackupRestorePlan plan, RestoreCandidatePort.FileEvidence files)
            throws BackupException {
        if (!files.candidateId().equals(plan.candidateId()) || files.stagedBytes() != plan.candidate().extractedBytes()
                || !files.isolated() || !files.integrityVerified() || !files.existingReleaseUntouched()) {
            throw BackupException.create(BackupFailureType.RESTORE_FILE_FAILED,
                    "staged restore files lack complete isolation, integrity or current-release evidence");
        }
    }

    private static void requireHealth(RestoreCandidatePort.HealthEvidence health, String diagnostic)
            throws BackupException {
        if (!health.healthy()) throw BackupException.create(BackupFailureType.RESTORE_HEALTH_FAILED, diagnostic);
    }

    private static FailureDescriptor failure(Exception exception) {
        if (exception instanceof BackupException backup) return backup.failure();
        return BackupException.create(BackupFailureType.RESTORE_FILE_FAILED,
                "unexpected restore candidate failure", exception).failure();
    }

    private static RestoreCandidateEvent success(RestoreCandidateState state, List<String> evidence) {
        String joined = String.join("; ", evidence);
        if (joined.length() > 1024) joined = joined.substring(0, 1024);
        return new RestoreCandidateEvent(state, true, joined);
    }
}
