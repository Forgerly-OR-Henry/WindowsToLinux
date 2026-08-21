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
import gold.debug.windowstolinux.shared.backup.manifest.BackupConsistencyMode;
import gold.debug.windowstolinux.shared.backup.manifest.BackupDatabase;
import gold.debug.windowstolinux.shared.backup.manifest.BackupDatabaseType;
import gold.debug.windowstolinux.shared.linux.error.LinuxOperationException;
import gold.debug.windowstolinux.shared.linux.protocol.database.RemoteDatabasePort;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.Objects;

/** Adapts the public Linux database contract to backup-owned policy types. / 将 Linux 公共数据库契约适配为备份策略类型。 */
public final class LinuxDatabaseOperationPort implements DatabaseOperationPort {
    private final RemoteDatabasePort remote;

    /** Creates a backup port over one typed remote database capability. / 基于一个类型化远程数据库能力创建备份端口。 */
    public LinuxDatabaseOperationPort(RemoteDatabasePort remote) {
        this.remote = Objects.requireNonNull(remote, "remote");
    }

    @Override
    public DatabaseCompatibilityEvidence inspect(DatabaseBackupRequest request) throws BackupException {
        try {
            RemoteDatabasePort.CompatibilityEvidence evidence = remote.inspect(toRemote(request));
            return new DatabaseCompatibilityEvidence(type(evidence.type()), evidence.engineVersion(),
                    evidence.toolVersion(), evidence.toolAvailable(), evidence.engineVersionCompatible(),
                    evidence.onlineBackupAvailable(), evidence.allTablesTransactional(), evidence.evidence());
        } catch (LinuxOperationException exception) {
            throw BackupException.create(BackupFailureType.DATABASE_PREFLIGHT_FAILED,
                    "remote database inspection failed", exception);
        }
    }

    @Override
    public DatabaseBackupArtifact export(DatabaseBackupRequest request, BackupConsistencyMode consistencyMode)
            throws BackupException {
        try {
            return fromRemote(remote.export(toRemote(request), mode(consistencyMode)));
        } catch (LinuxOperationException exception) {
            throw BackupException.create(BackupFailureType.DATABASE_BACKUP_FAILED,
                    "remote database export failed", exception);
        }
    }

    @Override
    public DatabaseRestoreEvidence restoreCandidate(DatabaseRestoreRequest request) throws BackupException {
        try {
            RemoteDatabasePort.RestoreEvidence evidence = remote.restoreCandidate(new RemoteDatabasePort.RestoreRequest(
                    request.applicationId(), request.candidateId(), connection(request.target()), artifact(request.artifact())));
            return new DatabaseRestoreEvidence(evidence.candidateId(), evidence.connectionToken(),
                    evidence.integrityVerified(), evidence.schemaReadable(), evidence.evidence());
        } catch (LinuxOperationException exception) {
            throw BackupException.create(BackupFailureType.DATABASE_RESTORE_FAILED,
                    "remote database candidate restore failed", exception);
        }
    }

    @Override
    public void copyArtifact(DatabaseBackupArtifact artifact, OutputStream destination) throws BackupException {
        try {
            remote.copyArtifact(artifact(artifact), destination);
        } catch (LinuxOperationException exception) {
            throw BackupException.create(BackupFailureType.DATABASE_BACKUP_FAILED,
                    "remote database artifact read failed", exception);
        }
    }

    @Override
    public void stageArtifact(DatabaseBackupArtifact artifact, InputStream source) throws BackupException {
        try {
            remote.stageArtifact(artifact(artifact), source);
        } catch (LinuxOperationException exception) {
            throw BackupException.create(BackupFailureType.DATABASE_RESTORE_FAILED,
                    "remote database artifact staging failed", exception);
        }
    }

    @Override
    public void discardArtifact(DatabaseBackupArtifact artifact) throws BackupException {
        try {
            remote.discardArtifact(artifact(artifact));
        } catch (LinuxOperationException exception) {
            throw BackupException.create(BackupFailureType.CLEANUP_FAILED,
                    "remote database artifact cleanup failed", exception);
        }
    }

    private static RemoteDatabasePort.BackupRequest toRemote(DatabaseBackupRequest request) {
        return new RemoteDatabasePort.BackupRequest(request.applicationId(), connection(request.connection()),
                request.applicationWritesStopped(), request.exclusiveWriterConfirmed());
    }

    private static RemoteDatabasePort.ConnectionProfile connection(DatabaseConnectionProfile profile) {
        if (profile instanceof DatabaseConnectionProfile.Sqlite sqlite) {
            return new RemoteDatabasePort.ConnectionProfile.Sqlite(sqlite.relativePath());
        }
        DatabaseConnectionProfile.Server server = (DatabaseConnectionProfile.Server) profile;
        return new RemoteDatabasePort.ConnectionProfile.Server(type(server.type()), server.host(), server.port(),
                server.database(), server.username(), server.passwordReference().identifier(),
                server.passwordReference().revision(), server.tlsRequired());
    }

    private static DatabaseBackupArtifact fromRemote(RemoteDatabasePort.BackupArtifact artifact) {
        BackupDatabase database = new BackupDatabase(type(artifact.type()), artifact.reference(),
                artifact.engineVersion(), artifact.toolVersion(), mode(artifact.consistencyMode()), artifact.limitations());
        return new DatabaseBackupArtifact(artifact.artifactId(), artifact.byteCount(), artifact.sha256(),
                database, artifact.evidence());
    }

    private static RemoteDatabasePort.BackupArtifact artifact(DatabaseBackupArtifact artifact) {
        BackupDatabase database = artifact.database();
        return new RemoteDatabasePort.BackupArtifact(artifact.artifactId(), artifact.byteCount(), artifact.sha256(),
                type(database.type()), database.reference(), database.engineVersion(), database.toolVersion(),
                mode(database.consistencyMode()), database.limitations(), artifact.evidence());
    }

    private static RemoteDatabasePort.DatabaseType type(BackupDatabaseType type) {
        return switch (type) {
            case SQLITE -> RemoteDatabasePort.DatabaseType.SQLITE;
            case POSTGRESQL -> RemoteDatabasePort.DatabaseType.POSTGRESQL;
            case MYSQL -> RemoteDatabasePort.DatabaseType.MYSQL;
            case MARIADB -> RemoteDatabasePort.DatabaseType.MARIADB;
            case NONE -> throw new IllegalArgumentException("database operation requires a concrete type");
        };
    }

    private static BackupDatabaseType type(RemoteDatabasePort.DatabaseType type) {
        return switch (type) {
            case SQLITE -> BackupDatabaseType.SQLITE;
            case POSTGRESQL -> BackupDatabaseType.POSTGRESQL;
            case MYSQL -> BackupDatabaseType.MYSQL;
            case MARIADB -> BackupDatabaseType.MARIADB;
        };
    }

    private static RemoteDatabasePort.DatabaseConsistencyMode mode(BackupConsistencyMode mode) {
        return switch (mode) {
            case SQLITE_ONLINE_BACKUP -> RemoteDatabasePort.DatabaseConsistencyMode.SQLITE_ONLINE_BACKUP;
            case SQLITE_WRITES_STOPPED -> RemoteDatabasePort.DatabaseConsistencyMode.SQLITE_WRITES_STOPPED;
            case POSTGRESQL_LOGICAL_DUMP -> RemoteDatabasePort.DatabaseConsistencyMode.POSTGRESQL_LOGICAL_DUMP;
            case MYSQL_TRANSACTION_SNAPSHOT -> RemoteDatabasePort.DatabaseConsistencyMode.MYSQL_TRANSACTION_SNAPSHOT;
            case MYSQL_WRITES_STOPPED -> RemoteDatabasePort.DatabaseConsistencyMode.MYSQL_WRITES_STOPPED;
            case NOT_APPLICABLE -> throw new IllegalArgumentException("database export requires a consistency mode");
        };
    }

    private static BackupConsistencyMode mode(RemoteDatabasePort.DatabaseConsistencyMode mode) {
        return switch (mode) {
            case SQLITE_ONLINE_BACKUP -> BackupConsistencyMode.SQLITE_ONLINE_BACKUP;
            case SQLITE_WRITES_STOPPED -> BackupConsistencyMode.SQLITE_WRITES_STOPPED;
            case POSTGRESQL_LOGICAL_DUMP -> BackupConsistencyMode.POSTGRESQL_LOGICAL_DUMP;
            case MYSQL_TRANSACTION_SNAPSHOT -> BackupConsistencyMode.MYSQL_TRANSACTION_SNAPSHOT;
            case MYSQL_WRITES_STOPPED -> BackupConsistencyMode.MYSQL_WRITES_STOPPED;
        };
    }
}
