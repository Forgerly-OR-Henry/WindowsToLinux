package gold.debug.windowstolinux.app.service.contract;

import gold.debug.windowstolinux.app.service.backup.BackupArchiveInspection;
import gold.debug.windowstolinux.app.service.backup.ManagedBackupInputAssessment;
import gold.debug.windowstolinux.app.service.backup.PreparedBackupCandidate;
import gold.debug.windowstolinux.app.service.backup.PreparedBackupSecrets;
import gold.debug.windowstolinux.app.secret.crypto.BackupSecretException;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;

/** UI-facing persisted-input assessment, local inspection, and candidate preparation contract. / 面向 UI 的持久化输入评估、本地检查与候选准备契约。 */
public interface BackupApplicationFacade {
    /** Assesses exact persisted inputs without connecting to the managed server. / 在不连接受管服务器的情况下评估精确持久化输入。 */
    ManagedBackupInputAssessment assessManagedBackupInputs(String applicationId) throws SQLException;

    /** Validates one archive without extraction or remote access. / 校验一个归档且不提取、不访问远端。 */
    BackupArchiveInspection inspectBackup(Path archive) throws IOException;

    /** Creates a new isolated local candidate without activation. / 创建一个新的隔离本地候选且不激活。 */
    PreparedBackupCandidate prepareBackupCandidate(Path archive) throws IOException;

    /** Prepares a local candidate and authenticates its exact encrypted revisions. / 准备本地候选并认证其精确加密修订。 */
    PreparedBackupSecrets prepareBackupCandidateWithSecrets(Path archive, char[] backupPassword)
            throws IOException, BackupSecretException;
}
