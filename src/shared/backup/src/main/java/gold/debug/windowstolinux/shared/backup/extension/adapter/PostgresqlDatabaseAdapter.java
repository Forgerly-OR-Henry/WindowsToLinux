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
import gold.debug.windowstolinux.shared.backup.manifest.BackupConsistencyMode;
import gold.debug.windowstolinux.shared.backup.manifest.BackupDatabaseType;

import java.util.Objects;

/** PostgreSQL policy that permits only a version-compatible controlled logical dump. / 仅允许版本兼容受控逻辑导出的 PostgreSQL 策略。 */
public final class PostgresqlDatabaseAdapter implements DatabaseBackupAdapter {
    private final DatabaseOperationPort operations;

    /** Creates the PostgreSQL adapter over one platform port. / 基于一个平台端口创建 PostgreSQL 适配器。 */
    public PostgresqlDatabaseAdapter(DatabaseOperationPort operations) {
        this.operations = Objects.requireNonNull(operations, "operations");
    }

    @Override public BackupDatabaseType type() { return BackupDatabaseType.POSTGRESQL; }

    @Override
    public DatabaseBackupArtifact backup(DatabaseBackupRequest request) throws BackupException {
        requireType(request);
        DatabaseCompatibilityEvidence evidence = operations.inspect(request);
        DatabaseAdapterEvidence.requireReady(evidence, type());
        BackupConsistencyMode mode = BackupConsistencyMode.POSTGRESQL_LOGICAL_DUMP;
        return DatabaseAdapterEvidence.verifyArtifact(operations.export(request, mode), type(), mode, evidence);
    }

    @Override
    public DatabaseRestoreEvidence restore(DatabaseRestoreRequest request) throws BackupException {
        if (request.target().type() != type()) throw new IllegalArgumentException("PostgreSQL restore target is required");
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
        if (request.target().type() != type()) throw new IllegalArgumentException("PostgreSQL restore target is required");
    }

    private void requireType(DatabaseBackupRequest request) {
        if (request.connection().type() != type()) throw new IllegalArgumentException("PostgreSQL connection is required");
    }
}
