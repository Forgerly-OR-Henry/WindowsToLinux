package gold.debug.windowstolinux.app.windows.update;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
    void validationFailureRestoresProgramAndPreUpdateDatabaseTogether() throws Exception {
        RecordingUpdatePort port = new RecordingUpdatePort(UpdateFailureType.MIGRATION);

        DesktopUpdateCoordinator coordinator = new DesktopUpdateCoordinator(port);
        DesktopUpdateResult result = coordinator.apply(coordinator.prepare(verification()).handoff().orElseThrow());

        assertEquals(DesktopUpdateStatus.FAILED_ROLLED_BACK, result.status());
        assertEquals(List.of("quiesce", "backup", "handoff", "replace", "migrate", "rollback"), port.calls);
        assertEquals(DesktopUpdateState.ROLLBACK_VERIFIED, result.events().get(result.events().size() - 1).state());
    }

    @Test
    void incompletePairedRollbackRequiresManualRecovery() throws Exception {
        RecordingUpdatePort port = new RecordingUpdatePort(UpdateFailureType.ROLLBACK);

        DesktopUpdateCoordinator coordinator = new DesktopUpdateCoordinator(port);
        DesktopUpdateResult result = coordinator.apply(coordinator.prepare(verification()).handoff().orElseThrow());

        assertEquals(DesktopUpdateStatus.MANUAL_RECOVERY_REQUIRED, result.status());
        assertEquals("windows.update.rollback-failed", result.problem().orElseThrow().eventCode());
        assertEquals("REQUIRE_MANUAL_RECOVERY", result.problem().orElseThrow().context().get("recoveryAction"));
        assertEquals("FAILED", result.problem().orElseThrow().context().get("recoveryDisposition"));
        assertEquals(result.operationIdentity(), result.problem().orElseThrow().diagnosticId().orElseThrow());
    }

    @Test
    void unverifiedIndependentUpdaterNeverReplacesProgram() throws Exception {
        RecordingUpdatePort port = new RecordingUpdatePort(UpdateFailureType.HANDOFF);

        DesktopUpdateCoordinator coordinator = new DesktopUpdateCoordinator(port);
        DesktopUpdateResult result = coordinator.apply(coordinator.prepare(verification()).handoff().orElseThrow());

        assertEquals(DesktopUpdateStatus.PRECONDITION_REJECTED, result.status());
        assertEquals(List.of("quiesce", "backup", "handoff"), port.calls);
    }

    @Test
    void publicCapabilityFailureRetainsCategoryContextAndVerifiedRecovery() throws Exception {
        String causeId = "33333333-3333-3333-3333-333333333333";
        for (boolean collision : List.of(false, true)) {
            var port = new RecordingUpdatePort(UpdateFailureType.PUBLIC_PROBLEM);
            port.causeCollision = collision;
            var coordinator = new DesktopUpdateCoordinator(port);
            var preparation = coordinator.prepare(verification());
            var result = coordinator.apply(preparation.handoff().orElseThrow());
            var problem = result.problem().orElseThrow();
            assertEquals(DesktopUpdateStatus.FAILED_ROLLED_BACK, result.status());
            assertEquals(gold.debug.windowstolinux.appapi.task.ProblemCode.INCOMPATIBLE, problem.code());
            assertEquals("DATABASE_REVIEW_CHANGED", problem.eventCode());
            var sdk = org.junit.jupiter.api.Assertions.assertInstanceOf(
                gold.debug.windowstolinux.appapi.task.ApiException.class, port.capabilityFailure.getCause());
            assertEquals(causeId, sdk.problem().diagnosticId().orElseThrow().toString());
            assertEquals("target", problem.context().get("schema"));
            assertEquals("ROLLBACK", problem.context().get("recoveryAction"));
            assertEquals("SUCCEEDED", problem.context().get("recoveryDisposition"));
            assertEquals(result.operationIdentity(), problem.diagnosticId().orElseThrow());
            assertEquals(preparation.operationIdentity(), result.operationIdentity());
            assertEquals(collision ? "44444444-4444-4444-4444-444444444444" : causeId,
                problem.context().get("causeDiagnosticId"));
            if (collision)
                assertEquals(causeId, problem.context().get("causeDiagnosticId." + causeId + ".0"));
            assertEquals(List.of("quiesce", "backup", "handoff", "replace", "migrate", "rollback"), port.calls);
        }
    }

    @Test
    void explicitRollbackFailureKeepsLocalEventAndOriginalSdkCauseDiagnostic() throws Exception {
        var port = new RecordingUpdatePort(UpdateFailureType.PUBLIC_ROLLBACK_PROBLEM);
        var coordinator = new DesktopUpdateCoordinator(port);
        var preparation = coordinator.prepare(verification());
        var result = coordinator.apply(preparation.handoff().orElseThrow());
        var problem = result.problem().orElseThrow();
        assertEquals(DesktopUpdateStatus.MANUAL_RECOVERY_REQUIRED, result.status());
        assertEquals(gold.debug.windowstolinux.appapi.task.ProblemCode.IO_FAILURE, problem.code());
        assertEquals("windows.update.rollback-failed", problem.eventCode());
        assertEquals("rollback", problem.context().get("phase"));
        assertEquals("windows.error.updateRollbackFailed", problem.context().get("messageKey"));
        assertEquals("REQUIRE_MANUAL_RECOVERY", problem.context().get("recoveryAction"));
        assertEquals("FAILED", problem.context().get("recoveryDisposition"));
        assertEquals("77777777-7777-7777-7777-777777777777", problem.context().get("causeDiagnosticId"));
        assertEquals(preparation.operationIdentity(), problem.diagnosticId().orElseThrow());
        var sdk = org.junit.jupiter.api.Assertions.assertInstanceOf(
            gold.debug.windowstolinux.appapi.task.ApiException.class, port.capabilityFailure.getCause());
        assertEquals("OLD_PROGRAM_RESTORE_DENIED", sdk.problem().eventCode());
        assertEquals("previous-program", sdk.problem().context().get("target"));
        assertEquals("Rollback cleanup evidence", port.capabilityFailure.getSuppressed()[0].getMessage());
        assertEquals(List.of("quiesce", "backup", "handoff", "replace", "migrate", "rollback"), port.calls);
    }

    private DesktopUpdateVerification verification() throws Exception {
        Path packageFile = Files.writeString(temporary.resolve("update.bin"), "verified");
        return new DesktopUpdateVerification(packageFile, "release-2", DesktopReleaseVersion.parse("2.0.0"),
            DesktopArchitectureType.X86_64, 8, "a".repeat(64), Instant.parse("2026-08-22T00:00:00Z"),
            List.of("signature, digest, version and architecture verified"));
    }

    private enum UpdateFailureType {
        NONE, HANDOFF, MIGRATION, ROLLBACK, PUBLIC_PROBLEM, PUBLIC_ROLLBACK_PROBLEM
    }

    private static final class RecordingUpdatePort implements DesktopUpdatePort {
        private final UpdateFailureType failure;

        private final List<String> calls = new ArrayList<>();

        private boolean causeCollision;

        private DesktopUpdateException capabilityFailure;

        private RecordingUpdatePort(UpdateFailureType failure) {
            this.failure = failure;
        }

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
        public HandoffEvidence verifyIndependentUpdater(DesktopUpdateVerification update, BackupEvidence backup) {
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
        public StepEvidence validateDatabase(DesktopUpdateVerification update, BackupEvidence backup)
            throws DesktopUpdateException {
            calls.add("migrate");
            if (failure == UpdateFailureType.PUBLIC_PROBLEM) {
                var sdk = new gold.debug.windowstolinux.appapi.task.ApiException(
                    new gold.debug.windowstolinux.appapi.task.Problem(
                        gold.debug.windowstolinux.appapi.task.ProblemCode.INCOMPATIBLE,
                        "DATABASE_REVIEW_CHANGED", "Reviewed schema differs",
                        causeCollision
                            ? java.util.Map.of("schema", "target", "causeDiagnosticId",
                            "44444444-4444-4444-4444-444444444444")
                            : java.util.Map.of("schema", "target"),
                        java.util.Optional
                            .of(java.util.UUID.fromString("33333333-3333-3333-3333-333333333333"))));
                capabilityFailure = DesktopUpdateException.create(DesktopUpdateFailureType.TRANSACTION_FAILED,
                    "Wrapped public schema refusal", sdk);
                throw capabilityFailure;
            }
            return step(failure != UpdateFailureType.MIGRATION && failure != UpdateFailureType.ROLLBACK
                && failure != UpdateFailureType.PUBLIC_ROLLBACK_PROBLEM, "SQLite validation attempted");
        }

        @Override
        public StepEvidence startAndVerify(DesktopUpdateVerification update, BackupEvidence backup) {
            calls.add("health");
            return step(true, "new version healthy");
        }

        @Override
        public RollbackEvidence rollbackProgramAndDatabase(BackupEvidence backup) throws DesktopUpdateException {
            calls.add("rollback");
            if (failure == UpdateFailureType.PUBLIC_ROLLBACK_PROBLEM) {
                var sdk = new gold.debug.windowstolinux.appapi.task.ApiException(
                    new gold.debug.windowstolinux.appapi.task.Problem(
                        gold.debug.windowstolinux.appapi.task.ProblemCode.ACCESS_DENIED,
                        "OLD_PROGRAM_RESTORE_DENIED", "Original restore refusal",
                        java.util.Map.of("target", "previous-program"), java.util.Optional
                        .of(java.util.UUID.fromString("77777777-7777-7777-7777-777777777777"))));
                capabilityFailure = DesktopUpdateException.create(DesktopUpdateFailureType.ROLLBACK_FAILED,
                    "Rollback capability refused", sdk);
                capabilityFailure.addSuppressed(new java.io.IOException("Rollback cleanup evidence"));
                throw capabilityFailure;
            }
            boolean verified = failure != UpdateFailureType.ROLLBACK;
            return new RollbackEvidence(verified, verified, verified,
                List.of("old program and pre-update SQLite restored together"));
        }

        private static StepEvidence step(boolean verified, String evidence) {
            return new StepEvidence(verified, verified, List.of(evidence));
        }
    }
}
