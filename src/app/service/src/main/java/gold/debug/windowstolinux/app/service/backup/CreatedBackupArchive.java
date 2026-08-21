package gold.debug.windowstolinux.app.service.backup;

import java.nio.file.Path;
import java.util.Objects;

/** Final-path evidence returned only after independent post-publication validation. / 仅在发布后独立复验完成时返回的最终路径证据。 */
public record CreatedBackupArchive(Path archive, BackupArchiveInspection inspection) {
    /** Normalizes the final path and requires complete inspection evidence. / 规范化最终路径并要求完整检查证据。 */
    public CreatedBackupArchive {
        archive = Objects.requireNonNull(archive, "archive").toAbsolutePath().normalize();
        inspection = Objects.requireNonNull(inspection, "inspection");
    }
}
