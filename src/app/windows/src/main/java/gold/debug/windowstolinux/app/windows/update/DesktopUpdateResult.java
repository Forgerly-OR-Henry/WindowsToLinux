package gold.debug.windowstolinux.app.windows.update;

import gold.debug.windowstolinux.shared.model.failure.FailureDescriptor;
import gold.debug.windowstolinux.shared.model.failure.OperationIdentity;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Terminal update result with paired program/database recovery evidence. / 带程序及数据库成对恢复证据的更新终态。 */
public record DesktopUpdateResult(
        OperationIdentity operationIdentity,
        DesktopUpdateStatus status,
        DesktopReleaseVersion requestedVersion,
        List<DesktopUpdateEvent> events,
        Optional<String> rollbackBackupToken,
        Optional<FailureDescriptor> failure
) {
    /** Validates status-specific evidence. / 校验终态对应证据。 */
    public DesktopUpdateResult {
        operationIdentity = Objects.requireNonNull(operationIdentity, "operationIdentity");
        status = Objects.requireNonNull(status, "status");
        requestedVersion = Objects.requireNonNull(requestedVersion, "requestedVersion");
        events = List.copyOf(Objects.requireNonNull(events, "events"));
        rollbackBackupToken = Objects.requireNonNull(rollbackBackupToken, "rollbackBackupToken");
        failure = Objects.requireNonNull(failure, "failure");
        if (events.isEmpty()) throw new IllegalArgumentException("update result requires events");
        if (status == DesktopUpdateStatus.SUCCEEDED) {
            if (rollbackBackupToken.isEmpty() || failure.isPresent()) {
                throw new IllegalArgumentException("successful update requires retained rollback backup and no failure");
            }
        } else if (failure.isEmpty()) {
            throw new IllegalArgumentException("failed update requires a structured failure");
        }
        if (failure.isPresent() && !failure.orElseThrow().operationIdentity().equals(operationIdentity)) {
            throw new IllegalArgumentException("update failure must use the result operation identity");
        }
    }
}
