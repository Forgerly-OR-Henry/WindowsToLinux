package gold.debug.windowstolinux.app.ui.backup;

import java.util.Objects;

/** Preserves the selected local archive and visible result across shell rebuilds. / 在外壳重建时保留已选本地归档与可见结果。 */
public record BackupPageState(String archivePath, String output) {
    /** Validates immutable page state. / 校验不可变页面状态。 */
    public BackupPageState {
        archivePath = Objects.requireNonNull(archivePath, "archivePath");
        output = Objects.requireNonNull(output, "output");
    }
}
