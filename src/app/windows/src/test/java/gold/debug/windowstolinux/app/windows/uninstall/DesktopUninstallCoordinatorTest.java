package gold.debug.windowstolinux.app.windows.uninstall;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DesktopUninstallCoordinatorTest {
    @TempDir
    Path temporary;

    @Test
    void missingDecisionDoesNotChooseOrDeleteAnything() {
        RecordingUninstallPort port = new RecordingUninstallPort(false, false, false);

        DesktopUninstallPreparationResult result = new DesktopUninstallCoordinator(port)
                .prepare(request(Optional.empty()));

        assertEquals(DesktopUninstallPreparationStatus.DECISION_REQUIRED, result.status());
        assertEquals("windows.uninstall.decision-required", result.failure().orElseThrow().code());
        assertTrue(port.calls.isEmpty());
    }

    @Test
    void explicitKeepChoiceRemovesProgramButRetainsDataAndCredentials() {
        RecordingUninstallPort port = new RecordingUninstallPort(false, false, false);
        DesktopUninstallRequest request = request(Optional.of(DesktopUninstallDecisionType.KEEP_DATA_AND_CREDENTIALS));

        DesktopUninstallCoordinator coordinator = new DesktopUninstallCoordinator(port);
        DesktopUninstallPreparationResult preparation = coordinator.prepare(request);

        assertEquals(DesktopUninstallPreparationStatus.READY_FOR_HANDOFF, preparation.status());
        assertEquals(List.of("stop"), port.calls);
        DesktopUninstallResult result = coordinator.apply(preparation.handoff().orElseThrow());

        assertEquals(DesktopUninstallStatus.SUCCEEDED_DATA_RETAINED, result.status());
        assertEquals(List.of("stop", "handoff", "verify", "program"), port.calls);
        assertEquals(List.of(request.dataRoot().toString(), request.credentialNamespace()),
                result.intentionallyRetainedItems());
    }

    @Test
    void explicitDeleteChoiceStaysInsideAllVerifiedManagedBoundaries() {
        RecordingUninstallPort port = new RecordingUninstallPort(false, false, false);

        DesktopUninstallCoordinator coordinator = new DesktopUninstallCoordinator(port);
        DesktopUninstallPreparationResult preparation = coordinator
                .prepare(request(Optional.of(DesktopUninstallDecisionType.DELETE_DATA_AND_CREDENTIALS)));
        DesktopUninstallResult result = coordinator.apply(preparation.handoff().orElseThrow());

        assertEquals(DesktopUninstallStatus.SUCCEEDED_DATA_DELETED, result.status());
        assertEquals(List.of("stop", "handoff", "verify", "program", "data", "credentials"), port.calls);
        assertTrue(result.residualItems().isEmpty());
    }

    @Test
    void unverifiedMarkerStopsBeforeAnyRemoval() {
        RecordingUninstallPort port = new RecordingUninstallPort(false, true, false);

        DesktopUninstallCoordinator coordinator = new DesktopUninstallCoordinator(port);
        DesktopUninstallPreparationResult preparation = coordinator
                .prepare(request(Optional.of(DesktopUninstallDecisionType.DELETE_DATA_AND_CREDENTIALS)));
        DesktopUninstallResult result = coordinator.apply(preparation.handoff().orElseThrow());

        assertEquals(DesktopUninstallStatus.PRECONDITION_REJECTED, result.status());
        assertEquals(List.of("stop", "handoff", "verify"), port.calls);
    }

    @Test
    void unverifiedExternalWorkerNeverInspectsOrDeletesManagedTargets() {
        RecordingUninstallPort port = new RecordingUninstallPort(true, false, false);
        DesktopUninstallCoordinator coordinator = new DesktopUninstallCoordinator(port);
        DesktopUninstallPreparationResult preparation = coordinator
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

        DesktopUninstallCoordinator coordinator = new DesktopUninstallCoordinator(port);
        DesktopUninstallResult result = coordinator.apply(coordinator.prepare(request).handoff().orElseThrow());

        assertEquals(DesktopUninstallStatus.COMPLETED_WITH_RESIDUALS, result.status());
        assertEquals(List.of(request.dataRoot().resolve("locked.db").toString()), result.residualItems());
        assertEquals("windows.uninstall.removal-incomplete", result.failure().orElseThrow().code());
    }

    @Test
    void credentialBoundaryAcceptsOnlyTheFixedApplicationNamespace() {
        Path install = temporary.resolve("WindowsToLinux");
        assertThrows(IllegalArgumentException.class, () -> new DesktopUninstallRequest(Optional.empty(), install,
                install.resolve("data"), "WindowsToLinux/desktop"));
        assertEquals("WindowsToLinux/*",
                new DesktopUninstallRequest(Optional.empty(), install, install.resolve("data"), "WindowsToLinux/*")
                        .credentialNamespace());
    }

    private DesktopUninstallRequest request(Optional<DesktopUninstallDecisionType> decision) {
        Path install = temporary.resolve("WindowsToLinux");
        return new DesktopUninstallRequest(decision, install, install.resolve("data"), "WindowsToLinux/*");
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
            return new BoundaryEvidence(!invalidBoundary, !invalidBoundary, !invalidBoundary, !invalidBoundary,
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

        @Override
        public RemovalEvidence removeCredentials(DesktopUninstallRequest request) {
            calls.add("credentials");
            return removed("managed credential namespace removed");
        }

        private static RemovalEvidence removed(String evidence) {
            return new RemovalEvidence(true, true, List.of(), List.of(evidence));
        }
    }
}
