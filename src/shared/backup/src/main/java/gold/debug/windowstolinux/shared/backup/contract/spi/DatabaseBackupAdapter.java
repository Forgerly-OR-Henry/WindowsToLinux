package gold.debug.windowstolinux.shared.backup.contract.spi;

import gold.debug.windowstolinux.shared.backup.contract.validation.BackupException;
import gold.debug.windowstolinux.shared.backup.manifest.BackupDatabaseType;

/** Database-family consistency policy over one platform operation port. / 基于平台操作端口的数据库族一致性策略。 */
public interface DatabaseBackupAdapter {
    /** Returns the exact supported database family. / 返回精确支持的数据库族。 */
    BackupDatabaseType type();

    /** Performs preflight and a consistency-proven export. / 执行前置检查及带一致性证据的导出。 */
    DatabaseBackupArtifact backup(DatabaseBackupRequest request) throws BackupException;

    /** Restores one verified artifact into an isolated candidate. / 将一个已验证导出物恢复到隔离候选。 */
    DatabaseRestoreEvidence restore(DatabaseRestoreRequest request) throws BackupException;

    /** Removes one uncommitted isolated restore candidate. / 移除一个未提交的隔离恢复候选。 */
    void discardCandidate(DatabaseRestoreRequest request) throws BackupException;
}
