package gold.debug.windowstolinux.app.windows.uninstall;

import gold.debug.windowstolinux.shared.model.failure.FailureDescriptor;
import gold.debug.windowstolinux.shared.model.failure.OperationIdentity;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Result of validating the decision and stopping main-process-owned tasks. / 校验决定并停止主进程任务的结果。 */
public record DesktopUninstallPreparationResult(
        OperationIdentity operationIdentity,
        DesktopUninstallPreparationStatus status,
        List<DesktopUninstallEvent> events,
        Optional<DesktopUninstallHandoff> handoff,
        Optional<FailureDescriptor> failure
) {
    /** Validates one exact preparation outcome. / 校验一个精确准备结果。 */
    public DesktopUninstallPreparationResult {
        operationIdentity = Objects.requireNonNull(operationIdentity, "operationIdentity");
        status = Objects.requireNonNull(status, "status");
        events = List.copyOf(Objects.requireNonNull(events, "events"));
        handoff = Objects.requireNonNull(handoff, "handoff");
        failure = Objects.requireNonNull(failure, "failure");
        if (events.isEmpty()) throw new IllegalArgumentException("uninstall preparation requires events");
        if (status == DesktopUninstallPreparationStatus.READY_FOR_HANDOFF) {
            if (handoff.isEmpty() || failure.isPresent()
                    || !handoff.orElseThrow().operationIdentity().equals(operationIdentity)) {
                throw new IllegalArgumentException("ready uninstall preparation requires its exact handoff");
            }
        } else if (handoff.isPresent() || failure.isEmpty()) {
            throw new IllegalArgumentException("rejected uninstall preparation requires one structured failure");
        }
        if (failure.isPresent() && !failure.orElseThrow().operationIdentity().equals(operationIdentity)) {
            throw new IllegalArgumentException("uninstall preparation failure must use its operation identity");
        }
    }
}
