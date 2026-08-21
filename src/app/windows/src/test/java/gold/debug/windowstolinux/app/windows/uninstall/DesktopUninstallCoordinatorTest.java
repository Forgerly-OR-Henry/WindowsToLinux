package gold.debug.windowstolinux.app.windows.uninstall;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DesktopUninstallCoordinatorTest {
    @TempDir
    Path temporary;

    @Test
    void missingDecisionDoesNotChooseOrDeleteAnything() {
        RecordingUninstallPort port = new RecordingUninstallPort(false, false);

        DesktopUninstallResult result = new DesktopUninstallCoordinator(port).uninstall(request(Optional.empty()));

        assertEquals(DesktopUninstallStatus.DECISION_REQUIRED, result.status());
        assertEquals("windows.uninstall.decision-required", result.failure().orElseThrow().code());
        assertTrue(port.calls.isEmpty());
    }

    @Test
    void explicitKeepChoiceRemovesProgramButRetainsDataAndCredentials() {
        RecordingUninstallPort port = new RecordingUninstallPort(false, false);
        DesktopUninstallRequest request = request(Optional.of(
                DesktopUninstallDecisionType.KEEP_DATA_AND_CREDENTIALS));

        DesktopUninstallResult result = new DesktopUninstallCoordinator(port).uninstall(request);

        assertEquals(DesktopUninstallStatus.SUCCEEDED_DATA_RETAINED, result.status());
        assertEquals(List.of("stop", "verify", "program"), port.calls);
        assertEquals(List.of(request.dataRoot().toString(), request.credentialNamespace()),
                result.intentionallyRetainedItems());
    }

    @Test
    void explicitDeleteChoiceStaysInsideAllVerifiedManagedBoundaries() {
        RecordingUninstallPort port = new RecordingUninstallPort(false, false);

        DesktopUninstallResult result = new DesktopUninstallCoordinator(port).uninstall(request(Optional.of(
                DesktopUninstallDecisionType.DELETE_DATA_AND_CREDENTIALS)));

        assertEquals(DesktopUninstallStatus.SUCCEEDED_DATA_DELETED, result.status());
        assertEquals(List.of("stop", "verify", "program", "data", "credentials"), port.calls);
        assertTrue(result.residualItems().isEmpty());
    }

    @Test
    void unverifiedMarkerStopsBeforeAnyRemoval() {
        RecordingUninstallPort port = new RecordingUninstallPort(true, false);

        DesktopUninstallResult result = new DesktopUninstallCoordinator(port).uninstall(request(Optional.of(
                DesktopUninstallDecisionType.DELETE_DATA_AND_CREDENTIALS)));

        assertEquals(DesktopUninstallStatus.PRECONDITION_REJECTED, result.status());
        assertEquals(List.of("stop", "verify"), port.calls);
    }

    @Test
    void incompleteDeletionReportsExactResidualItems() {
        RecordingUninstallPort port = new RecordingUninstallPort(false, true);
        DesktopUninstallRequest request = request(Optional.of(
                DesktopUninstallDecisionType.DELETE_DATA_AND_CREDENTIALS));

        DesktopUninstallResult result = new DesktopUninstallCoordinator(port).uninstall(request);

        assertEquals(DesktopUninstallStatus.COMPLETED_WITH_RESIDUALS, result.status());
        assertEquals(List.of(request.dataRoot().resolve("locked.db").toString()), result.residualItems());
        assertEquals("windows.uninstall.removal-incomplete", result.failure().orElseThrow().code());
    }

    private DesktopUninstallRequest request(Optional<DesktopUninstallDecisionType> decision) {
        Path install = temporary.resolve("WindowsToLinux");
        return new DesktopUninstallRequest(decision, install, install.resolve("data"),
                "WindowsToLinux/desktop");
    }

    private static final class RecordingUninstallPort implements DesktopUninstallPort {
        private final boolean invalidBoundary;
        private final boolean dataResidual;
        private final List<String> calls = new ArrayList<>();

        private RecordingUninstallPort(boolean invalidBoundary, boolean dataResidual) {
            this.invalidBoundary = invalidBoundary;
            this.dataResidual = dataResidual;
        }

        @Override
        public StepEvidence stopOwnedTasks(DesktopUninstallRequest request) {
            calls.add("stop");
            return new StepEvidence(true, true, List.of("application-owned tasks stopped"));
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
                return new RemovalEvidence(false, false,
                        List.of(request.dataRoot().resolve("locked.db").toString()),
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
