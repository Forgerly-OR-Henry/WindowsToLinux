package gold.debug.windowstolinux.shared.backup.contract.spi;

import gold.debug.windowstolinux.shared.backup.contract.validation.BackupException;
import gold.debug.windowstolinux.shared.backup.manifest.BackupConsistencyMode;

import java.io.InputStream;
import java.io.OutputStream;

/** Platform port for fixed database tools and isolated candidate restoration. / 固定数据库工具与隔离候选恢复的平台端口。 */
public interface DatabaseOperationPort {
    /** Collects version, tool and storage-engine evidence without changing the database. / 在不改变数据库的情况下采集版本、工具与存储引擎证据。 */
    DatabaseCompatibilityEvidence inspect(DatabaseBackupRequest request) throws BackupException;

    /** Exports using exactly the approved consistency mode. / 严格使用已批准的一致性方式导出。 */
    DatabaseBackupArtifact export(DatabaseBackupRequest request, BackupConsistencyMode consistencyMode)
            throws BackupException;

    /** Restores into a new isolated candidate and verifies it without activation. / 恢复到新的隔离候选并在不激活的情况下校验。 */
    DatabaseRestoreEvidence restoreCandidate(DatabaseRestoreRequest request) throws BackupException;

    /** Activates one isolated candidate while application writes are stopped. / 在应用写入停止时激活隔离候选。 */
    DatabaseCommitEvidence commitCandidate(DatabaseRestoreRequest request) throws BackupException;

    /** Restores the previous database or proves no previous database was present. / 恢复旧数据库或证明此前不存在数据库。 */
    DatabaseRecoveryEvidence recoverCandidate(DatabaseRestoreRequest request) throws BackupException;

    /** Removes one isolated database candidate after an uncommitted restore fails. / 在未提交恢复失败后移除隔离数据库候选。 */
    void discardCandidate(DatabaseRestoreRequest request) throws BackupException;

    /** Streams one verified remote artifact to a caller-owned destination. / 将一个已验证远程导出物流式传输到调用方持有的目标。 */
    void copyArtifact(DatabaseBackupArtifact artifact, OutputStream destination) throws BackupException;

    /** Streams an exact verified artifact into controlled remote staging before restore. / 在恢复前把精确已验证导出物流式传入受控远程暂存区。 */
    void stageArtifact(DatabaseBackupArtifact artifact, InputStream source) throws BackupException;

    /** Removes one failed or expired opaque export artifact. / 移除一个失败或过期的不透明导出物。 */
    void discardArtifact(DatabaseBackupArtifact artifact) throws BackupException;
}
