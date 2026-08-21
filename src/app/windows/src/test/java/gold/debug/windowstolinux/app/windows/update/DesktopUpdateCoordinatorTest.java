package gold.debug.windowstolinux.app.windows.update;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DesktopUpdateCoordinatorTest {
    @TempDir
    Path temporary;

    @Test
    void commitsOnlyAfterIndependentHandoffDatabaseMigrationAndHealth() throws Exception {
        RecordingUpdatePort port = new RecordingUpdatePort(UpdateFailureType.NONE);

        DesktopUpdateCoordinator coordinator = new DesktopUpdateCoordinator(port);
        DesktopUpdatePreparationResult preparation = coordinator.prepare(verification());

        assertEquals(DesktopUpdatePreparationStatus.READY_FOR_HANDOFF, preparation.status());
        assertEquals(List.of("quiesce", "backup"), port.calls);
        DesktopUpdateResult result = coordinator.apply(preparation.handoff().orElseThrow());

        assertEquals(DesktopUpdateStatus.SUCCEEDED, result.status());
        assertEquals(List.of("quiesce", "backup", "handoff", "replace", "migrate", "health"), port.calls);
        assertEquals("backup-1", result.rollbackBackupToken().orElseThrow());
    }

    @Test
    void migrationFailureRestoresProgramAndPreMigrationDatabaseTogether() throws Exception {
        RecordingUpdatePort port = new RecordingUpdatePort(UpdateFailureType.MIGRATION);

        DesktopUpdateCoordinator coordinator = new DesktopUpdateCoordinator(port);
        DesktopUpdateResult result = coordinator.apply(
                coordinator.prepare(verification()).handoff().orElseThrow());

        assertEquals(DesktopUpdateStatus.FAILED_ROLLED_BACK, result.status());
        assertEquals(List.of("quiesce", "backup", "handoff", "replace", "migrate", "rollback"), port.calls);
        assertEquals(DesktopUpdateState.ROLLBACK_VERIFIED,
                result.events().get(result.events().size() - 1).state());
    }

    @Test
    void incompletePairedRollbackRequiresManualRecovery() throws Exception {
        RecordingUpdatePort port = new RecordingUpdatePort(UpdateFailureType.ROLLBACK);

        DesktopUpdateCoordinator coordinator = new DesktopUpdateCoordinator(port);
        DesktopUpdateResult result = coordinator.apply(
                coordinator.prepare(verification()).handoff().orElseThrow());

        assertEquals(DesktopUpdateStatus.MANUAL_RECOVERY_REQUIRED, result.status());
        assertEquals("windows.update.rollback-failed", result.failure().orElseThrow().code());
    }

    @Test
    void unverifiedIndependentUpdaterNeverReplacesProgram() throws Exception {
        RecordingUpdatePort port = new RecordingUpdatePort(UpdateFailureType.HANDOFF);

        DesktopUpdateCoordinator coordinator = new DesktopUpdateCoordinator(port);
        DesktopUpdateResult result = coordinator.apply(
                coordinator.prepare(verification()).handoff().orElseThrow());

        assertEquals(DesktopUpdateStatus.PRECONDITION_REJECTED, result.status());
        assertEquals(List.of("quiesce", "backup", "handoff"), port.calls);
    }

    private DesktopUpdateVerification verification() throws Exception {
        Path packageFile = Files.writeString(temporary.resolve("update.bin"), "verified");
        return new DesktopUpdateVerification(packageFile, "release-2", DesktopReleaseVersion.parse("2.0.0"),
                DesktopArchitectureType.X86_64, 8, "a".repeat(64), Instant.parse("2026-08-22T00:00:00Z"),
                List.of("signature, digest, version and architecture verified"));
    }

    private enum UpdateFailureType { NONE, HANDOFF, MIGRATION, ROLLBACK }

    private static final class RecordingUpdatePort implements DesktopUpdatePort {
        private final UpdateFailureType failure;
        private final List<String> calls = new ArrayList<>();

        private RecordingUpdatePort(UpdateFailureType failure) { this.failure = failure; }

        @Override
        public StepEvidence quiesceTasks() {
            calls.add("quiesce");
            return step(true, "tasks quiesced");
        }

        @Override
        public BackupEvidence backupCurrent(DesktopUpdateVerification update) {
            calls.add("backup");
            return new BackupEvidence("backup-1", true, true, true, true,
                    List.of("program and SQLite backup verified"));
        }

        @Override
        public HandoffEvidence verifyIndependentUpdater(
                DesktopUpdateVerification update, BackupEvidence backup) {
            calls.add("handoff");
            boolean verified = failure != UpdateFailureType.HANDOFF;
            return new HandoffEvidence(verified, verified, verified,
                    List.of("independent updater, process state and handoff checked"));
        }

        @Override
        public StepEvidence replaceProgram(DesktopUpdateVerification update, BackupEvidence backup) {
            calls.add("replace");
            return step(true, "signed program replaced");
        }

        @Override
        public StepEvidence migrateDatabase(DesktopUpdateVerification update, BackupEvidence backup) {
            calls.add("migrate");
            return step(failure != UpdateFailureType.MIGRATION && failure != UpdateFailureType.ROLLBACK,
                    "SQLite migration attempted");
        }

        @Override
        public StepEvidence startAndVerify(DesktopUpdateVerification update, BackupEvidence backup) {
            calls.add("health");
            return step(true, "new version healthy");
        }

        @Override
        public RollbackEvidence rollbackProgramAndDatabase(BackupEvidence backup) {
            calls.add("rollback");
            boolean verified = failure != UpdateFailureType.ROLLBACK;
            return new RollbackEvidence(verified, verified, verified,
                    List.of("old program and pre-migration SQLite restored together"));
        }

        private static StepEvidence step(boolean verified, String evidence) {
            return new StepEvidence(verified, verified, List.of(evidence));
        }
    }
}
