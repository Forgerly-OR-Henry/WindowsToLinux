package gold.debug.windowstolinux.app.service.backup;

import gold.debug.windowstolinux.app.windows.workspace.WindowsRestoreAttempt;

import java.nio.file.Path;
import java.util.Objects;

/** Locally extracted but never activated restore candidate. / 已在本地提取但从未激活的恢复候选。 */
public final class PreparedBackupCandidate {
    private final BackupArchiveInspection inspection;
    private final Path candidateRoot;
    private final long extractedBytes;
    private final WindowsRestoreAttempt attempt;

    /** Creates read-only candidate evidence without granting workspace deletion authority. / 创建不授予工作区删除权限的只读候选证据。 */
    public PreparedBackupCandidate(
            BackupArchiveInspection inspection,
            Path candidateRoot,
            long extractedBytes
    ) {
        this(inspection, candidateRoot, extractedBytes, null);
    }

    PreparedBackupCandidate(
            BackupArchiveInspection inspection,
            Path candidateRoot,
            long extractedBytes,
            WindowsRestoreAttempt attempt
    ) {
        this.inspection = Objects.requireNonNull(inspection, "inspection");
        this.candidateRoot = Objects.requireNonNull(candidateRoot, "candidateRoot").toAbsolutePath().normalize();
        this.extractedBytes = extractedBytes;
        this.attempt = attempt;
        String expected = this.inspection.applicationId() + "-" + this.inspection.archiveSha256().substring(0, 16);
        if (this.candidateRoot.getFileName() == null
                || !this.candidateRoot.getFileName().toString().equals(expected)
                || this.extractedBytes != this.inspection.verifiedBytes()
                || attempt != null && !attempt.candidateRoot().equals(this.candidateRoot)) {
            throw new IllegalArgumentException("prepared backup candidate differs from validation evidence");
        }
    }

    /** Returns complete archive inspection evidence. / 返回完整归档检查证据。 */
    public BackupArchiveInspection inspection() {
        return inspection;
    }

    /** Returns the local inactive candidate root. / 返回本地未激活候选根。 */
    public Path candidateRoot() {
        return candidateRoot;
    }

    /** Returns the extracted byte evidence. / 返回已提取字节证据。 */
    public long extractedBytes() {
        return extractedBytes;
    }

    WindowsRestoreAttempt attempt() {
        return attempt;
    }
}
