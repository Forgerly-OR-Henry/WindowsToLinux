package gold.debug.windowstolinux.shared.backup.extension.adapter;

import gold.debug.windowstolinux.shared.backup.contract.spi.DatabaseBackupAdapter;
import gold.debug.windowstolinux.shared.backup.contract.spi.DatabaseBackupArtifact;
import gold.debug.windowstolinux.shared.backup.contract.spi.DatabaseBackupRequest;
import gold.debug.windowstolinux.shared.backup.contract.spi.DatabaseCompatibilityEvidence;
import gold.debug.windowstolinux.shared.backup.contract.spi.DatabaseCommitEvidence;
import gold.debug.windowstolinux.shared.backup.contract.spi.DatabaseOperationPort;
import gold.debug.windowstolinux.shared.backup.contract.spi.DatabaseRecoveryEvidence;
import gold.debug.windowstolinux.shared.backup.contract.spi.DatabaseRestoreEvidence;
import gold.debug.windowstolinux.shared.backup.contract.spi.DatabaseRestoreRequest;
import gold.debug.windowstolinux.shared.backup.contract.validation.BackupException;
import gold.debug.windowstolinux.shared.backup.contract.validation.BackupFailureType;
import gold.debug.windowstolinux.shared.backup.manifest.BackupConsistencyMode;
import gold.debug.windowstolinux.shared.backup.manifest.BackupDatabaseType;

import java.util.Objects;

/** SQLite policy that forbids copying an actively written database. / 禁止复制活跃写入数据库的 SQLite 策略。 */
public final class SqliteDatabaseAdapter implements DatabaseBackupAdapter {
    private final DatabaseOperationPort operations;

    /** Creates the SQLite adapter over one platform port. / 基于一个平台端口创建 SQLite 适配器。 */
    public SqliteDatabaseAdapter(DatabaseOperationPort operations) {
        this.operations = Objects.requireNonNull(operations, "operations");
    }

    @Override public BackupDatabaseType type() { return BackupDatabaseType.SQLITE; }

    @Override
    public DatabaseBackupArtifact backup(DatabaseBackupRequest request) throws BackupException {
        requireType(request);
        DatabaseCompatibilityEvidence evidence = operations.inspect(request);
        DatabaseAdapterEvidence.requireReady(evidence, type());
        BackupConsistencyMode mode;
        if (evidence.onlineBackupAvailable()) {
            mode = BackupConsistencyMode.SQLITE_ONLINE_BACKUP;
        } else if (request.applicationWritesStopped() && request.exclusiveWriterConfirmed()) {
            mode = BackupConsistencyMode.SQLITE_WRITES_STOPPED;
        } else {
            throw BackupException.create(BackupFailureType.DATABASE_PREFLIGHT_FAILED,
                    "SQLite online backup is unavailable and exclusive stopped writes were not confirmed");
        }
        return DatabaseAdapterEvidence.verifyArtifact(operations.export(request, mode), type(), mode, evidence);
    }

    @Override
    public DatabaseRestoreEvidence restore(DatabaseRestoreRequest request) throws BackupException {
        if (request.target().type() != type()) throw new IllegalArgumentException("SQLite restore target is required");
        DatabaseCompatibilityEvidence evidence = operations.inspect(new DatabaseBackupRequest(
                request.applicationId(), request.target(), true, true));
        DatabaseAdapterEvidence.requireRestoreCompatible(request, evidence, type());
        return operations.restoreCandidate(request);
    }

    @Override public DatabaseCommitEvidence commitCandidate(DatabaseRestoreRequest request) throws BackupException {
        requireRestoreType(request); return operations.commitCandidate(request);
    }

    @Override public DatabaseRecoveryEvidence recoverCandidate(DatabaseRestoreRequest request) throws BackupException {
        requireRestoreType(request); return operations.recoverCandidate(request);
    }

    @Override
    public void discardCandidate(DatabaseRestoreRequest request) throws BackupException {
        requireRestoreType(request);
        operations.discardCandidate(request);
    }

    private void requireRestoreType(DatabaseRestoreRequest request) {
        if (request.target().type() != type()) throw new IllegalArgumentException("SQLite restore target is required");
    }

    private void requireType(DatabaseBackupRequest request) {
        if (request.connection().type() != type()) throw new IllegalArgumentException("SQLite connection is required");
    }
}
