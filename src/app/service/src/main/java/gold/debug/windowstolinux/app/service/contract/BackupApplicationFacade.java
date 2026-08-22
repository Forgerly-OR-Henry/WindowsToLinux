package gold.debug.windowstolinux.app.service.contract;

import gold.debug.windowstolinux.app.service.backup.BackupArchiveInspection;
import gold.debug.windowstolinux.app.service.backup.ManagedBackupInputAssessment;
import gold.debug.windowstolinux.app.service.backup.PreparedBackupCandidate;
import gold.debug.windowstolinux.app.service.backup.PreparedBackupSecrets;
import gold.debug.windowstolinux.app.service.backup.CreatedBackupArchive;
import gold.debug.windowstolinux.app.secret.crypto.BackupSecretException;
import gold.debug.windowstolinux.app.secret.SecretStoreException;
import gold.debug.windowstolinux.shared.linux.error.LinuxOperationException;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.function.Predicate;

/** UI-facing persisted-input assessment, local inspection, and candidate preparation contract. / 面向 UI 的持久化输入评估、本地检查与候选准备契约。 */
public interface BackupApplicationFacade {
    /** Assesses exact persisted inputs without connecting to the managed server. / 在不连接受管服务器的情况下评估精确持久化输入。 */
    ManagedBackupInputAssessment assessManagedBackupInputs(String applicationId) throws SQLException;

    /** Creates and atomically publishes one complete managed-server backup. / 创建并原子发布一个完整受管服务器备份。 */
    CreatedBackupArchive createManagedBackup(
            String applicationId, Path destination, char[] backupPassword, char[] masterPassword,
            Predicate<String> firstUseConfirmation
    ) throws SQLException, SecretStoreException, LinuxOperationException, IOException, BackupSecretException;

    /** Validates one archive without extraction or remote access. / 校验一个归档且不提取、不访问远端。 */
    BackupArchiveInspection inspectBackup(Path archive) throws IOException;

    /** Creates a new isolated local candidate without activation. / 创建一个新的隔离本地候选且不激活。 */
    PreparedBackupCandidate prepareBackupCandidate(Path archive) throws IOException;

    /** Deletes only the exact local candidate supplied by this facade. / 仅删除此门面所提供的精确本地候选。 */
    void discardBackupCandidate(PreparedBackupCandidate candidate) throws IOException;

    /** Prepares a local candidate and authenticates its exact encrypted revisions. / 准备本地候选并认证其精确加密修订。 */
    PreparedBackupSecrets prepareBackupCandidateWithSecrets(Path archive, char[] backupPassword)
            throws IOException, BackupSecretException;
}
