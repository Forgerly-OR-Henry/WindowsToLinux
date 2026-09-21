package gold.debug.windowstolinux.shared.backup.contract.spi;

import gold.debug.windowstolinux.shared.backup.contract.validation.BackupException;
import gold.debug.windowstolinux.shared.backup.manifest.BackupDatabaseType;

/**
 * Database-family consistency policy over one platform operation port. / 基于平台操作端口的数据库族一致性策略。
 */
public interface DatabaseBackupAdapter {
    /**
     * Returns the exact supported database family. / 返回精确支持的数据库族。
     *
     * @return the exact supported database family / 精确支持的数据库族
     */
    BackupDatabaseType type();

    /**
     * Performs preflight and a consistency-proven export. / 执行前置检查及带一致性证据的导出。
     *
     * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
     * @return constructed or resolved database backup artifact / 构造或解析得到的数据库备份制品
     * @throws BackupException if backup validation or the controlled backup operation fails / 备份校验或受控备份操作失败时
     */
    DatabaseBackupArtifact backup(DatabaseBackupRequest request) throws BackupException;

    /**
     * Restores one verified artifact into an isolated candidate. / 将一个已验证导出物恢复到隔离候选。
     *
     * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
     * @return constructed or resolved database restore evidence / 构造或解析得到的数据库恢复证据
     * @throws BackupException if backup validation or the controlled backup operation fails / 备份校验或受控备份操作失败时
     */
    DatabaseRestoreEvidence restore(DatabaseRestoreRequest request) throws BackupException;

    /**
     * Activates the verified candidate only inside a stopped-write transaction. / 仅在已停写事务内激活已验证候选。
     *
     * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
     * @return constructed or resolved database commit evidence / 构造或解析得到的数据库提交证据
     * @throws BackupException if backup validation or the controlled backup operation fails / 备份校验或受控备份操作失败时
     */
    DatabaseCommitEvidence commitCandidate(DatabaseRestoreRequest request) throws BackupException;

    /**
     * Rolls back an attempted activation or removes an uncommitted candidate. / 回滚激活尝试或移除未提交候选。
     *
     * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
     * @return constructed or resolved database recovery evidence / 构造或解析得到的数据库恢复证据
     * @throws BackupException if backup validation or the controlled backup operation fails / 备份校验或受控备份操作失败时
     */
    DatabaseRecoveryEvidence recoverCandidate(DatabaseRestoreRequest request) throws BackupException;

    /**
     * Removes one uncommitted isolated restore candidate. / 移除一个未提交的隔离恢复候选。
     *
     * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
     * @throws BackupException if backup validation or the controlled backup operation fails / 备份校验或受控备份操作失败时
     */
    void discardCandidate(DatabaseRestoreRequest request) throws BackupException;
}
