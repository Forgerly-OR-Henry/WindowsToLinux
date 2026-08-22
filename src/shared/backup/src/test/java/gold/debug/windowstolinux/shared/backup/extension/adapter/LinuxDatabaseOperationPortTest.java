package gold.debug.windowstolinux.shared.backup.extension.adapter;

import gold.debug.windowstolinux.shared.backup.contract.spi.DatabaseBackupRequest;
import gold.debug.windowstolinux.shared.backup.contract.spi.DatabaseConnectionProfile;
import gold.debug.windowstolinux.shared.backup.contract.validation.BackupException;
import gold.debug.windowstolinux.shared.backup.contract.validation.BackupFailureType;
import gold.debug.windowstolinux.shared.backup.extension.registry.DatabaseAdapterRegistry;
import gold.debug.windowstolinux.shared.backup.manifest.BackupConsistencyMode;
import gold.debug.windowstolinux.shared.backup.manifest.BackupDatabaseType;
import gold.debug.windowstolinux.shared.config.secretref.SecretReference;
import gold.debug.windowstolinux.shared.linux.error.LinuxOperationException;
import gold.debug.windowstolinux.shared.linux.error.LinuxOperationFailureType;
import gold.debug.windowstolinux.shared.linux.protocol.database.RemoteDatabasePort;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LinuxDatabaseOperationPortTest {
    @Test
    void mapsPublicLinuxEvidenceWithoutReversingModuleDependencies() throws Exception {
        RecordingRemote remote = new RecordingRemote(false);
        var adapter = DatabaseAdapterRegistry.defaults(remote).require(BackupDatabaseType.POSTGRESQL);

        var artifact = adapter.backup(request());

        assertEquals(BackupDatabaseType.POSTGRESQL, artifact.database().type());
        assertEquals(BackupConsistencyMode.POSTGRESQL_LOGICAL_DUMP, artifact.database().consistencyMode());
        assertEquals("db-0123456789abcdef0123456789abcdef", artifact.artifactId());
    }

    @Test
    void mapsLinuxPreflightFailureToBackupOwnedFailure() {
        var adapter = DatabaseAdapterRegistry.defaults(new RecordingRemote(true))
                .require(BackupDatabaseType.POSTGRESQL);

        BackupException failure = assertThrows(BackupException.class, () -> adapter.backup(request()));

        assertEquals(BackupFailureType.DATABASE_PREFLIGHT_FAILED.code(), failure.failure().code());
    }

    private static DatabaseBackupRequest request() {
        return new DatabaseBackupRequest("sample", new DatabaseConnectionProfile.Server(
                BackupDatabaseType.POSTGRESQL, "db.example.test", 5432, "sample", "sample_user",
                new SecretReference("db.password", 3), true), false, false);
    }

    private static final class RecordingRemote implements RemoteDatabasePort {
        private final boolean failInspection;

        private RecordingRemote(boolean failInspection) {
            this.failInspection = failInspection;
        }

        @Override
        public CompatibilityEvidence inspect(BackupRequest request) throws LinuxOperationException {
            if (failInspection) {
                throw LinuxOperationException.create(LinuxOperationFailureType.DATABASE_PREFLIGHT_FAILED,
                        "injected database preflight failure");
            }
            return new CompatibilityEvidence(DatabaseType.POSTGRESQL, "16.4", "16.4",
                    true, true, false, true, List.of("version and tool verified"));
        }

        @Override
        public BackupArtifact export(BackupRequest request, DatabaseConsistencyMode mode) {
            return new BackupArtifact("db-0123456789abcdef0123456789abcdef", 256, "a".repeat(64),
                    DatabaseType.POSTGRESQL, "db.example.test:5432/sample", "16.4", "16.4", mode,
                    List.of(), List.of("logical export verified"));
        }

        @Override
        public RestoreEvidence restoreCandidate(RestoreRequest request) {
            return new RestoreEvidence(request.candidateId(), request.candidateId(), true, true,
                    List.of("candidate schema readable"));
        }

        @Override public CommitEvidence commitCandidate(RestoreRequest request) {
            return new CommitEvidence(request.candidateId(), true, true, List.of("database committed"));
        }

        @Override public RecoveryEvidence recoverCandidate(RestoreRequest request) {
            return new RecoveryEvidence(request.candidateId(), true, true, true, List.of("database recovered"));
        }

        @Override public void discardCandidate(RestoreRequest request) { }

        @Override public void copyArtifact(BackupArtifact artifact, OutputStream destination) { }
        @Override public void stageArtifact(BackupArtifact artifact, InputStream source) { }
        @Override public void discardArtifact(BackupArtifact artifact) { }
    }
}
