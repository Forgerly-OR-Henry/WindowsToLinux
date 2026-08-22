package gold.debug.windowstolinux.shared.backup.extension.adapter;

import gold.debug.windowstolinux.shared.backup.contract.spi.RestoreCandidatePort;
import gold.debug.windowstolinux.shared.backup.contract.spi.RestoreCandidateRequest;
import gold.debug.windowstolinux.shared.backup.contract.validation.BackupException;
import gold.debug.windowstolinux.shared.backup.manifest.BackupComponent;
import gold.debug.windowstolinux.shared.backup.manifest.BackupComponentRuntime;
import gold.debug.windowstolinux.shared.backup.manifest.BackupDatabase;
import gold.debug.windowstolinux.shared.backup.manifest.BackupHealthCheck;
import gold.debug.windowstolinux.shared.backup.manifest.BackupIdentity;
import gold.debug.windowstolinux.shared.backup.manifest.BackupInventory;
import gold.debug.windowstolinux.shared.backup.manifest.BackupManifest;
import gold.debug.windowstolinux.shared.backup.manifest.BackupMember;
import gold.debug.windowstolinux.shared.backup.manifest.BackupMemberKind;
import gold.debug.windowstolinux.shared.backup.manifest.BackupRuntime;
import gold.debug.windowstolinux.shared.deploy.contract.spi.RestoreDeploymentPort;
import gold.debug.windowstolinux.shared.deploy.contract.spi.RestoreDeploymentRequest;
import gold.debug.windowstolinux.shared.config.secretref.SecretReference;
import gold.debug.windowstolinux.shared.linux.protocol.RemoteStepResult;
import gold.debug.windowstolinux.shared.linux.protocol.restore.RemoteRestoreFilePort;
import gold.debug.windowstolinux.shared.linux.protocol.restore.RemoteRestoreStagingEvidence;
import gold.debug.windowstolinux.shared.linux.protocol.restore.RemoteRestoreStagingRequest;
import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSpecification;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LinuxRestoreCandidateAdapterTest {
    @TempDir Path temporary;

    @Test
    void mapsExactMembersTypedRuntimeHealthAndCommitEvidence() throws Exception {
        RecordingFiles files = new RecordingFiles();
        RecordingDeployments deployments = new RecordingDeployments(false);
        LinuxRestoreCandidateAdapter adapter = new LinuxRestoreCandidateAdapter(files, deployments);
        RestoreCandidateRequest request = request();

        RestoreCandidatePort.FileEvidence staged = adapter.stageFiles(request);
        RestoreCandidatePort.HealthEvidence components = adapter.verifyComponents(
                request, staged, Optional.of("database-token"));
        RestoreCandidatePort.HealthEvidence application = adapter.verifyApplication(
                request, staged, Optional.of("database-token"));
        RestoreCandidatePort.CommitEvidence committed = adapter.commit(
                request, staged, Optional.of("database-token"));

        assertEquals(4, files.staging.members().size());
        assertEquals(request.verifiedBytes(), staged.stagedBytes());
        assertTrue(components.healthy());
        assertTrue(application.healthy());
        assertTrue(committed.previousReleaseRetained());
        assertEquals(new DeploymentRuntimeSpecification.NodeService(22,
                        request.manifest().inventory().components().getFirst().runtime().healthCheck().toHealthCheck()),
                deployments.request.components().getFirst().runtime());
        assertEquals("sample", deployments.request.applicationHealthComponentId());
        assertEquals("target-server", deployments.request.targetServerId());
        assertEquals(request.manifest().inventory().identity().releaseSetSha256().orElseThrow(),
                deployments.request.releaseSetSha256());
        assertEquals(request.manifest().inventory().secretReferences(), deployments.request.secretReferences());
        assertEquals("d".repeat(64), deployments.request.components().getFirst().releaseSha256());
    }

    @Test
    void stillDiscardsRemoteCandidateWhenDeployRecoveryThrows() throws Exception {
        RecordingFiles files = new RecordingFiles();
        LinuxRestoreCandidateAdapter adapter = new LinuxRestoreCandidateAdapter(
                files, new RecordingDeployments(true));
        RestoreCandidateRequest request = request();
        RestoreCandidatePort.FileEvidence staged = adapter.stageFiles(request);

        BackupException failure = assertThrows(BackupException.class,
                () -> adapter.recoverExisting(request, Optional.of(staged)));

        assertEquals("backup.restore.recovery-failed", failure.failure().code());
        assertTrue(files.discardCalled);
    }

    @Test
    void rejectsNonActivatableManifestBeforeRemoteStaging() throws Exception {
        RecordingFiles files = new RecordingFiles();
        LinuxRestoreCandidateAdapter adapter = new LinuxRestoreCandidateAdapter(
                files, new RecordingDeployments(false));
        RestoreCandidateRequest exact = request();
        BackupManifest withoutSecrets = new BackupManifest(
                exact.manifest().format(), exact.manifest().schemaVersion(), exact.manifest().createdAtUtc(),
                exact.manifest().applicationId(), exact.manifest().inventory(),
                exact.manifest().members().stream()
                        .filter(member -> member.kind() != BackupMemberKind.ENCRYPTED_SECRETS).toList(),
                exact.manifest().provenance());
        RestoreCandidateRequest request = new RestoreCandidateRequest(
                withoutSecrets, exact.localCandidateRoot(), exact.archiveSha256(), exact.verifiedBytes(),
                exact.candidateId(), exact.targetServerId(), exact.rebuildFromSource());

        BackupException failure = assertThrows(BackupException.class, () -> adapter.stageFiles(request));

        assertEquals("backup.restore.preflight-failed", failure.failure().code());
        assertNull(files.staging);
    }

    private RestoreCandidateRequest request() throws Exception {
        String digest = "b".repeat(64);
        Path root = Files.createDirectory(temporary.resolve("sample-" + digest.substring(0, 16)));
        BackupHealthCheck health = BackupHealthCheck.tcp(8080, 30, 5);
        List<SecretReference> secrets = List.of(
                new SecretReference("rotated-token", 1), new SecretReference("rotated-token", 2));
        BackupComponent component = new BackupComponent("sample", "sample", "c".repeat(64),
                "releases/sample.json", "config/sample.json", "runtime/sample.service", List.of(),
                new BackupComponentRuntime.NodeService(22, health), "d".repeat(64), secrets);
        List<BackupMember> members = List.of(
                new BackupMember("releases/sample.json", 0, "a".repeat(64), BackupMemberKind.RELEASE),
                new BackupMember("config/sample.json", 0, "a".repeat(64), BackupMemberKind.CONFIGURATION),
                new BackupMember("runtime/sample.service", 0, "a".repeat(64), BackupMemberKind.RUNTIME),
                new BackupMember("secrets.enc", 0, "a".repeat(64), BackupMemberKind.ENCRYPTED_SECRETS));
        BackupInventory inventory = new BackupInventory(
                List.of("releases/sample.json"), List.of("config/sample.json"), secrets, List.of(), List.of(),
                BackupDatabase.none(),
                new BackupIdentity("sample", "source-server", "/var/lib/windowstolinux/apps/sample",
                        BackupInventory.computeReleaseSetSha256(List.of(component))),
                List.of("runtime/sample.service"), List.of(component), "sample", health,
                new BackupRuntime("ubuntu", "24.04", "systemd", "255", "x86_64", List.of("systemd")),
                List.of());
        BackupManifest manifest = BackupManifest.create(
                Instant.parse("2026-08-22T00:00:00Z"), "sample", inventory, members);
        return new RestoreCandidateRequest(manifest, root, digest, 0,
                "sample-" + digest.substring(0, 16), "target-server", false);
    }

    private static final class RecordingFiles implements RemoteRestoreFilePort {
        private RemoteRestoreStagingRequest staging;
        private boolean discardCalled;

        @Override
        public RemoteRestoreStagingEvidence stageRestoreFiles(RemoteRestoreStagingRequest request) {
            staging = request;
            return new RemoteRestoreStagingEvidence(request.candidateId(),
                    "/var/lib/windowstolinux/work/" + request.candidateId() + "/mutable/restore",
                    request.archiveSha256().substring(0, 32), request.expectedBytes(), true, true, true,
                    List.of("staged and read back"));
        }

        @Override
        public RemoteStepResult discardRestoreFiles(RemoteRestoreStagingRequest request) {
            discardCalled = true;
            return new RemoteStepResult(true, false, "candidate discarded");
        }
    }

    private static final class RecordingDeployments implements RestoreDeploymentPort {
        private final boolean failRecovery;
        private RestoreDeploymentRequest request;

        private RecordingDeployments(boolean failRecovery) {
            this.failRecovery = failRecovery;
        }

        @Override
        public HealthEvidence verifyComponents(RestoreDeploymentRequest request, Optional<String> databaseToken) {
            this.request = request;
            return new HealthEvidence(true, List.of("components healthy"));
        }

        @Override
        public HealthEvidence verifyApplication(RestoreDeploymentRequest request, Optional<String> databaseToken) {
            this.request = request;
            return new HealthEvidence(true, List.of("application healthy"));
        }

        @Override
        public HealthEvidence prepareCommit(RestoreDeploymentRequest request, Optional<String> databaseToken) {
            this.request = request; return new HealthEvidence(true, List.of("commit boundary prepared"));
        }

        @Override
        public CommitEvidence commit(RestoreDeploymentRequest request, Optional<String> databaseToken) {
            this.request = request;
            return new CommitEvidence(true, true, "release-active", List.of("committed with rollback point"));
        }

        @Override
        public HealthEvidence quiesceForRecovery(RestoreDeploymentRequest request) {
            return new HealthEvidence(true, List.of("recovery quiesced"));
        }

        @Override
        public RecoveryEvidence recoverExisting(RestoreDeploymentRequest request) {
            if (failRecovery) throw new IllegalStateException("rollback failed");
            return new RecoveryEvidence(true, List.of("existing release verified"));
        }
    }
}
