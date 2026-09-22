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
import gold.debug.windowstolinux.shared.backup.manifest.BackupConsistencyMode;
import gold.debug.windowstolinux.shared.backup.manifest.BackupDatabaseType;

/**
 * PostgreSQL policy that permits only a version-compatible controlled logical dump. / 仅允许版本兼容受控逻辑导出的 PostgreSQL 策略。
 */
public final class PostgresqlDatabaseAdapter implements DatabaseBackupAdapter {
    /**
     * Operations.
     * <p>操作集合。
     */
    private final DatabaseOperationPort operations;

    /**
     * Creates the PostgreSQL adapter over one platform port. / 基于一个平台端口创建 PostgreSQL 适配器。
     *
     * @param operations operations / 操作集合
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public PostgresqlDatabaseAdapter(DatabaseOperationPort operations) {
        this.operations = Objects.requireNonNull(operations, "operations");
    }

    /**
     * Returns selected member of the supported type set.
     * <p>返回受支持类型集合中的所选项。
     *
     * @return selected member of the supported type set / 受支持类型集合中的所选项
     */
    @Override
    public BackupDatabaseType type() {
        return BackupDatabaseType.POSTGRESQL;
    }

    /**
     * Checks PostgreSQL readiness and exports using its consistent logical-backup strategy.
     * <p>检查 PostgreSQL 就绪状态，并使用一致性逻辑备份策略导出。
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
        BackupConsistencyMode mode = BackupConsistencyMode.POSTGRESQL_LOGICAL_DUMP;
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
            throw new IllegalArgumentException("PostgreSQL restore target is required");
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
            throw new IllegalArgumentException("PostgreSQL restore target is required");
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
            throw new IllegalArgumentException("PostgreSQL connection is required");
    }
}
