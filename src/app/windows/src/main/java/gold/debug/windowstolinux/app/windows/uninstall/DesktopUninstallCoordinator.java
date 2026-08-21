package gold.debug.windowstolinux.app.windows.uninstall;

import gold.debug.windowstolinux.shared.model.failure.FailureDescriptor;
import gold.debug.windowstolinux.shared.model.failure.FailureRecoveryAction;
import gold.debug.windowstolinux.shared.model.failure.FailureRecoveryDisposition;
import gold.debug.windowstolinux.shared.model.failure.OperationIdentity;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Executes an explicit-choice uninstall within verified local application boundaries. / 在已验证本地应用边界内执行显式选择的卸载。 */
public final class DesktopUninstallCoordinator {
    private final DesktopUninstallPort port;

    /** Creates an uninstall coordinator over one platform implementation. / 基于一个平台实现创建卸载协调器。 */
    public DesktopUninstallCoordinator(DesktopUninstallPort port) {
        this.port = Objects.requireNonNull(port, "port");
    }

    /** Removes only managed local program/data/credentials and reports exact residuals. / 仅移除受管本地程序、数据及凭据并报告精确残留。 */
    public DesktopUninstallResult uninstall(DesktopUninstallRequest request) {
        Objects.requireNonNull(request, "request");
        OperationIdentity operation = OperationIdentity.create();
        List<DesktopUninstallEvent> events = new ArrayList<>();
        if (request.decision().isEmpty()) {
            FailureDescriptor required = FailureDescriptor.create(DesktopUninstallFailureType.DECISION_REQUIRED,
                    operation, "uninstall requires an explicit data and credential decision");
            events.add(new DesktopUninstallEvent(DesktopUninstallState.DECISION_VALIDATED, false,
                    required.diagnostic()));
            return result(operation, DesktopUninstallStatus.DECISION_REQUIRED, events, List.of(), List.of(),
                    Optional.of(required));
        }
        DesktopUninstallDecisionType decision = request.decision().orElseThrow();
        events.add(new DesktopUninstallEvent(DesktopUninstallState.DECISION_VALIDATED, true,
                decision == DesktopUninstallDecisionType.KEEP_DATA_AND_CREDENTIALS
                        ? "user explicitly chose to retain local data and credentials"
                        : "user explicitly chose to delete managed local data and credentials"));
        DesktopUninstallState state = DesktopUninstallState.TASKS_STOPPED;
        try {
            DesktopUninstallPort.StepEvidence stopped = port.stopOwnedTasks(request);
            require(stopped.completed() && stopped.verified(), DesktopUninstallFailureType.TASKS_ACTIVE,
                    "application-owned tasks could not be stopped and verified");
            events.add(success(DesktopUninstallState.TASKS_STOPPED, stopped.evidence()));

            state = DesktopUninstallState.BOUNDARIES_VERIFIED;
            DesktopUninstallPort.BoundaryEvidence boundaries = port.verifyManagedBoundaries(request);
            boolean deleteData = decision == DesktopUninstallDecisionType.DELETE_DATA_AND_CREDENTIALS;
            boolean completeBoundary = boundaries.jpackageLayoutVerified() && boundaries.installMarkerVerified()
                    && (!deleteData || boundaries.dataMarkerVerified() && boundaries.credentialNamespaceVerified());
            require(completeBoundary, DesktopUninstallFailureType.BOUNDARY_INVALID,
                    "jpackage, data marker or credential namespace ownership is incomplete");
            events.add(success(DesktopUninstallState.BOUNDARIES_VERIFIED, boundaries.evidence()));

            List<String> residuals = new ArrayList<>();
            state = DesktopUninstallState.PROGRAM_REMOVED;
            DesktopUninstallPort.RemovalEvidence program = port.removeProgram(request);
            addRemoval(events, DesktopUninstallState.PROGRAM_REMOVED, program, residuals);
            List<String> retained = new ArrayList<>();
            if (deleteData) {
                state = DesktopUninstallState.DATA_REMOVED;
                DesktopUninstallPort.RemovalEvidence data = port.removeData(request);
                addRemoval(events, DesktopUninstallState.DATA_REMOVED, data, residuals);
                state = DesktopUninstallState.CREDENTIALS_REMOVED;
                DesktopUninstallPort.RemovalEvidence credentials = port.removeCredentials(request);
                addRemoval(events, DesktopUninstallState.CREDENTIALS_REMOVED, credentials, residuals);
            } else {
                retained.add(request.dataRoot().toString());
                retained.add(request.credentialNamespace());
            }
            if (!residuals.isEmpty()) {
                FailureDescriptor incomplete = FailureDescriptor.create(
                        DesktopUninstallFailureType.REMOVAL_INCOMPLETE, operation,
                        "one or more exact managed uninstall targets remain")
                        .withRecovery(FailureRecoveryAction.REQUIRE_MANUAL_RECOVERY,
                                FailureRecoveryDisposition.FAILED);
                return result(operation, DesktopUninstallStatus.COMPLETED_WITH_RESIDUALS, events,
                        residuals, retained, Optional.of(incomplete));
            }
            DesktopUninstallStatus status = deleteData ? DesktopUninstallStatus.SUCCEEDED_DATA_DELETED
                    : DesktopUninstallStatus.SUCCEEDED_DATA_RETAINED;
            return result(operation, status, events, List.of(), retained, Optional.empty());
        } catch (Exception exception) {
            FailureDescriptor failure = failure(exception).withOperationIdentity(operation).withRecovery(
                    FailureRecoveryAction.NONE, FailureRecoveryDisposition.NOT_ATTEMPTED);
            events.add(new DesktopUninstallEvent(state, false,
                    failure.diagnostic()));
            return result(operation, DesktopUninstallStatus.PRECONDITION_REJECTED, events,
                    List.of(), List.of(), Optional.of(failure));
        }
    }

    private static void addRemoval(
            List<DesktopUninstallEvent> events,
            DesktopUninstallState state,
            DesktopUninstallPort.RemovalEvidence removal,
            List<String> residuals
    ) {
        residuals.addAll(removal.residualItems());
        boolean succeeded = removal.completed() && removal.verified() && removal.residualItems().isEmpty();
        events.add(new DesktopUninstallEvent(state, succeeded, joined(removal.evidence())));
    }

    private static DesktopUninstallResult result(
            OperationIdentity operation,
            DesktopUninstallStatus status,
            List<DesktopUninstallEvent> events,
            List<String> residuals,
            List<String> retained,
            Optional<FailureDescriptor> failure
    ) {
        return new DesktopUninstallResult(operation, status, events, residuals, retained, failure);
    }

    private static void require(boolean condition, DesktopUninstallFailureType type, String diagnostic)
            throws DesktopUninstallException {
        if (!condition) throw DesktopUninstallException.create(type, diagnostic);
    }

    private static FailureDescriptor failure(Exception exception) {
        if (exception instanceof DesktopUninstallException uninstall) return uninstall.failure();
        return DesktopUninstallException.create(DesktopUninstallFailureType.BOUNDARY_INVALID,
                "unexpected desktop uninstall boundary failure").failure();
    }

    private static DesktopUninstallEvent success(DesktopUninstallState state, List<String> evidence) {
        return new DesktopUninstallEvent(state, true, joined(evidence));
    }

    private static String joined(List<String> evidence) {
        String joined = String.join("; ", evidence);
        return joined.length() <= 1024 ? joined : joined.substring(0, 1024);
    }
}
