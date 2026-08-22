package gold.debug.windowstolinux.app.ui.backup;

import gold.debug.windowstolinux.app.service.backup.PreparedBackupCandidate;

import java.util.Objects;

/** Preserves managed input, archive, result, and candidate lifecycle across shell rebuilds. / 在外壳重建时保留受管输入、归档、结果和候选生命周期。 */
public record BackupPageState(
        String applicationId,
        String archivePath,
        String destinationPath,
        String output,
        PreparedBackupCandidate preparedCandidate
) {
    /** Validates immutable page state. / 校验不可变页面状态。 */
    public BackupPageState {
        applicationId = Objects.requireNonNull(applicationId, "applicationId");
        archivePath = Objects.requireNonNull(archivePath, "archivePath");
        destinationPath = Objects.requireNonNull(destinationPath, "destinationPath");
        output = Objects.requireNonNull(output, "output");
    }

    /** Restores state captured before remote backup creation was exposed. / 恢复远端备份创建入口出现前捕获的状态。 */
    public BackupPageState(String applicationId, String archivePath, String output,
                           PreparedBackupCandidate preparedCandidate) {
        this(applicationId, archivePath, "", output, preparedCandidate);
    }
}
