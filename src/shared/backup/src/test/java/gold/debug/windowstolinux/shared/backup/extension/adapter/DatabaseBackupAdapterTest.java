package gold.debug.windowstolinux.shared.backup.extension.adapter;

import gold.debug.windowstolinux.shared.backup.contract.spi.DatabaseBackupArtifact;
import gold.debug.windowstolinux.shared.backup.contract.spi.DatabaseBackupRequest;
import gold.debug.windowstolinux.shared.backup.contract.spi.DatabaseCompatibilityEvidence;
import gold.debug.windowstolinux.shared.backup.contract.spi.DatabaseConnectionProfile;
import gold.debug.windowstolinux.shared.backup.contract.spi.DatabaseOperationPort;
import gold.debug.windowstolinux.shared.backup.contract.spi.DatabaseRestoreEvidence;
import gold.debug.windowstolinux.shared.backup.contract.spi.DatabaseRestoreRequest;
import gold.debug.windowstolinux.shared.backup.contract.validation.BackupException;
import gold.debug.windowstolinux.shared.backup.contract.validation.BackupFailureType;
import gold.debug.windowstolinux.shared.backup.extension.registry.DatabaseAdapterRegistry;
import gold.debug.windowstolinux.shared.backup.manifest.BackupConsistencyMode;
import gold.debug.windowstolinux.shared.backup.manifest.BackupDatabase;
import gold.debug.windowstolinux.shared.backup.manifest.BackupDatabaseType;
import gold.debug.windowstolinux.shared.config.secretref.SecretReference;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.io.InputStream;
import java.io.OutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DatabaseBackupAdapterTest {
    @Test
    void sqliteUsesOnlineBackupWithoutStoppingActiveApplication() throws Exception {
        RecordingPort port = new RecordingPort(evidence(
                BackupDatabaseType.SQLITE, true, true, true));
        DatabaseBackupRequest request = new DatabaseBackupRequest(
                "sample", new DatabaseConnectionProfile.Sqlite("main", gold.debug.windowstolinux.shared.model.managed.ManagedStorageLocation.defaults(), "application.db"), false, false);

        DatabaseBackupArtifact artifact = new SqliteDatabaseAdapter(port).backup(request);

        assertEquals(BackupConsistencyMode.SQLITE_ONLINE_BACKUP, port.lastMode);
        assertEquals(BackupConsistencyMode.SQLITE_ONLINE_BACKUP, artifact.database().consistencyMode());
    }

    @Test
    void sqliteRejectsDirectCopyWhenOnlineBackupAndStoppedWritesAreUnproven() {
        RecordingPort port = new RecordingPort(evidence(
                BackupDatabaseType.SQLITE, true, false, true));
        DatabaseBackupRequest request = new DatabaseBackupRequest(
                "sample", new DatabaseConnectionProfile.Sqlite("main", gold.debug.windowstolinux.shared.model.managed.ManagedStorageLocation.defaults(), "application.db"), false, false);

        BackupException failure = assertThrows(BackupException.class,
                () -> new SqliteDatabaseAdapter(port).backup(request));

        assertEquals(BackupFailureType.DATABASE_PREFLIGHT_FAILED.code(), failure.failure().code());
    }

    @Test
    void postgresqlRequiresCompatibleLogicalDumpTool() {
        RecordingPort port = new RecordingPort(evidence(
                BackupDatabaseType.POSTGRESQL, false, false, true));
        DatabaseBackupRequest request = new DatabaseBackupRequest("sample",
                server(BackupDatabaseType.POSTGRESQL), false, false);

        BackupException failure = assertThrows(BackupException.class,
                () -> new PostgresqlDatabaseAdapter(port).backup(request));

        assertEquals(BackupFailureType.DATABASE_PREFLIGHT_FAILED.code(), failure.failure().code());
    }

    @Test
    void mysqlDistinguishesTransactionalAndNonTransactionalTables() throws Exception {
        RecordingPort transactionalPort = new RecordingPort(evidence(
                BackupDatabaseType.MYSQL, true, false, true));
        DatabaseBackupRequest active = new DatabaseBackupRequest(
                "sample", server(BackupDatabaseType.MYSQL), false, false);
        new MysqlDatabaseAdapter(BackupDatabaseType.MYSQL, transactionalPort).backup(active);
        assertEquals(BackupConsistencyMode.MYSQL_TRANSACTION_SNAPSHOT, transactionalPort.lastMode);

        RecordingPort mixedPort = new RecordingPort(evidence(
                BackupDatabaseType.MYSQL, true, false, false));
        BackupException failure = assertThrows(BackupException.class,
                () -> new MysqlDatabaseAdapter(BackupDatabaseType.MYSQL, mixedPort).backup(active));
        assertEquals(BackupFailureType.DATABASE_PREFLIGHT_FAILED.code(), failure.failure().code());

        DatabaseBackupRequest stopped = new DatabaseBackupRequest(
                "sample", server(BackupDatabaseType.MYSQL), true, true);
        new MysqlDatabaseAdapter(BackupDatabaseType.MYSQL, mixedPort).backup(stopped);
        assertEquals(BackupConsistencyMode.MYSQL_WRITES_STOPPED, mixedPort.lastMode);
    }

    @Test
    void restoreRemainsAnUnactivatedVerifiedCandidate() throws Exception {
        RecordingPort port = new RecordingPort(evidence(
                BackupDatabaseType.POSTGRESQL, true, false, true));
        DatabaseBackupArtifact artifact = new PostgresqlDatabaseAdapter(port).backup(new DatabaseBackupRequest(
                "sample", server(BackupDatabaseType.POSTGRESQL), false, false));
        DatabaseRestoreRequest restore = new DatabaseRestoreRequest(
                "sample", "sample-0123456789abcdef", server(BackupDatabaseType.POSTGRESQL), artifact);

        DatabaseRestoreEvidence restored = new PostgresqlDatabaseAdapter(port).restore(restore);

        assertEquals("sample-0123456789abcdef", restored.candidateId());
        assertEquals("candidate-token", restored.connectionToken());
    }

    @Test
    void restoreRejectsDifferentDatabaseMajorVersionBeforeMutation() {
        RecordingPort port = new RecordingPort(new DatabaseCompatibilityEvidence(
                BackupDatabaseType.POSTGRESQL, "17.1", "17.1", true,
                true, false, true, List.of("target version collected")));
        BackupDatabase source = new BackupDatabase(BackupDatabaseType.POSTGRESQL, "managed-database",
                "16.4", "16.4", BackupConsistencyMode.POSTGRESQL_LOGICAL_DUMP, List.of());
        DatabaseBackupArtifact artifact = new DatabaseBackupArtifact("artifact-1", 128,
                "1".repeat(64), source, List.of("logical export digest verified"));
        DatabaseRestoreRequest request = new DatabaseRestoreRequest(
                "sample", "sample-0123456789abcdef", server(BackupDatabaseType.POSTGRESQL), artifact);

        BackupException failure = assertThrows(BackupException.class,
                () -> new PostgresqlDatabaseAdapter(port).restore(request));

        assertEquals(BackupFailureType.DATABASE_PREFLIGHT_FAILED.code(), failure.failure().code());
    }

    @Test
    void registryIsClosedOverSupportedDatabaseFamilies() {
        DatabaseAdapterRegistry registry = DatabaseAdapterRegistry.defaults(new RecordingPort(evidence(
                BackupDatabaseType.SQLITE, true, true, true)));

        assertEquals(BackupDatabaseType.SQLITE, registry.require(BackupDatabaseType.SQLITE).type());
        assertEquals(BackupDatabaseType.POSTGRESQL, registry.require(BackupDatabaseType.POSTGRESQL).type());
        assertEquals(BackupDatabaseType.MARIADB, registry.require(BackupDatabaseType.MARIADB).type());
        assertThrows(IllegalArgumentException.class, () -> registry.require(BackupDatabaseType.NONE));
    }

    @Test
    void connectionProfilesRejectTraversalAndCredentialBearingHosts() {
        assertThrows(IllegalArgumentException.class,
                () -> new DatabaseConnectionProfile.Sqlite("main", gold.debug.windowstolinux.shared.model.managed.ManagedStorageLocation.defaults(), "../active.db"));
        assertThrows(IllegalArgumentException.class, () -> new DatabaseConnectionProfile.Server(
                BackupDatabaseType.POSTGRESQL, "user:password@db.example", 5432,
                "sample", "sample", new SecretReference("db.password", 1), true));
    }

    private static DatabaseConnectionProfile.Server server(BackupDatabaseType type) {
        return new DatabaseConnectionProfile.Server(type, "db.example.test",
                type == BackupDatabaseType.POSTGRESQL ? 5432 : 3306,
                "sample", "sample_user", new SecretReference("db.password", 1), true);
    }

    private static DatabaseCompatibilityEvidence evidence(
            BackupDatabaseType type, boolean compatible, boolean online, boolean transactional) {
        return new DatabaseCompatibilityEvidence(type, "16.4", "16.4", true,
                compatible, online, transactional, List.of("tool and engine versions collected"));
    }

    private static final class RecordingPort implements DatabaseOperationPort {
        private final DatabaseCompatibilityEvidence compatibility;
        private BackupConsistencyMode lastMode;

        private RecordingPort(DatabaseCompatibilityEvidence compatibility) {
            this.compatibility = compatibility;
        }

        @Override
        public DatabaseCompatibilityEvidence inspect(DatabaseBackupRequest request) {
            return compatibility;
        }

        @Override
        public DatabaseBackupArtifact export(DatabaseBackupRequest request, BackupConsistencyMode consistencyMode) {
            lastMode = consistencyMode;
            BackupDatabase database = new BackupDatabase(request.connection().type(), "managed-database",
                    compatibility.engineVersion(), compatibility.toolVersion(), consistencyMode, List.of());
            return new DatabaseBackupArtifact("artifact-1", 128,
                    "1".repeat(64), database, List.of("logical export digest verified"));
        }

        @Override
        public DatabaseRestoreEvidence restoreCandidate(DatabaseRestoreRequest request) {
            return new DatabaseRestoreEvidence(request.candidateId(), "candidate-token",
                    true, true, List.of("candidate database schema is readable"));
        }

        @Override
        public gold.debug.windowstolinux.shared.backup.contract.spi.DatabaseCommitEvidence commitCandidate(
                DatabaseRestoreRequest request) {
            return new gold.debug.windowstolinux.shared.backup.contract.spi.DatabaseCommitEvidence(
                    request.candidateId(), true, true, List.of("database committed"));
        }

        @Override
        public gold.debug.windowstolinux.shared.backup.contract.spi.DatabaseRecoveryEvidence recoverCandidate(
                DatabaseRestoreRequest request) {
            return new gold.debug.windowstolinux.shared.backup.contract.spi.DatabaseRecoveryEvidence(
                    request.candidateId(), true, true, true, List.of("database recovered"));
        }

        @Override
        public void discardCandidate(DatabaseRestoreRequest request) {
        }

        @Override
        public void copyArtifact(DatabaseBackupArtifact artifact, OutputStream destination) {
        }

        @Override
        public void stageArtifact(DatabaseBackupArtifact artifact, InputStream source) {
        }

        @Override
        public void discardArtifact(DatabaseBackupArtifact artifact) {
        }
    }
}
