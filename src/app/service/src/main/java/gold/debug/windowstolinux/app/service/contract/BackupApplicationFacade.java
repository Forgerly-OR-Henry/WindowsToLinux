package gold.debug.windowstolinux.app.service.contract;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.function.Predicate;

import gold.debug.windowstolinux.app.secret.SecretStoreException;
import gold.debug.windowstolinux.app.service.backup.BackupArchiveInspection;
import gold.debug.windowstolinux.app.service.backup.CreatedBackupArchive;
import gold.debug.windowstolinux.app.service.backup.ManagedBackupInputAssessment;
import gold.debug.windowstolinux.app.service.backup.ManagedOfflineMigrationOutcome;
import gold.debug.windowstolinux.app.service.backup.ManagedRestoreOutcome;
import gold.debug.windowstolinux.app.service.backup.PreparedBackupCandidate;
import gold.debug.windowstolinux.app.service.backup.PreparedBackupSecrets;
import gold.debug.windowstolinux.shared.backup.crypto.BackupSecretException;
import gold.debug.windowstolinux.shared.linux.error.LinuxOperationException;

/**
 * UI-facing persisted-input assessment, local inspection, and candidate preparation contract. / 面向 UI 的持久化输入评估、本地检查与候选准备契约。
 */
public interface BackupApplicationFacade {
    /**
     * Lists saved choices for beginner backup and migration forms. / 列出供简易备份和迁移表单选择的已保存项。
     *
     * @return constructed or resolved list / 构造或解析得到的列表
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     */
    java.util.List<gold.debug.windowstolinux.app.service.execution.lifecycle.ManagedApplicationSnapshot> listManagedApplicationSummaries()
            throws SQLException;

    /**
     * Lists server profiles.
     * <p>列出服务器配置资料集合。
     *
     * @return constructed or resolved list / 构造或解析得到的列表
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     */
    java.util.List<gold.debug.windowstolinux.app.service.server.ServerProfile> listServerProfiles() throws SQLException;

    /**
     * Assesses exact persisted inputs without connecting to the managed server. / 在不连接受管服务器的情况下评估精确持久化输入。
     *
     * @param applicationId managed application identifier / 受管应用标识
     * @return constructed or resolved managed backup input assessment / 构造或解析得到的受管备份输入评估
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     */
    ManagedBackupInputAssessment assessManagedBackupInputs(String applicationId) throws SQLException;

    /**
     * Creates and atomically publishes one complete managed-server backup. / 创建并原子发布一个完整受管服务器备份。
     *
     * @param applicationId managed application identifier / 受管应用标识
     * @param destination caller-selected destination inside the permitted boundary / 调用方选择的许可边界内目的地
     * @param backupPassword independent password for backup secret encryption or decryption / 备份秘密加密或解密使用的独立密码
     * @param masterPassword master-password buffer used to unlock protected credentials / 用于解锁受保护凭据的主密码缓冲区
     * @param firstUseConfirmation first use confirmation / 首次使用确认
     * @return and atomically publishes one complete managed-server backup / 并原子发布一个完整受管服务器备份
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     * @throws SecretStoreException if the protected credential cannot be accessed or updated / 无法访问或更新受保护凭据时
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     * @throws BackupSecretException if the backup secret boundary rejects the operation / 备份秘密边界拒绝当前操作时
     */
    CreatedBackupArchive createManagedBackup(String applicationId, Path destination, char[] backupPassword,
            char[] masterPassword, Predicate<String> firstUseConfirmation)
            throws SQLException, SecretStoreException, LinuxOperationException, IOException, BackupSecretException;

    /**
     * Restores one complete archive to a selected saved server profile. / 将一个完整归档恢复到选定的已保存服务器资料。
     *
     * @param archive source or backup archive descriptor or filesystem path / 源码或备份归档描述或文件系统路径
     * @param targetServerId target server id / 目标服务器标识
     * @param backupPassword independent password for backup secret encryption or decryption / 备份秘密加密或解密使用的独立密码
     * @param masterPassword master-password buffer used to unlock protected credentials / 用于解锁受保护凭据的主密码缓冲区
     * @param firstUseConfirmation first use confirmation / 首次使用确认
     * @return constructed or resolved managed restore outcome / 构造或解析得到的受管恢复结果
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     * @throws SecretStoreException if the protected credential cannot be accessed or updated / 无法访问或更新受保护凭据时
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     * @throws BackupSecretException if the backup secret boundary rejects the operation / 备份秘密边界拒绝当前操作时
     */
    ManagedRestoreOutcome restoreManagedBackup(Path archive, String targetServerId, char[] backupPassword,
            char[] masterPassword, Predicate<String> firstUseConfirmation)
            throws SQLException, SecretStoreException, LinuxOperationException, IOException, BackupSecretException;

    /**
     * Prepares an offline two-server migration and stops before manual external traffic switching. / 准备离线双服务器迁移并在人工外部切流前停止。
     *
     * @param applicationId managed application identifier / 受管应用标识
     * @param targetServerId target server id / 目标服务器标识
     * @param backupPassword independent password for backup secret encryption or decryption / 备份秘密加密或解密使用的独立密码
     * @param masterPassword master-password buffer used to unlock protected credentials / 用于解锁受保护凭据的主密码缓冲区
     * @param stopWindowApproved stop window approved / 停止窗口已批准
     * @param firstUseConfirmation first use confirmation / 首次使用确认
     * @return constructed or resolved managed offline migration outcome / 构造或解析得到的受管离线迁移结果
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     * @throws SecretStoreException if the protected credential cannot be accessed or updated / 无法访问或更新受保护凭据时
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     * @throws BackupSecretException if the backup secret boundary rejects the operation / 备份秘密边界拒绝当前操作时
     */
    ManagedOfflineMigrationOutcome prepareManagedOfflineMigration(String applicationId, String targetServerId,
            char[] backupPassword, char[] masterPassword, boolean stopWindowApproved,
            Predicate<String> firstUseConfirmation)
            throws SQLException, SecretStoreException, LinuxOperationException, IOException, BackupSecretException;

    /**
     * Validates one archive without extraction or remote access. / 校验一个归档且不提取、不访问远端。
     *
     * @param archive source or backup archive descriptor or filesystem path / 源码或备份归档描述或文件系统路径
     * @return constructed or resolved backup archive inspection / 构造或解析得到的备份归档检查
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
    BackupArchiveInspection inspectBackup(Path archive) throws IOException;

    /**
     * Creates a new isolated local candidate without activation. / 创建一个新的隔离本地候选且不激活。
     *
     * @param archive source or backup archive descriptor or filesystem path / 源码或备份归档描述或文件系统路径
     * @return a new isolated local candidate without activation / 一个新的隔离本地候选且不激活
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
    PreparedBackupCandidate prepareBackupCandidate(Path archive) throws IOException;

    /**
     * Deletes only the exact local candidate supplied by this facade. / 仅删除此门面所提供的精确本地候选。
     *
     * @param candidate candidate / 候选
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
    void discardBackupCandidate(PreparedBackupCandidate candidate) throws IOException;

    /**
     * Prepares a local candidate and authenticates its exact encrypted revisions. / 准备本地候选并认证其精确加密修订。
     *
     * @param archive source or backup archive descriptor or filesystem path / 源码或备份归档描述或文件系统路径
     * @param backupPassword independent password for backup secret encryption or decryption / 备份秘密加密或解密使用的独立密码
     * @return constructed or resolved prepared backup secrets / 构造或解析得到的已准备备份秘密集合
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     * @throws BackupSecretException if the backup secret boundary rejects the operation / 备份秘密边界拒绝当前操作时
     */
    PreparedBackupSecrets prepareBackupCandidateWithSecrets(Path archive, char[] backupPassword)
            throws IOException, BackupSecretException;
}
