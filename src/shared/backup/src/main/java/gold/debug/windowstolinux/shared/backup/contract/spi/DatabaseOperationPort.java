package gold.debug.windowstolinux.shared.backup.contract.spi;

import gold.debug.windowstolinux.shared.backup.contract.validation.BackupException;
import gold.debug.windowstolinux.shared.backup.manifest.BackupConsistencyMode;

import java.io.InputStream;
import java.io.OutputStream;

/**
 * Platform port for fixed database tools and isolated candidate restoration. / 固定数据库工具与隔离候选恢复的平台端口。
 */
public interface DatabaseOperationPort {
    /**
     * Collects version, tool and storage-engine evidence without changing the database. / 在不改变数据库的情况下采集版本、工具与存储引擎证据。
     *
     * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
     * @return constructed or resolved database compatibility evidence / 构造或解析得到的数据库兼容性证据
     * @throws BackupException if backup validation or the controlled backup operation fails / 备份校验或受控备份操作失败时
     */
    DatabaseCompatibilityEvidence inspect(DatabaseBackupRequest request) throws BackupException;

    /**
     * Exports using exactly the approved consistency mode. / 严格使用已批准的一致性方式导出。
     *
     * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
     * @param consistencyMode consistency mode / 一致性模式
     * @return constructed or resolved database backup artifact / 构造或解析得到的数据库备份制品
     * @throws BackupException if backup validation or the controlled backup operation fails / 备份校验或受控备份操作失败时
     */
    DatabaseBackupArtifact export(DatabaseBackupRequest request, BackupConsistencyMode consistencyMode)
            throws BackupException;

    /**
     * Restores into a new isolated candidate and verifies it without activation. / 恢复到新的隔离候选并在不激活的情况下校验。
     *
     * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
     * @return constructed or resolved database restore evidence / 构造或解析得到的数据库恢复证据
     * @throws BackupException if backup validation or the controlled backup operation fails / 备份校验或受控备份操作失败时
     */
    DatabaseRestoreEvidence restoreCandidate(DatabaseRestoreRequest request) throws BackupException;

    /**
     * Activates one isolated candidate while application writes are stopped. / 在应用写入停止时激活隔离候选。
     *
     * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
     * @return constructed or resolved database commit evidence / 构造或解析得到的数据库提交证据
     * @throws BackupException if backup validation or the controlled backup operation fails / 备份校验或受控备份操作失败时
     */
    DatabaseCommitEvidence commitCandidate(DatabaseRestoreRequest request) throws BackupException;

    /**
     * Restores the previous database or proves no previous database was present. / 恢复旧数据库或证明此前不存在数据库。
     *
     * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
     * @return constructed or resolved database recovery evidence / 构造或解析得到的数据库恢复证据
     * @throws BackupException if backup validation or the controlled backup operation fails / 备份校验或受控备份操作失败时
     */
    DatabaseRecoveryEvidence recoverCandidate(DatabaseRestoreRequest request) throws BackupException;

    /**
     * Removes one isolated database candidate after an uncommitted restore fails. / 在未提交恢复失败后移除隔离数据库候选。
     *
     * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
     * @throws BackupException if backup validation or the controlled backup operation fails / 备份校验或受控备份操作失败时
     */
    void discardCandidate(DatabaseRestoreRequest request) throws BackupException;

    /**
     * Streams one verified remote artifact to a caller-owned destination. / 将一个已验证远程导出物流式传输到调用方持有的目标。
     *
     * @param artifact verified build or backup artifact metadata / 已验证构建或备份制品元数据
     * @param destination caller-selected destination inside the permitted boundary / 调用方选择的许可边界内目的地
     * @throws BackupException if backup validation or the controlled backup operation fails / 备份校验或受控备份操作失败时
     */
    void copyArtifact(DatabaseBackupArtifact artifact, OutputStream destination) throws BackupException;

    /**
     * Streams an exact verified artifact into controlled remote staging before restore. / 在恢复前把精确已验证导出物流式传入受控远程暂存区。
     *
     * @param artifact verified build or backup artifact metadata / 已验证构建或备份制品元数据
     * @param source source identity or content read by the operation / 操作读取的源身份或内容
     * @throws BackupException if backup validation or the controlled backup operation fails / 备份校验或受控备份操作失败时
     */
    void stageArtifact(DatabaseBackupArtifact artifact, InputStream source) throws BackupException;

    /**
     * Removes one failed or expired opaque export artifact. / 移除一个失败或过期的不透明导出物。
     *
     * @param artifact verified build or backup artifact metadata / 已验证构建或备份制品元数据
     * @throws BackupException if backup validation or the controlled backup operation fails / 备份校验或受控备份操作失败时
     */
    void discardArtifact(DatabaseBackupArtifact artifact) throws BackupException;
}
