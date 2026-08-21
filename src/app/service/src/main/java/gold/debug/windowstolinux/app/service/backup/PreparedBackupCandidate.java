package gold.debug.windowstolinux.app.service.backup;

import java.nio.file.Path;
import java.util.Objects;

/** Locally extracted but never activated restore candidate. / 已在本地提取但从未激活的恢复候选。 */
public record PreparedBackupCandidate(
        BackupArchiveInspection inspection,
        Path candidateRoot,
        long extractedBytes
) {
    /** Validates candidate identity and byte evidence. / 校验候选身份和字节证据。 */
    public PreparedBackupCandidate {
        inspection = Objects.requireNonNull(inspection, "inspection");
        candidateRoot = Objects.requireNonNull(candidateRoot, "candidateRoot").toAbsolutePath().normalize();
        String expected = inspection.applicationId() + "-" + inspection.archiveSha256().substring(0, 16);
        if (!candidateRoot.getFileName().toString().equals(expected)
                || extractedBytes != inspection.verifiedBytes()) {
            throw new IllegalArgumentException("prepared backup candidate differs from validation evidence");
        }
    }
}
