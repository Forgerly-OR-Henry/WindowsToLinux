package gold.debug.windowstolinux.app.ui.backup;

import gold.debug.windowstolinux.app.service.backup.PreparedBackupCandidate;

import java.util.Objects;

/** Preserves archive, result, and exact candidate lifecycle across shell rebuilds. / 在外壳重建时保留归档、结果和精确候选生命周期。 */
public record BackupPageState(String archivePath, String output, PreparedBackupCandidate preparedCandidate) {
    /** Validates immutable page state. / 校验不可变页面状态。 */
    public BackupPageState {
        archivePath = Objects.requireNonNull(archivePath, "archivePath");
        output = Objects.requireNonNull(output, "output");
    }
}
