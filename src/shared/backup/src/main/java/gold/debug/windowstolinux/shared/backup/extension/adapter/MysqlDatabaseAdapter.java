package gold.debug.windowstolinux.shared.backup.extension.adapter;

import java.util.Objects;

import gold.debug.windowstolinux.shared.backup.contract.spi.DatabaseBackupAdapter;
import gold.debug.windowstolinux.shared.backup.contract.spi.DatabaseBackupArtifact;
import gold.debug.windowstolinux.shared.backup.contract.spi.DatabaseBackupRequest;
import gold.debug.windowstolinux.shared.backup.contract.spi.DatabaseCommitEvidence;
import gold.debug.windowstolinux.shared.backup.contract.spi.DatabaseCompatibilityEvidence;
import gold.debug.windowstolinux.shared.backup.contract.spi.DatabaseOperationPort;
import gold.debug.windowstolinux.shared.backup.contract.spi.DatabaseRecoveryEvidence;
import gold.debug.windowstolinux.shared.backup.contract.spi.DatabaseRestoreEvidence;
import gold.debug.windowstolinux.shared.backup.contract.spi.DatabaseRestoreRequest;
import gold.debug.windowstolinux.shared.backup.contract.validation.BackupException;
import gold.debug.windowstolinux.shared.backup.contract.validation.BackupFailureType;
import gold.debug.windowstolinux.shared.backup.manifest.BackupConsistencyMode;
import gold.debug.windowstolinux.shared.backup.manifest.BackupDatabaseType;

/**
 * MySQL-compatible policy with explicit transactional-table limitations. / 显式处理事务表限制的 MySQL 兼容策略。
 */
public final class MysqlDatabaseAdapter implements DatabaseBackupAdapter {
    /**
     * Database type.
     * <p>数据库类型。
     */
    private final BackupDatabaseType databaseType;

    /**
     * Operations.
     * <p>操作集合。
     */
    private final DatabaseOperationPort operations;

    /**
     * Creates one MySQL or MariaDB adapter. / 创建一个 MySQL 或 MariaDB 适配器。
     *
     * @param databaseType database type / 数据库类型
     * @param operations operations / 操作集合
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public MysqlDatabaseAdapter(BackupDatabaseType databaseType, DatabaseOperationPort operations) {
        if (databaseType != BackupDatabaseType.MYSQL && databaseType != BackupDatabaseType.MARIADB) {
            throw new IllegalArgumentException("MySQL adapter requires MySQL or MariaDB");
        }
        this.databaseType = databaseType;
        this.operations = Objects.requireNonNull(operations, "operations");
    }

    /**
     * Returns database type.
     * <p>返回数据库类型。
     *
     * @return database type / 数据库类型
     */
    @Override
    public BackupDatabaseType type() {
        return databaseType;
    }

    /**
     * Checks MySQL compatibility evidence and exports using the admitted consistency strategy.
     * <p>检查 MySQL 兼容性证据，并使用已准入一致性策略导出。
     *
     * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
     * @return constructed or resolved database backup artifact / 构造或解析得到的数据库备份制品
     * @throws BackupException if backup validation or the controlled backup operation fails / 备份校验或受控备份操作失败时
     */
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

    /**
     * Restores database restore evidence.
     * <p>恢复数据库恢复证据。
     *
     * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
     * @return constructed or resolved database restore evidence / 构造或解析得到的数据库恢复证据
     * @throws BackupException if backup validation or the controlled backup operation fails / 备份校验或受控备份操作失败时
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     */
    @Override
    public DatabaseRestoreEvidence restore(DatabaseRestoreRequest request) throws BackupException {
        if (request.target().type() != type())
            throw new IllegalArgumentException("matching MySQL-compatible target is required");
        DatabaseCompatibilityEvidence evidence = operations
                .inspect(new DatabaseBackupRequest(request.applicationId(), request.target(), false, false));
        DatabaseAdapterEvidence.requireRestoreCompatible(request, evidence, type());
        return operations.restoreCandidate(request);
    }

    /**
     * Commits candidate.
     * <p>提交候选。
     *
     * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
     * @return constructed or resolved database commit evidence / 构造或解析得到的数据库提交证据
     * @throws BackupException if backup validation or the controlled backup operation fails / 备份校验或受控备份操作失败时
     */
    @Override
    public DatabaseCommitEvidence commitCandidate(DatabaseRestoreRequest request) throws BackupException {
        requireRestoreType(request);
        return operations.commitCandidate(request);
    }

    /**
     * Recovers candidate.
     * <p>恢复候选。
     *
     * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
     * @return constructed or resolved database recovery evidence / 构造或解析得到的数据库恢复证据
     * @throws BackupException if backup validation or the controlled backup operation fails / 备份校验或受控备份操作失败时
     */
    @Override
    public DatabaseRecoveryEvidence recoverCandidate(DatabaseRestoreRequest request) throws BackupException {
        requireRestoreType(request);
        return operations.recoverCandidate(request);
    }

    /**
     * Discards candidate.
     * <p>清理候选。
     *
     * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
     * @throws BackupException if backup validation or the controlled backup operation fails / 备份校验或受控备份操作失败时
     */
    @Override
    public void discardCandidate(DatabaseRestoreRequest request) throws BackupException {
        requireRestoreType(request);
        operations.discardCandidate(request);
    }

    /**
     * Requires restore type and rejects inputs outside the declared constraints.
     * <p>要求恢复类型并拒绝超出已声明约束的输入。
     *
     * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     */
    private void requireRestoreType(DatabaseRestoreRequest request) {
        if (request.target().type() != type())
            throw new IllegalArgumentException("matching MySQL-compatible target is required");
    }

    /**
     * Requires selected member of the supported type set and rejects inputs outside the declared constraints.
     * <p>要求受支持类型集合中的所选项并拒绝超出已声明约束的输入。
     *
     * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     */
    private void requireType(DatabaseBackupRequest request) {
        if (request.connection().type() != type())
            throw new IllegalArgumentException("matching MySQL-compatible connection is required");
    }
}
