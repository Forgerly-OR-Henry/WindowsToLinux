package gold.debug.windowstolinux.app.windows.uninstall;

import gold.debug.windowstolinux.shared.model.failure.FailureDescriptor;
import gold.debug.windowstolinux.shared.model.failure.OperationIdentity;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Desktop uninstall result with exact failed residual items. / 带精确失败残留项目的桌面卸载结果。 */
public record DesktopUninstallResult(
        OperationIdentity operationIdentity,
        DesktopUninstallStatus status,
        List<DesktopUninstallEvent> events,
        List<String> residualItems,
        List<String> intentionallyRetainedItems,
        Optional<FailureDescriptor> failure
) {
    /** Validates status, failure and residual consistency. / 校验终态、失败及残留一致性。 */
    public DesktopUninstallResult {
        operationIdentity = Objects.requireNonNull(operationIdentity, "operationIdentity");
        status = Objects.requireNonNull(status, "status");
        events = List.copyOf(Objects.requireNonNull(events, "events"));
        residualItems = List.copyOf(Objects.requireNonNull(residualItems, "residualItems"));
        intentionallyRetainedItems = List.copyOf(Objects.requireNonNull(
                intentionallyRetainedItems, "intentionallyRetainedItems"));
        failure = Objects.requireNonNull(failure, "failure");
        if (events.isEmpty()) throw new IllegalArgumentException("uninstall result requires events");
        if (status == DesktopUninstallStatus.COMPLETED_WITH_RESIDUALS && residualItems.isEmpty()) {
            throw new IllegalArgumentException("residual terminal status requires exact residual items");
        }
        if (status != DesktopUninstallStatus.COMPLETED_WITH_RESIDUALS && !residualItems.isEmpty()) {
            throw new IllegalArgumentException("only residual terminal status may report failed removals");
        }
        boolean successful = status == DesktopUninstallStatus.SUCCEEDED_DATA_RETAINED
                || status == DesktopUninstallStatus.SUCCEEDED_DATA_DELETED;
        if (successful == failure.isPresent()) {
            throw new IllegalArgumentException("successful uninstall has no failure; other statuses require one");
        }
        if (failure.isPresent() && !failure.orElseThrow().operationIdentity().equals(operationIdentity)) {
            throw new IllegalArgumentException("uninstall failure must use the result operation identity");
        }
    }
}
