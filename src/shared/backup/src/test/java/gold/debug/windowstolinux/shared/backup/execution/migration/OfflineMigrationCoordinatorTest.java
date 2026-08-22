package gold.debug.windowstolinux.shared.backup.execution.migration;

import gold.debug.windowstolinux.shared.backup.contract.spi.OfflineMigrationPort;
import gold.debug.windowstolinux.shared.backup.contract.spi.OfflineMigrationRequest;
import gold.debug.windowstolinux.shared.backup.contract.validation.BackupException;
import gold.debug.windowstolinux.shared.backup.contract.validation.BackupFailureType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OfflineMigrationCoordinatorTest {
    @Test
    void preparesVerifiedTargetThenStopsForManualTrafficSwitch() {
        RecordingPort port = new RecordingPort(MigrationFailureType.NONE);

        OfflineMigrationResult result = new OfflineMigrationCoordinator(port).prepare(request(true));

        assertEquals(OfflineMigrationStatus.READY_FOR_MANUAL_TRAFFIC_SWITCH, result.status());
        assertTrue(result.sourceWritesStopped());
        assertTrue(result.targetCandidateReady());
        assertFalse(result.externalTrafficSwitched());
        assertTrue(result.sourceRetained());
        assertEquals("sample-" + "a".repeat(16), result.targetCandidateId().orElseThrow());
        assertEquals(List.of("preflight", "initial", "stop", "final", "target"), port.calls);
        assertEquals(OfflineMigrationState.MANUAL_TRAFFIC_SWITCH_REQUIRED,
                result.events().get(result.events().size() - 1).state());
    }

    @Test
    void missingStopWindowApprovalRejectsBeforeAnyPortCall() {
        RecordingPort port = new RecordingPort(MigrationFailureType.NONE);

        OfflineMigrationResult result = new OfflineMigrationCoordinator(port).prepare(request(false));

        assertEquals(OfflineMigrationStatus.PRECONDITION_REJECTED, result.status());
        assertTrue(port.calls.isEmpty());
        assertFalse(result.sourceWritesStopped());
        assertTrue(result.sourceRetained());
    }

    @Test
    void finalSyncFailureCleansTargetAndRecoversSource() {
        RecordingPort port = new RecordingPort(MigrationFailureType.FINAL_SYNC);

        OfflineMigrationResult result = new OfflineMigrationCoordinator(port).prepare(request(true));

        assertEquals(OfflineMigrationStatus.FAILED_SOURCE_RECOVERED, result.status());
        assertEquals(List.of("preflight", "initial", "stop", "final", "discard", "recover-source"), port.calls);
        assertFalse(result.targetCandidateReady());
        assertFalse(result.sourceWritesStopped());
    }

    @Test
    void initialSyncFailureIsReportedAsCleanedTargetMutation() {
        RecordingPort port = new RecordingPort(MigrationFailureType.INITIAL_SYNC);

        OfflineMigrationResult result = new OfflineMigrationCoordinator(port).prepare(request(true));

        assertEquals(OfflineMigrationStatus.FAILED_TARGET_CLEANED, result.status());
        assertEquals(List.of("preflight", "initial", "discard"), port.calls);
        assertFalse(result.sourceWritesStopped());
    }

    @Test
    void stoppedSourceWithoutRecoveryTokenRequiresManualRecovery() {
        RecordingPort port = new RecordingPort(MigrationFailureType.STOP_THROWS);

        OfflineMigrationResult result = new OfflineMigrationCoordinator(port).prepare(request(true));

        assertEquals(OfflineMigrationStatus.MANUAL_RECOVERY_REQUIRED, result.status());
        assertEquals("backup.migration.recovery-failed", result.failure().orElseThrow().code());
        assertEquals(List.of("preflight", "initial", "stop", "discard"), port.calls);
    }

    @Test
    void unverifiedSourceRecoveryRequiresManualAction() {
        RecordingPort port = new RecordingPort(MigrationFailureType.SOURCE_RECOVERY);

        OfflineMigrationResult result = new OfflineMigrationCoordinator(port).prepare(request(true));

        assertEquals(OfflineMigrationStatus.MANUAL_RECOVERY_REQUIRED, result.status());
        assertEquals(List.of("preflight", "initial", "stop", "final", "discard", "recover-source"), port.calls);
    }

    @Test
    void endpointsMustDifferBeforeTheFinalBackupDigestExists() {
        assertThrows(IllegalArgumentException.class, () -> new OfflineMigrationRequest(
                "migration-1", "sample", "same", "same", 100, true));
    }

    private OfflineMigrationRequest request(boolean approved) {
        return new OfflineMigrationRequest("migration-1", "sample", "source", "target", 100, approved);
    }

    private enum MigrationFailureType { NONE, INITIAL_SYNC, FINAL_SYNC, STOP_THROWS, SOURCE_RECOVERY }

    private static final class RecordingPort implements OfflineMigrationPort {
        private final MigrationFailureType failure;
        private final List<String> calls = new ArrayList<>();

        private RecordingPort(MigrationFailureType failure) {
            this.failure = failure;
        }

        @Override
        public TargetPreflightEvidence preflightTarget(OfflineMigrationRequest request) {
            calls.add("preflight");
            return new TargetPreflightEvidence(true, true, true, 1000,
                    List.of("target ownership, ports, compatibility and space verified"));
        }

        @Override
        public SyncEvidence initialSync(OfflineMigrationRequest request) throws BackupException {
            calls.add("initial");
            if (failure == MigrationFailureType.INITIAL_SYNC) {
                throw BackupException.create(BackupFailureType.MIGRATION_SYNC_FAILED,
                        "initial synchronization failed after target candidate creation");
            }
            return new SyncEvidence(100, "b".repeat(64), true, false,
                    List.of("immutable initial baseline synchronized"));
        }

        @Override
        public SourceQuiesceEvidence stopSourceWrites(OfflineMigrationRequest request) throws BackupException {
            calls.add("stop");
            if (failure == MigrationFailureType.STOP_THROWS) {
                throw BackupException.create(BackupFailureType.MIGRATION_QUIESCE_FAILED,
                        "source connection was interrupted while stopping writes");
            }
            return new SourceQuiesceEvidence(true, true, "source-snapshot-1",
                    List.of("source writes stopped and no active writer remains"));
        }

        @Override
        public SyncEvidence finalSync(
                OfflineMigrationRequest request, SyncEvidence initial, SourceQuiesceEvidence quiesced)
                throws BackupException {
            calls.add("final");
            if (failure == MigrationFailureType.FINAL_SYNC || failure == MigrationFailureType.SOURCE_RECOVERY) {
                throw BackupException.create(BackupFailureType.MIGRATION_SYNC_FAILED,
                        "final synchronization failed");
            }
            return new SyncEvidence(100, "a".repeat(64), true, true,
                    List.of("stopped-write final snapshot synchronized and verified"));
        }

        @Override
        public TargetCandidateEvidence restoreAndVerifyTarget(
                OfflineMigrationRequest request, SyncEvidence finalSync) {
            calls.add("target");
            return new TargetCandidateEvidence(request.applicationId() + "-" + finalSync.contentSha256().substring(0, 16),
                    true, true, true,
                    List.of("target components and application are healthy without external traffic changes"));
        }

        @Override
        public RecoveryEvidence discardTargetCandidate(OfflineMigrationRequest request) {
            calls.add("discard");
            return new RecoveryEvidence(true, true, List.of("owned target candidate removed and verified absent"));
        }

        @Override
        public RecoveryEvidence recoverSource(
                OfflineMigrationRequest request, SourceQuiesceEvidence quiesced) {
            calls.add("recover-source");
            boolean verified = failure != MigrationFailureType.SOURCE_RECOVERY;
            return new RecoveryEvidence(verified, verified,
                    List.of("source recovery attempted and runtime state observed"));
        }
    }
}
