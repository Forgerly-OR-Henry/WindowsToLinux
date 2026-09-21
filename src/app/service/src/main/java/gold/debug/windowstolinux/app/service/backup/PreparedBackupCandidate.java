package gold.debug.windowstolinux.app.service.backup;

import gold.debug.windowstolinux.app.windows.workspace.WindowsRestoreAttempt;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Locally extracted but never activated restore candidate. / 已在本地提取但从未激活的恢复候选。
 */
public final class PreparedBackupCandidate {
    /**
     * Inspection.
     * <p>检查。
     */
    private final BackupArchiveInspection inspection;
    /**
     * Candidate root.
     * <p>候选根目录。
     */
    private final Path candidateRoot;
    /**
     * Extracted bytes.
     * <p>已提取字节。
     */
    private final long extractedBytes;
    /**
     * Attempt.
     * <p>尝试。
     */
    private final WindowsRestoreAttempt attempt;

    /**
     * Creates read-only candidate evidence without granting workspace deletion authority. / 创建不授予工作区删除权限的只读候选证据。
     *
     * @param inspection inspection / 检查
     * @param candidateRoot candidate root / 候选根目录
     * @param extractedBytes extracted bytes / 已提取字节
     */
    public PreparedBackupCandidate(
            BackupArchiveInspection inspection,
            Path candidateRoot,
            long extractedBytes
    ) {
        this(inspection, candidateRoot, extractedBytes, null);
    }

    /**
     * Validates and binds the inputs required by prepared backup candidate.
     * <p>校验并绑定已准备备份候选所需输入。
     *
     * @param inspection inspection / 检查
     * @param candidateRoot candidate root / 候选根目录
     * @param extractedBytes extracted bytes / 已提取字节
     * @param attempt attempt / 尝试
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
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

    /**
     * Returns complete archive inspection evidence. / 返回完整归档检查证据。
     *
     * @return complete archive inspection evidence / 完整归档检查证据
     */
    public BackupArchiveInspection inspection() {
        return inspection;
    }

    /**
     * Returns the local inactive candidate root. / 返回本地未激活候选根。
     *
     * @return the local inactive candidate root / 本地未激活候选根
     */
    public Path candidateRoot() {
        return candidateRoot;
    }

    /**
     * Returns the extracted byte evidence. / 返回已提取字节证据。
     *
     * @return the extracted byte evidence / 已提取字节证据
     */
    public long extractedBytes() {
        return extractedBytes;
    }

    /**
     * Returns attempt.
     * <p>返回尝试。
     *
     * @return attempt / 尝试
     */
    WindowsRestoreAttempt attempt() {
        return attempt;
    }
}
