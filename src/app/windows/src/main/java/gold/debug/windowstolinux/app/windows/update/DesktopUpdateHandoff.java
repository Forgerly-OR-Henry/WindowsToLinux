package gold.debug.windowstolinux.app.windows.update;

import gold.debug.windowstolinux.shared.model.failure.OperationIdentity;

import java.util.List;
import java.util.Objects;

/** Immutable main-process evidence handed to an independently launched updater. / 交给独立更新器的不可变主进程证据。 */
public record DesktopUpdateHandoff(
        OperationIdentity operationIdentity,
        DesktopUpdateVerification update,
        DesktopUpdatePort.BackupEvidence backup,
        List<DesktopUpdateEvent> preparationEvents
) {
    /** Validates that replacement has not begun before the independent handoff. / 校验独立交接前尚未开始替换。 */
    public DesktopUpdateHandoff {
        operationIdentity = Objects.requireNonNull(operationIdentity, "operationIdentity");
        update = Objects.requireNonNull(update, "update");
        backup = Objects.requireNonNull(backup, "backup");
        preparationEvents = List.copyOf(Objects.requireNonNull(preparationEvents, "preparationEvents"));
        if (!backup.programBackedUp() || !backup.databaseBackedUp()
                || !backup.dataLocationPreserved() || !backup.credentialModePreserved()
                || preparationEvents.size() != 2
                || preparationEvents.get(0).state() != DesktopUpdateState.TASKS_QUIESCED
                || preparationEvents.get(1).state() != DesktopUpdateState.BACKUP_CREATED
                || preparationEvents.stream().anyMatch(event -> !event.succeeded())) {
            throw new IllegalArgumentException("update handoff requires successful quiesce and paired backup evidence");
        }
    }
}
