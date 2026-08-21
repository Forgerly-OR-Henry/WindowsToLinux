package gold.debug.windowstolinux.app.windows.update;

import gold.debug.windowstolinux.shared.model.failure.FailureDescriptor;
import gold.debug.windowstolinux.shared.model.failure.FailureRecoveryAction;
import gold.debug.windowstolinux.shared.model.failure.FailureRecoveryDisposition;
import gold.debug.windowstolinux.shared.model.failure.OperationIdentity;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Coordinates an independently handed-off program and SQLite update transaction. / 编排已独立交接的程序及 SQLite 更新事务。 */
public final class DesktopUpdateCoordinator {
    private final DesktopUpdatePort port;

    /** Creates an updater coordinator over one platform implementation. / 基于一个平台实现创建更新协调器。 */
    public DesktopUpdateCoordinator(DesktopUpdatePort port) {
        this.port = Objects.requireNonNull(port, "port");
    }

    /** Updates program and database as one recoverable pair. / 将程序及数据库作为一个可恢复整体更新。 */
    public DesktopUpdateResult update(DesktopUpdateVerification update) {
        Objects.requireNonNull(update, "update");
        OperationIdentity operation = OperationIdentity.create();
        List<DesktopUpdateEvent> events = new ArrayList<>();
        DesktopUpdateState state = DesktopUpdateState.TASKS_QUIESCED;
        Optional<DesktopUpdatePort.BackupEvidence> backup = Optional.empty();
        boolean replacementAttempted = false;
        try {
            requireStep(port.quiesceTasks(), DesktopUpdateFailureType.TRANSACTION_FAILED,
                    "desktop tasks did not reach a safe terminal or recoverable state");
            events.add(success(state, "new tasks stopped and existing tasks reached safe states"));

            state = DesktopUpdateState.BACKUP_CREATED;
            DesktopUpdatePort.BackupEvidence created = port.backupCurrent(update);
            backup = Optional.of(created);
            if (!created.programBackedUp() || !created.databaseBackedUp()
                    || !created.dataLocationPreserved() || !created.credentialModePreserved()) {
                throw DesktopUpdateException.create(DesktopUpdateFailureType.TRANSACTION_FAILED,
                        "program, SQLite, data location and credential mode were not backed up as one update point");
            }
            events.add(success(state, created.evidence()));

            state = DesktopUpdateState.INDEPENDENT_UPDATER_VERIFIED;
            DesktopUpdatePort.HandoffEvidence handoff = port.verifyIndependentUpdater(update, created);
            if (!handoff.independentUpdaterVerified() || !handoff.mainProcessExited()) {
                throw DesktopUpdateException.create(DesktopUpdateFailureType.TRANSACTION_FAILED,
                        "independent updater identity or main process exit could not be verified");
            }
            events.add(success(state, handoff.evidence()));

            state = DesktopUpdateState.PROGRAM_REPLACED;
            replacementAttempted = true;
            DesktopUpdatePort.StepEvidence replaced = port.replaceProgram(update, created);
            requireStep(replaced, DesktopUpdateFailureType.TRANSACTION_FAILED,
                    "signed program files could not be replaced and verified");
            events.add(success(state, replaced.evidence()));

            state = DesktopUpdateState.DATABASE_MIGRATED;
            DesktopUpdatePort.StepEvidence migrated = port.migrateDatabase(update, created);
            requireStep(migrated, DesktopUpdateFailureType.TRANSACTION_FAILED,
                    "SQLite migration did not commit and verify successfully");
            events.add(success(state, migrated.evidence()));

            state = DesktopUpdateState.NEW_VERSION_HEALTHY;
            DesktopUpdatePort.StepEvidence healthy = port.startAndVerify(update, created);
            requireStep(healthy, DesktopUpdateFailureType.TRANSACTION_FAILED,
                    "new desktop version did not pass startup health verification");
            events.add(success(state, healthy.evidence()));
            return new DesktopUpdateResult(operation, DesktopUpdateStatus.SUCCEEDED, update.version(), events,
                    Optional.of(created.backupToken()), Optional.empty());
        } catch (Exception exception) {
            FailureDescriptor original = failure(exception).withOperationIdentity(operation);
            if (events.isEmpty() || events.get(events.size() - 1).state() != state
                    || events.get(events.size() - 1).succeeded()) {
                events.add(new DesktopUpdateEvent(state, false, original.diagnostic()));
            }
            if (!replacementAttempted || backup.isEmpty()) {
                FailureDescriptor safe = original.withRecovery(
                        FailureRecoveryAction.NONE, FailureRecoveryDisposition.NOT_REQUIRED);
                return new DesktopUpdateResult(operation, DesktopUpdateStatus.PRECONDITION_REJECTED,
                        update.version(), events, backup.map(DesktopUpdatePort.BackupEvidence::backupToken),
                        Optional.of(safe));
            }
            return rollback(update, operation, events, backup.orElseThrow(), original);
        }
    }

    private DesktopUpdateResult rollback(
            DesktopUpdateVerification update,
            OperationIdentity operation,
            List<DesktopUpdateEvent> events,
            DesktopUpdatePort.BackupEvidence backup,
            FailureDescriptor original
    ) {
        try {
            DesktopUpdatePort.RollbackEvidence rollback = port.rollbackProgramAndDatabase(backup);
            if (!rollback.programRestored() || !rollback.databaseRestored() || !rollback.previousVersionHealthy()) {
                throw DesktopUpdateException.create(DesktopUpdateFailureType.ROLLBACK_FAILED,
                        "program and pre-migration SQLite backup were not both restored and verified");
            }
            events.add(success(DesktopUpdateState.ROLLBACK_VERIFIED, rollback.evidence()));
            FailureDescriptor safe = original.withRecovery(
                    FailureRecoveryAction.ROLLBACK, FailureRecoveryDisposition.SUCCEEDED);
            return new DesktopUpdateResult(operation, DesktopUpdateStatus.FAILED_ROLLED_BACK, update.version(),
                    events, Optional.of(backup.backupToken()), Optional.of(safe));
        } catch (Exception exception) {
            FailureDescriptor failed = failure(DesktopUpdateException.create(DesktopUpdateFailureType.ROLLBACK_FAILED,
                    "program and SQLite rollback could not be verified", exception))
                    .withOperationIdentity(operation)
                    .withRecovery(FailureRecoveryAction.REQUIRE_MANUAL_RECOVERY,
                            FailureRecoveryDisposition.FAILED);
            events.add(new DesktopUpdateEvent(DesktopUpdateState.ROLLBACK_VERIFIED, false, failed.diagnostic()));
            return new DesktopUpdateResult(operation, DesktopUpdateStatus.MANUAL_RECOVERY_REQUIRED,
                    update.version(), events, Optional.of(backup.backupToken()), Optional.of(failed));
        }
    }

    private static void requireStep(
            DesktopUpdatePort.StepEvidence evidence, DesktopUpdateFailureType type, String diagnostic)
            throws DesktopUpdateException {
        if (!evidence.completed() || !evidence.verified()) {
            throw DesktopUpdateException.create(type, diagnostic);
        }
    }

    private static FailureDescriptor failure(Exception exception) {
        if (exception instanceof DesktopUpdateException update) return update.failure();
        return DesktopUpdateException.create(DesktopUpdateFailureType.TRANSACTION_FAILED,
                "unexpected desktop update transaction failure", exception).failure();
    }

    private static DesktopUpdateEvent success(DesktopUpdateState state, List<String> evidence) {
        String joined = String.join("; ", evidence);
        if (joined.length() > 1024) joined = joined.substring(0, 1024);
        return new DesktopUpdateEvent(state, true, joined);
    }

    private static DesktopUpdateEvent success(DesktopUpdateState state, String evidence) {
        return new DesktopUpdateEvent(state, true, evidence);
    }
}
