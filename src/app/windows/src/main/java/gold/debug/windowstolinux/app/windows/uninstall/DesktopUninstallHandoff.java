package gold.debug.windowstolinux.app.windows.uninstall;

import gold.debug.windowstolinux.shared.model.failure.OperationIdentity;

import java.util.List;
import java.util.Objects;

/** Explicit uninstall decision and quiesce evidence handed to an external worker. / 交给外部执行器的显式卸载决定与停收证据。 */
public record DesktopUninstallHandoff(
        OperationIdentity operationIdentity,
        DesktopUninstallRequest request,
        List<DesktopUninstallEvent> preparationEvents
) {
    /** Requires a decision and successful task quiesce before process exit. / 要求进程退出前已有决定且任务停收成功。 */
    public DesktopUninstallHandoff {
        operationIdentity = Objects.requireNonNull(operationIdentity, "operationIdentity");
        request = Objects.requireNonNull(request, "request");
        preparationEvents = List.copyOf(Objects.requireNonNull(preparationEvents, "preparationEvents"));
        if (request.decision().isEmpty() || preparationEvents.size() != 2
                || preparationEvents.get(0).state() != DesktopUninstallState.DECISION_VALIDATED
                || preparationEvents.get(1).state() != DesktopUninstallState.TASKS_STOPPED
                || preparationEvents.stream().anyMatch(event -> !event.succeeded())) {
            throw new IllegalArgumentException("uninstall handoff requires an explicit decision and stopped owned tasks");
        }
    }
}
