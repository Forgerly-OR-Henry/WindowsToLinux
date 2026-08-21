package gold.debug.windowstolinux.shared.backup.restore;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import gold.debug.windowstolinux.shared.backup.contract.spi.DatabaseBackupAdapter;
import gold.debug.windowstolinux.shared.backup.contract.spi.DatabaseBackupArtifact;
import gold.debug.windowstolinux.shared.backup.contract.spi.DatabaseBackupRequest;
import gold.debug.windowstolinux.shared.backup.contract.spi.DatabaseConnectionProfile;
import gold.debug.windowstolinux.shared.backup.contract.spi.DatabaseRestoreEvidence;
import gold.debug.windowstolinux.shared.backup.contract.spi.DatabaseRestoreRequest;
import gold.debug.windowstolinux.shared.backup.contract.spi.RestoreCandidatePort;
import gold.debug.windowstolinux.shared.backup.contract.spi.RestoreCandidateRequest;
import gold.debug.windowstolinux.shared.backup.contract.validation.BackupArchiveValidation;
import gold.debug.windowstolinux.shared.backup.contract.validation.BackupException;
import gold.debug.windowstolinux.shared.backup.contract.validation.BackupFailureType;
import gold.debug.windowstolinux.shared.backup.contract.validation.BackupProvenanceStatus;
import gold.debug.windowstolinux.shared.backup.extension.registry.DatabaseAdapterRegistry;
import gold.debug.windowstolinux.shared.backup.manifest.BackupDatabase;
import gold.debug.windowstolinux.shared.backup.manifest.BackupDatabaseType;
import gold.debug.windowstolinux.shared.backup.manifest.BackupComponent;
import gold.debug.windowstolinux.shared.backup.manifest.BackupComponentRuntime;
import gold.debug.windowstolinux.shared.backup.manifest.BackupConsistencyMode;
import gold.debug.windowstolinux.shared.backup.manifest.BackupHealthCheck;
import gold.debug.windowstolinux.shared.backup.manifest.BackupIdentity;
import gold.debug.windowstolinux.shared.backup.manifest.BackupInventory;
import gold.debug.windowstolinux.shared.backup.manifest.BackupManifest;
import gold.debug.windowstolinux.shared.backup.manifest.BackupManifestCodec;
import gold.debug.windowstolinux.shared.backup.manifest.BackupMember;
import gold.debug.windowstolinux.shared.backup.manifest.BackupMemberKind;
import gold.debug.windowstolinux.shared.backup.manifest.BackupRuntime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BackupRestoreCoordinatorTest {
    @TempDir
    Path temporary;

    @Test
    void commitsOnlyAfterFilesAndBothHealthLevelsPass() throws Exception {
        RecordingCandidatePort port = new RecordingCandidatePort(false, false);

        BackupRestoreResult result = coordinator(port).restore(plan("x86_64"));

        assertEquals(BackupRestoreStatus.SUCCEEDED, result.status());
        assertTrue(result.failure().isEmpty());
        assertEquals("release-active", result.activeReleaseToken().orElseThrow());
        assertEquals(List.of(RestoreCandidateState.PREFLIGHT_VERIFIED, RestoreCandidateState.FILES_STAGED,
                        RestoreCandidateState.COMPONENTS_HEALTHY, RestoreCandidateState.APPLICATION_HEALTHY,
                        RestoreCandidateState.COMMITTED),
                result.events().stream().map(RestoreCandidateEvent::state).toList());
        assertFalse(port.recoveryCalled);
    }

    @Test
    void componentHealthFailurePreservesExistingRelease() throws Exception {
        RecordingCandidatePort port = new RecordingCandidatePort(true, false);

        BackupRestoreResult result = coordinator(port).restore(plan("x86_64"));

        assertEquals(BackupRestoreStatus.FAILED_EXISTING_PRESERVED, result.status());
        assertTrue(result.failure().isPresent());
        assertTrue(port.recoveryCalled);
        assertEquals(RestoreCandidateState.RECOVERY_VERIFIED,
                result.events().get(result.events().size() - 1).state());
    }

    @Test
    void incompatibleArchitectureStopsBeforeMutation() throws Exception {
        RecordingCandidatePort port = new RecordingCandidatePort(false, false);

        BackupRestoreResult result = coordinator(port).restore(plan("arm64"));

        assertEquals(BackupRestoreStatus.FAILED_EXISTING_PRESERVED, result.status());
        assertEquals(0, port.stageCalls);
        assertFalse(port.recoveryCalled);
    }

    @Test
    void schemaV3StopsBeforeAnyAutomaticRestoreMutation() throws Exception {
        RecordingCandidatePort port = new RecordingCandidatePort(false, false);
        BackupRestorePlan current = plan("x86_64");
        BackupManifest legacy = legacy(current.validation().manifest());
        BackupArchiveValidation validation = new BackupArchiveValidation(
                current.validation().archiveSha256(), legacy, current.validation().verifiedBytes(),
                current.validation().provenanceStatus());
        BackupRestorePlan legacyPlan = new BackupRestorePlan(validation,
                new BackupRestoreCandidate(current.candidate().root(), legacy, current.candidate().extractedBytes()),
                current.localCandidateParent(), current.candidateId(), current.materialKind(), current.target(),
                current.databaseRestore());

        BackupRestoreResult result = coordinator(port).restore(legacyPlan);

        assertEquals(BackupRestoreStatus.FAILED_EXISTING_PRESERVED, result.status());
        assertEquals("backup.restore.preflight-failed", result.failure().orElseThrow().code());
        assertEquals(0, port.stageCalls);
        assertFalse(port.recoveryCalled);
    }

    @Test
    void insufficientTwoCopySpaceStopsBeforeMutation() throws Exception {
        RecordingCandidatePort port = new RecordingCandidatePort(false, false);
        BackupRestorePlan original = plan("x86_64");
        BackupRestorePlan constrained = withTarget(original, target(
                "ubuntu", "24.04", "x86_64", BackupDatabaseType.NONE, "none", 15, true, false),
                RestoreMaterialKind.BINARY_RELEASE);

        BackupRestoreResult result = coordinator(port).restore(constrained);

        assertEquals(BackupRestoreStatus.FAILED_EXISTING_PRESERVED, result.status());
        assertEquals(0, port.stageCalls);
        assertFalse(port.recoveryCalled);
    }

    @Test
    void crossDistributionBinaryRequiresExplicitExperimentalApproval() throws Exception {
        RecordingCandidatePort rejectedPort = new RecordingCandidatePort(false, false);
        BackupRestorePlan original = plan("x86_64");
        RestoreTargetProfile unapproved = target(
                "debian", "13", "x86_64", BackupDatabaseType.NONE, "none", 1024, true, false);

        BackupRestoreResult rejected = coordinator(rejectedPort).restore(
                withTarget(original, unapproved, RestoreMaterialKind.BINARY_RELEASE));

        assertEquals(BackupRestoreStatus.FAILED_EXISTING_PRESERVED, rejected.status());
        assertEquals(0, rejectedPort.stageCalls);

        RecordingCandidatePort approvedPort = new RecordingCandidatePort(false, false);
        RestoreTargetProfile approved = target(
                "debian", "13", "x86_64", BackupDatabaseType.NONE, "none", 1024, true, true);
        BackupRestoreResult accepted = coordinator(approvedPort).restore(
                withTarget(original, approved, RestoreMaterialKind.BINARY_RELEASE));

        assertEquals(BackupRestoreStatus.SUCCEEDED, accepted.status());
        assertEquals(1, approvedPort.stageCalls);
    }

    @Test
    void sourceRestoreMayRebuildForAnotherArchitectureWhenCapabilityIsVerified() throws Exception {
        RecordingCandidatePort port = new RecordingCandidatePort(false, false);
        BackupRestorePlan original = plan("x86_64");
        RestoreTargetProfile target = target(
                "debian", "13", "arm64", BackupDatabaseType.NONE, "none", 1024, true, false);

        BackupRestoreResult result = coordinator(port).restore(
                withTarget(original, target, RestoreMaterialKind.SOURCE_REBUILD));

        assertEquals(BackupRestoreStatus.SUCCEEDED, result.status());
        assertEquals(1, port.stageCalls);
    }

    @Test
    void unverifiableRecoveryRequiresManualAction() throws Exception {
        RecordingCandidatePort port = new RecordingCandidatePort(true, true);

        BackupRestoreResult result = coordinator(port).restore(plan("x86_64"));

        assertEquals(BackupRestoreStatus.MANUAL_RECOVERY_REQUIRED, result.status());
        assertTrue(result.failure().isPresent());
        assertEquals("backup.restore.recovery-failed", result.failure().orElseThrow().code());
    }

    @Test
    void databaseRestoreFailureStillDiscardsThePossiblyCreatedCandidate() throws Exception {
        RecordingCandidatePort port = new RecordingCandidatePort(false, false);
        FailingDatabaseAdapter database = new FailingDatabaseAdapter();

        BackupRestoreResult result = new BackupRestoreCoordinator(new BackupRestorePreflight(), port,
                new DatabaseAdapterRegistry(List.of(database))).restore(databasePlan());

        assertEquals(BackupRestoreStatus.FAILED_EXISTING_PRESERVED, result.status());
        assertTrue(database.restoreCalled);
        assertTrue(database.discardCalled);
        assertTrue(port.recoveryCalled);
    }

    private BackupRestoreCoordinator coordinator(RestoreCandidatePort port) {
        return new BackupRestoreCoordinator(new BackupRestorePreflight(), port,
                new DatabaseAdapterRegistry(List.of()));
    }

    private BackupRestorePlan plan(String targetArchitecture) throws Exception {
        Path parent = Files.createDirectory(temporary.resolve("candidates-" + targetArchitecture));
        Path root = Files.createDirectory(parent.resolve("sample-bbbbbbbbbbbbbbbb"));
        List<BackupMember> members = members();
        BackupHealthCheck health = BackupHealthCheck.tcp(8080, 30, 5);
        BackupComponent component = component(health);
        BackupInventory inventory = new BackupInventory(
                List.of("releases/release.json"), List.of("config/application.json"), List.of(),
                List.of("data/content"), List.of(), BackupDatabase.none(),
                new BackupIdentity("sample", "source-server", "/opt/windowstolinux/apps/sample",
                        BackupInventory.computeReleaseSetSha256(List.of(component))),
                List.of("runtime/sample.service"), List.of(component), "sample", health,
                new BackupRuntime("ubuntu", "24.04", "systemd", "255", "x86_64", List.of("systemd")),
                List.of());
        BackupManifest manifest = BackupManifest.create(Instant.parse("2026-08-21T00:00:00Z"),
                "sample", inventory, members);
        BackupArchiveValidation validation = new BackupArchiveValidation("b".repeat(64), manifest, 8,
                BackupProvenanceStatus.NOT_PRESENT);
        BackupRestoreCandidate candidate = new BackupRestoreCandidate(root, manifest, 8);
        RestoreTargetProfile target = target(
                "ubuntu", "24.04", targetArchitecture, BackupDatabaseType.NONE, "none", 1024, true, false);
        return new BackupRestorePlan(validation, candidate, parent, "sample-bbbbbbbbbbbbbbbb",
                RestoreMaterialKind.BINARY_RELEASE, target, Optional.empty());
    }

    private BackupRestorePlan databasePlan() throws Exception {
        Path parent = Files.createDirectory(temporary.resolve("candidates-database"));
        String candidateId = "sample-bbbbbbbbbbbbbbbb";
        Path root = Files.createDirectory(parent.resolve(candidateId));
        List<BackupMember> members = members();
        BackupDatabase database = new BackupDatabase(BackupDatabaseType.SQLITE, "data/application.db", "3.46",
                "3.46", BackupConsistencyMode.SQLITE_ONLINE_BACKUP, List.of());
        BackupHealthCheck health = BackupHealthCheck.tcp(8080, 30, 5);
        BackupComponent component = component(health);
        BackupInventory inventory = new BackupInventory(
                List.of("releases/release.json"), List.of("config/application.json"), List.of(),
                List.of("data/content"), List.of(), database,
                new BackupIdentity("sample", "source-server", "/opt/windowstolinux/apps/sample",
                        BackupInventory.computeReleaseSetSha256(List.of(component))),
                List.of("runtime/sample.service"), List.of(component), "sample", health,
                new BackupRuntime("ubuntu", "24.04", "systemd", "255", "x86_64", List.of("systemd")),
                List.of());
        BackupManifest manifest = BackupManifest.create(Instant.parse("2026-08-21T00:00:00Z"),
                "sample", inventory, members);
        BackupArchiveValidation validation = new BackupArchiveValidation("b".repeat(64), manifest, 8,
                BackupProvenanceStatus.NOT_PRESENT);
        DatabaseBackupArtifact artifact = new DatabaseBackupArtifact("artifact-1", 128, "c".repeat(64),
                database, List.of("consistent SQLite artifact"));
        DatabaseRestoreRequest restore = new DatabaseRestoreRequest("sample", candidateId,
                new DatabaseConnectionProfile.Sqlite("data/application.db"), artifact);
        RestoreTargetProfile target = target(
                "ubuntu", "24.04", "x86_64", BackupDatabaseType.SQLITE, "3.46", 1024, true, false);
        return new BackupRestorePlan(validation, new BackupRestoreCandidate(root, manifest, 8), parent,
                candidateId, RestoreMaterialKind.BINARY_RELEASE, target, Optional.of(restore));
    }

    private BackupRestorePlan withTarget(
            BackupRestorePlan plan, RestoreTargetProfile target, RestoreMaterialKind materialKind) {
        return new BackupRestorePlan(plan.validation(), plan.candidate(), plan.localCandidateParent(),
                plan.candidateId(), materialKind, target, plan.databaseRestore());
    }

    private static List<BackupMember> members() {
        return List.of(
                new BackupMember("releases/release.json", 0, "a".repeat(64), BackupMemberKind.RELEASE),
                new BackupMember("config/application.json", 8, "a".repeat(64), BackupMemberKind.CONFIGURATION),
                new BackupMember("runtime/sample.service", 0, "a".repeat(64), BackupMemberKind.RUNTIME));
    }

    private static BackupComponent component(BackupHealthCheck health) {
        return new BackupComponent("sample", "sample", "d".repeat(64),
                "releases/release.json", "config/application.json", "runtime/sample.service", List.of(),
                new BackupComponentRuntime.SpringBoot(health), "e".repeat(64), List.of());
    }

    private static BackupManifest legacy(BackupManifest current) throws Exception {
        BackupManifestCodec codec = new BackupManifestCodec();
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode root = (ObjectNode) mapper.readTree(codec.write(current));
        root.put("schemaVersion", BackupManifest.LEGACY_SCHEMA_VERSION);
        ObjectNode inventory = (ObjectNode) root.get("inventory");
        ObjectNode identity = (ObjectNode) inventory.get("identity");
        identity.remove("releaseSetSha256");
        identity.put("releaseIdentity", "release-source");
        ArrayNode legacySecrets = mapper.createArrayNode();
        for (JsonNode reference : inventory.withArray("secretReferences")) {
            legacySecrets.add(reference.get("identifier").asText());
        }
        inventory.set("secretReferences", legacySecrets);
        for (JsonNode value : inventory.withArray("components")) {
            ObjectNode component = (ObjectNode) value;
            component.remove("releaseSha256");
            component.remove("secretReferences");
        }
        return codec.read(mapper.writeValueAsBytes(root));
    }

    private RestoreTargetProfile target(
            String distroId,
            String distroVersion,
            String architecture,
            BackupDatabaseType databaseType,
            String databaseVersion,
            long availableBytes,
            boolean sourceRebuildSupported,
            boolean binaryExperimentApproved
    ) {
        return new RestoreTargetProfile(
                "target-server", distroId, distroVersion, architecture, "systemd", "255",
                databaseType, databaseVersion, availableBytes, true, true, false, sourceRebuildSupported,
                true, binaryExperimentApproved, List.of("target facts collected by managed helper"));
    }

    private static final class RecordingCandidatePort implements RestoreCandidatePort {
        private final boolean componentFailure;
        private final boolean recoveryFailure;
        private int stageCalls;
        private boolean recoveryCalled;

        private RecordingCandidatePort(boolean componentFailure, boolean recoveryFailure) {
            this.componentFailure = componentFailure;
            this.recoveryFailure = recoveryFailure;
        }

        @Override
        public FileEvidence stageFiles(RestoreCandidateRequest request) {
            stageCalls++;
            return new FileEvidence(request.candidateId(), "candidate-token", request.verifiedBytes(),
                    true, true, true, List.of("isolated files staged and verified"));
        }

        @Override
        public HealthEvidence verifyComponents(
                RestoreCandidateRequest request, FileEvidence files, Optional<String> databaseToken) {
            return new HealthEvidence(!componentFailure, List.of("component health checked"));
        }

        @Override
        public HealthEvidence verifyApplication(
                RestoreCandidateRequest request, FileEvidence files, Optional<String> databaseToken) {
            return new HealthEvidence(true, List.of("whole application health checked"));
        }

        @Override
        public CommitEvidence commit(
                RestoreCandidateRequest request, FileEvidence files, Optional<String> databaseToken) {
            return new CommitEvidence(true, true, "release-active", List.of("candidate committed atomically"));
        }

        @Override
        public RecoveryEvidence recoverExisting(RestoreCandidateRequest request, Optional<FileEvidence> files)
                throws BackupException {
            recoveryCalled = true;
            return new RecoveryEvidence(!recoveryFailure, !recoveryFailure,
                    List.of("failed candidate removed and existing release verified"));
        }
    }

    private static final class FailingDatabaseAdapter implements DatabaseBackupAdapter {
        private boolean restoreCalled;
        private boolean discardCalled;

        @Override public BackupDatabaseType type() { return BackupDatabaseType.SQLITE; }

        @Override
        public DatabaseBackupArtifact backup(DatabaseBackupRequest request) {
            throw new UnsupportedOperationException("backup is not used by this test");
        }

        @Override
        public DatabaseRestoreEvidence restore(DatabaseRestoreRequest request) throws BackupException {
            restoreCalled = true;
            throw BackupException.create(BackupFailureType.DATABASE_RESTORE_FAILED,
                    "database candidate creation failed after mutation began");
        }

        @Override
        public void discardCandidate(DatabaseRestoreRequest request) {
            discardCalled = true;
        }
    }
}
