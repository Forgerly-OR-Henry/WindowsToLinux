package gold.debug.windowstolinux.app.windows.uninstall;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import gold.debug.windowstolinux.app.windows.uninstall.model.DesktopUninstallDecisionType;
import gold.debug.windowstolinux.app.windows.uninstall.model.DesktopUninstallException;
import gold.debug.windowstolinux.app.windows.uninstall.model.DesktopUninstallFailureType;
import gold.debug.windowstolinux.app.windows.uninstall.model.DesktopUninstallSetupStatus;
import gold.debug.windowstolinux.app.windows.uninstall.model.DesktopUninstallStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DesktopUninstallFlowTest {
    @TempDir
    Path temporary;

    @Test
    void missingDecisionDoesNotChooseOrDeleteAnything() {
        RecordingUninstallPort port = new RecordingUninstallPort(false, false, false);

        DesktopUninstallSetupResult result = new DesktopUninstallFlow(port)
            .prepare(request(Optional.empty()));

        assertEquals(DesktopUninstallSetupStatus.DECISION_REQUIRED, result.status());
        assertEquals("windows.uninstall.decision-required", result.problem().orElseThrow().eventCode());
        assertEquals(gold.debug.windowstolinux.appapi.task.result.ProblemCode.INVALID_INPUT,
            result.problem().orElseThrow().code());
        assertEquals("windows.error.uninstallDecisionRequired",
            result.problem().orElseThrow().context().get("messageKey"));
        assertEquals(result.operationIdentity(), result.problem().orElseThrow().diagnosticId().orElseThrow());
        assertTrue(port.calls.isEmpty());
    }

    @Test
    void explicitKeepChoiceRemovesProgramButRetainsDataAndCredentials() {
        RecordingUninstallPort port = new RecordingUninstallPort(false, false, false);
        DesktopUninstallRequest request = request(Optional.of(DesktopUninstallDecisionType.KEEP_DATA_AND_CREDENTIALS));

        DesktopUninstallFlow coordinator = new DesktopUninstallFlow(port);
        DesktopUninstallSetupResult preparation = coordinator.prepare(request);

        assertEquals(DesktopUninstallSetupStatus.READY_FOR_HANDOFF, preparation.status());
        assertEquals(List.of("stop"), port.calls);
        DesktopUninstallResult result = coordinator.apply(preparation.handoff().orElseThrow());

        assertEquals(DesktopUninstallStatus.SUCCEEDED_DATA_RETAINED, result.status());
        assertEquals(List.of("stop", "handoff", "verify", "program"), port.calls);
        assertEquals(List.of(request.dataRoot().toString()), result.intentionallyRetainedItems());
    }

    @Test
    void explicitDeleteChoiceStaysInsideAllVerifiedManagedBoundaries() {
        RecordingUninstallPort port = new RecordingUninstallPort(false, false, false);

        DesktopUninstallFlow coordinator = new DesktopUninstallFlow(port);
        DesktopUninstallSetupResult preparation = coordinator
            .prepare(request(Optional.of(DesktopUninstallDecisionType.DELETE_DATA_AND_CREDENTIALS)));
        DesktopUninstallResult result = coordinator.apply(preparation.handoff().orElseThrow());

        assertEquals(DesktopUninstallStatus.SUCCEEDED_DATA_DELETED, result.status());
        assertEquals(List.of("stop", "handoff", "verify", "program", "data"), port.calls);
        assertTrue(result.residualItems().isEmpty());
    }

    @Test
    void unverifiedMarkerStopsBeforeAnyRemoval() {
        RecordingUninstallPort port = new RecordingUninstallPort(false, true, false);

        DesktopUninstallFlow coordinator = new DesktopUninstallFlow(port);
        DesktopUninstallSetupResult preparation = coordinator
            .prepare(request(Optional.of(DesktopUninstallDecisionType.DELETE_DATA_AND_CREDENTIALS)));
        DesktopUninstallResult result = coordinator.apply(preparation.handoff().orElseThrow());

        assertEquals(DesktopUninstallStatus.PRECONDITION_REJECTED, result.status());
        assertEquals(List.of("stop", "handoff", "verify"), port.calls);
    }

    @Test
    void unverifiedExternalWorkerNeverInspectsOrDeletesManagedTargets() {
        RecordingUninstallPort port = new RecordingUninstallPort(true, false, false);
        DesktopUninstallFlow coordinator = new DesktopUninstallFlow(port);
        DesktopUninstallSetupResult preparation = coordinator
            .prepare(request(Optional.of(DesktopUninstallDecisionType.DELETE_DATA_AND_CREDENTIALS)));

        DesktopUninstallResult result = coordinator.apply(preparation.handoff().orElseThrow());

        assertEquals(DesktopUninstallStatus.PRECONDITION_REJECTED, result.status());
        assertEquals(List.of("stop", "handoff"), port.calls);
    }

    @Test
    void incompleteDeletionReportsExactResidualItems() {
        RecordingUninstallPort port = new RecordingUninstallPort(false, false, true);
        DesktopUninstallRequest request = request(
            Optional.of(DesktopUninstallDecisionType.DELETE_DATA_AND_CREDENTIALS));

        DesktopUninstallFlow coordinator = new DesktopUninstallFlow(port);
        DesktopUninstallResult result = coordinator.apply(coordinator.prepare(request).handoff().orElseThrow());

        assertEquals(DesktopUninstallStatus.COMPLETED_WITH_RESIDUALS, result.status());
        assertEquals(List.of(request.dataRoot().resolve("locked.db").toString()), result.residualItems());
        assertEquals("windows.uninstall.removal-incomplete", result.problem().orElseThrow().eventCode());
    }

    @Test
    void databaseCredentialsStayInsideTheFixedDataDirectory() {
        Path install = temporary.resolve("WindowsToLinux");
        assertThrows(IllegalArgumentException.class,
            () -> new DesktopUninstallRequest(Optional.empty(), install, install.resolve("other")));
        assertEquals(install.resolve("data").toAbsolutePath().normalize(),
            new DesktopUninstallRequest(Optional.empty(), install, install.resolve("data")).dataRoot());
    }

    @Test
    void checkedWindowsFailuresRetainCauseSuppressedEvidenceAndPublicDiagnostic() {
        var cause = new java.io.IOException("Original safe evidence");
        var suppressed = new java.io.IOException("Safe cleanup evidence");
        java.io.IOException[] failures = {
            DesktopUninstallException.create(DesktopUninstallFailureType.BOUNDARY_INVALID, "Rejected boundary",
                cause),
            gold.debug.windowstolinux.app.windows.update.model.DesktopUpdateException.create(
                gold.debug.windowstolinux.app.windows.update.model.DesktopUpdateFailureType.PACKAGE_INVALID,
                "Rejected package", cause),
            gold.debug.windowstolinux.app.windows.handoff.DesktopHandoffException.create(
                gold.debug.windowstolinux.app.windows.handoff.DesktopHandoffFailureType.HANDOFF_INVALID,
                "Rejected handoff", cause)};
        for (var failure : failures) {
            org.junit.jupiter.api.Assertions.assertSame(cause, failure.getCause());
            failure.addSuppressed(suppressed);
            org.junit.jupiter.api.Assertions.assertSame(suppressed, failure.getSuppressed()[0]);
        }
        var problem = ((DesktopUninstallException) failures[0]).problem();
        assertEquals("windows.uninstall.boundary-invalid", problem.eventCode());
        assertEquals("windows", problem.context().get("domain"));
        assertEquals("validation", problem.context().get("phase"));
        assertEquals("ERROR", problem.context().get("severity"));
        assertEquals("REQUEST_USER_CORRECTION", problem.context().get("recoveryAction"));
        assertEquals("NOT_ATTEMPTED", problem.context().get("recoveryDisposition"));
        assertTrue(problem.diagnosticId().isPresent());
        assertThrows(UnsupportedOperationException.class, () -> problem.context().put("phase", "changed"));
    }

    private DesktopUninstallRequest request(Optional<DesktopUninstallDecisionType> decision) {
        Path install = temporary.resolve("WindowsToLinux");
        return new DesktopUninstallRequest(decision, install, install.resolve("data"));
    }

    private static final class RecordingUninstallPort implements DesktopUninstallPort {
        private final boolean invalidWorker;

        private final boolean invalidBoundary;

        private final boolean dataResidual;

        private final List<String> calls = new ArrayList<>();

        private RecordingUninstallPort(boolean invalidWorker, boolean invalidBoundary, boolean dataResidual) {
            this.invalidWorker = invalidWorker;
            this.invalidBoundary = invalidBoundary;
            this.dataResidual = dataResidual;
        }

        @Override
        public StepEvidence stopOwnedTasks(DesktopUninstallRequest request) {
            calls.add("stop");
            return new StepEvidence(true, true, List.of("application-owned tasks stopped"));
        }

        @Override
        public HandoffEvidence verifyIndependentWorker(DesktopUninstallHandoff handoff) {
            calls.add("handoff");
            boolean verified = !invalidWorker;
            return new HandoffEvidence(verified, verified, verified,
                List.of("external worker, process exit and handoff checked"));
        }

        @Override
        public BoundaryEvidence verifyManagedBoundaries(DesktopUninstallRequest request) {
            calls.add("verify");
            return new BoundaryEvidence(!invalidBoundary, !invalidBoundary, !invalidBoundary,
                List.of("jpackage, markers and credential namespace checked"));
        }

        @Override
        public RemovalEvidence removeProgram(DesktopUninstallRequest request) {
            calls.add("program");
            return removed("program removed while preserving data decision");
        }

        @Override
        public RemovalEvidence removeData(DesktopUninstallRequest request) {
            calls.add("data");
            if (dataResidual) {
                return new RemovalEvidence(false, false, List.of(request.dataRoot().resolve("locked.db").toString()),
                    List.of("locked database file could not be removed"));
            }
            return removed("managed data removed");
        }

        private static RemovalEvidence removed(String evidence) {
            return new RemovalEvidence(true, true, List.of(), List.of(evidence));
        }
    }
}
