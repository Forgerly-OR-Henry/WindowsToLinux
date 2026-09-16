package gold.debug.windowstolinux.app.ui.backup;

import gold.debug.windowstolinux.app.service.backup.PreparedBackupCandidate;

import java.util.Objects;

/** Preserves managed input, archive, result, and candidate lifecycle across shell rebuilds. / 在外壳重建时保留受管输入、归档、结果和候选生命周期。 */
public record BackupPageState(
        String applicationId,
        String targetServerId,
        String archivePath,
        String destinationPath,
        String output,
        PreparedBackupCandidate preparedCandidate,
        int task,
        char[] backupPassword,
        char[] masterPassword
) implements AutoCloseable {
    /** Constructs a default backup task with retained prior page values. / 保留页面先前输入，构造默认备份任务。 */
    public BackupPageState(String applicationId, String targetServerId, String archivePath, String destinationPath,
                           String output, PreparedBackupCandidate preparedCandidate) {
        this(applicationId, targetServerId, archivePath, destinationPath, output, preparedCandidate, 0, new char[0], new char[0]);
    }

    /** Validates immutable page state. / 校验不可变页面状态。 */
    public BackupPageState {
        if (task < 0 || task > 2) throw new IllegalArgumentException("invalid backup task");
        applicationId = Objects.requireNonNull(applicationId, "applicationId");
        targetServerId = Objects.requireNonNull(targetServerId, "targetServerId");
        archivePath = Objects.requireNonNull(archivePath, "archivePath");
        destinationPath = Objects.requireNonNull(destinationPath, "destinationPath");
        output = Objects.requireNonNull(output, "output");
        backupPassword = Objects.requireNonNull(backupPassword).clone();
        masterPassword = Objects.requireNonNull(masterPassword).clone();
    }

    /** Restores state captured before remote backup creation was exposed. / 恢复远端备份创建入口出现前捕获的状态。 */
    public BackupPageState(String applicationId, String archivePath, String output,
                           PreparedBackupCandidate preparedCandidate) {
        this(applicationId, "", archivePath, "", output, preparedCandidate);
    }

    /** Restores state captured before target restore and migration were exposed. / 恢复目标恢复及迁移入口出现前捕获的状态。 */
    public BackupPageState(String applicationId, String archivePath, String destinationPath, String output,
                           PreparedBackupCandidate preparedCandidate) {
        this(applicationId, "", archivePath, destinationPath, output, preparedCandidate);
    }

    @Override public char[] backupPassword() { return backupPassword.clone(); }
    @Override public char[] masterPassword() { return masterPassword.clone(); }
    @Override public void close() {
        java.util.Arrays.fill(backupPassword, '\0'); java.util.Arrays.fill(masterPassword, '\0');
    }
}
