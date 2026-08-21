package gold.debug.windowstolinux.shared.backup.restore;

import gold.debug.windowstolinux.shared.backup.contract.spi.DatabaseRestoreEvidence;
import gold.debug.windowstolinux.shared.model.failure.FailureDescriptor;
import gold.debug.windowstolinux.shared.model.failure.OperationIdentity;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Terminal restore result separating health, commit and recovery evidence. / 区分健康、提交和恢复证据的恢复终态结果。 */
public record BackupRestoreResult(
        OperationIdentity operationIdentity,
        BackupRestoreStatus status,
        List<RestoreCandidateEvent> events,
        Optional<DatabaseRestoreEvidence> databaseEvidence,
        Optional<String> activeReleaseToken,
        Optional<FailureDescriptor> failure
) {
    /** Validates status-specific evidence. / 校验终态对应证据。 */
    public BackupRestoreResult {
        operationIdentity = Objects.requireNonNull(operationIdentity, "operationIdentity");
        status = Objects.requireNonNull(status, "status");
        events = List.copyOf(Objects.requireNonNull(events, "events"));
        databaseEvidence = Objects.requireNonNull(databaseEvidence, "databaseEvidence");
        activeReleaseToken = Objects.requireNonNull(activeReleaseToken, "activeReleaseToken");
        failure = Objects.requireNonNull(failure, "failure");
        if (events.isEmpty()) throw new IllegalArgumentException("restore result requires events");
        if (status == BackupRestoreStatus.SUCCEEDED) {
            if (activeReleaseToken.isEmpty() || failure.isPresent()) {
                throw new IllegalArgumentException("successful restore requires an active token and no failure");
            }
        } else if (failure.isEmpty() || activeReleaseToken.isPresent()) {
            throw new IllegalArgumentException("failed restore requires a failure and no active token");
        }
        if (failure.isPresent()) {
            FailureDescriptor value = failure.orElseThrow();
            if (!value.operationIdentity().equals(operationIdentity)) {
                throw new IllegalArgumentException("restore failure must use the result operation identity");
            }
        }
    }
}
