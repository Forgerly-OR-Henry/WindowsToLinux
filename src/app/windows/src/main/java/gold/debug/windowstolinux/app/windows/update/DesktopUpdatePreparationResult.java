package gold.debug.windowstolinux.app.windows.update;

import gold.debug.windowstolinux.shared.model.failure.FailureDescriptor;
import gold.debug.windowstolinux.shared.model.failure.OperationIdentity;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Result of the main-process-only update preparation stage. / 仅由主进程执行的更新准备阶段结果。 */
public record DesktopUpdatePreparationResult(
        OperationIdentity operationIdentity,
        DesktopUpdatePreparationStatus status,
        DesktopReleaseVersion requestedVersion,
        List<DesktopUpdateEvent> events,
        Optional<DesktopUpdateHandoff> handoff,
        Optional<FailureDescriptor> failure
) {
    /** Validates mutually exclusive handoff and failure outcomes. / 校验互斥的交接与失败结果。 */
    public DesktopUpdatePreparationResult {
        operationIdentity = Objects.requireNonNull(operationIdentity, "operationIdentity");
        status = Objects.requireNonNull(status, "status");
        requestedVersion = Objects.requireNonNull(requestedVersion, "requestedVersion");
        events = List.copyOf(Objects.requireNonNull(events, "events"));
        handoff = Objects.requireNonNull(handoff, "handoff");
        failure = Objects.requireNonNull(failure, "failure");
        if (events.isEmpty()) throw new IllegalArgumentException("update preparation requires events");
        if (status == DesktopUpdatePreparationStatus.READY_FOR_HANDOFF) {
            if (handoff.isEmpty() || failure.isPresent()
                    || !handoff.orElseThrow().operationIdentity().equals(operationIdentity)) {
                throw new IllegalArgumentException("ready update preparation requires its exact handoff and no failure");
            }
        } else if (handoff.isPresent() || failure.isEmpty()) {
            throw new IllegalArgumentException("rejected update preparation requires one structured failure");
        }
        if (failure.isPresent() && !failure.orElseThrow().operationIdentity().equals(operationIdentity)) {
            throw new IllegalArgumentException("update preparation failure must use its operation identity");
        }
    }
}
