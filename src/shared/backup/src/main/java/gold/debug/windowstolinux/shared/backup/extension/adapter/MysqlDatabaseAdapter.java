package gold.debug.windowstolinux.shared.backup.extension.adapter;

import gold.debug.windowstolinux.shared.backup.contract.spi.DatabaseBackupAdapter;
import gold.debug.windowstolinux.shared.backup.contract.spi.DatabaseBackupArtifact;
import gold.debug.windowstolinux.shared.backup.contract.spi.DatabaseBackupRequest;
import gold.debug.windowstolinux.shared.backup.contract.spi.DatabaseCompatibilityEvidence;
import gold.debug.windowstolinux.shared.backup.contract.spi.DatabaseOperationPort;
import gold.debug.windowstolinux.shared.backup.contract.spi.DatabaseRestoreEvidence;
import gold.debug.windowstolinux.shared.backup.contract.spi.DatabaseRestoreRequest;
import gold.debug.windowstolinux.shared.backup.contract.validation.BackupException;
import gold.debug.windowstolinux.shared.backup.contract.validation.BackupFailureType;
import gold.debug.windowstolinux.shared.backup.manifest.BackupConsistencyMode;
import gold.debug.windowstolinux.shared.backup.manifest.BackupDatabaseType;

import java.util.Objects;

/** MySQL-compatible policy with explicit transactional-table limitations. / 显式处理事务表限制的 MySQL 兼容策略。 */
public final class MysqlDatabaseAdapter implements DatabaseBackupAdapter {
    private final BackupDatabaseType databaseType;
    private final DatabaseOperationPort operations;

    /** Creates one MySQL or MariaDB adapter. / 创建一个 MySQL 或 MariaDB 适配器。 */
    public MysqlDatabaseAdapter(BackupDatabaseType databaseType, DatabaseOperationPort operations) {
        if (databaseType != BackupDatabaseType.MYSQL && databaseType != BackupDatabaseType.MARIADB) {
            throw new IllegalArgumentException("MySQL adapter requires MySQL or MariaDB");
        }
        this.databaseType = databaseType;
        this.operations = Objects.requireNonNull(operations, "operations");
    }

    @Override public BackupDatabaseType type() { return databaseType; }

    @Override
    public DatabaseBackupArtifact backup(DatabaseBackupRequest request) throws BackupException {
        requireType(request);
        DatabaseCompatibilityEvidence evidence = operations.inspect(request);
        DatabaseAdapterEvidence.requireReady(evidence, type());
        BackupConsistencyMode mode;
        if (evidence.allTablesTransactional()) {
            mode = BackupConsistencyMode.MYSQL_TRANSACTION_SNAPSHOT;
        } else if (request.applicationWritesStopped() && request.exclusiveWriterConfirmed()) {
            mode = BackupConsistencyMode.MYSQL_WRITES_STOPPED;
        } else {
            throw BackupException.create(BackupFailureType.DATABASE_PREFLIGHT_FAILED,
                    "non-transactional tables require confirmed exclusive stopped writes");
        }
        return DatabaseAdapterEvidence.verifyArtifact(operations.export(request, mode), type(), mode, evidence);
    }

    @Override
    public DatabaseRestoreEvidence restore(DatabaseRestoreRequest request) throws BackupException {
        if (request.target().type() != type()) throw new IllegalArgumentException("matching MySQL-compatible target is required");
        DatabaseCompatibilityEvidence evidence = operations.inspect(new DatabaseBackupRequest(
                request.applicationId(), request.target(), true, true));
        DatabaseAdapterEvidence.requireRestoreCompatible(request, evidence, type());
        return operations.restoreCandidate(request);
    }

    @Override
    public void discardCandidate(DatabaseRestoreRequest request) throws BackupException {
        if (request.target().type() != type()) throw new IllegalArgumentException("matching MySQL-compatible target is required");
        operations.discardCandidate(request);
    }

    private void requireType(DatabaseBackupRequest request) {
        if (request.connection().type() != type()) throw new IllegalArgumentException("matching MySQL-compatible connection is required");
    }
}
