package gold.debug.windowstolinux.shared.linux.transfer;

import java.util.Objects;

/**
 * Confirmation that a source archive reached the fixed candidate workspace.
 *
 * <p>源码归档已到达固定候选工作区的确认结果。
 *
 * @param remoteArchivePath the {@code remoteArchivePath} value / {@code remoteArchivePath} 值
 * @param byteCount the {@code byteCount} value / {@code byteCount} 值
 * @param contentSha256 the {@code contentSha256} value / {@code contentSha256} 值
 * @param evidence the {@code evidence} value / {@code evidence} 值
 */
public record SourceUploadResult(String remoteArchivePath, long byteCount, String contentSha256, String evidence) {
    /**
     * Creates a {@code SourceUploadResult} instance.
     *
     * <p>创建 {@code SourceUploadResult} 实例。
     *
     * @param remoteArchivePath the {@code remoteArchivePath} value / {@code remoteArchivePath} 值
     * @param byteCount the {@code byteCount} value / {@code byteCount} 值
     * @param contentSha256 the {@code contentSha256} value / {@code contentSha256} 值
     * @param evidence the {@code evidence} value / {@code evidence} 值
     * @throws IllegalArgumentException if an argument violates the required constraints / 参数违反必要约束时
     * @throws NullPointerException if a required argument is {@code null} / 必要参数为 {@code null} 时
     */
    public SourceUploadResult {
        remoteArchivePath = Objects.requireNonNull(remoteArchivePath, "remoteArchivePath");
        contentSha256 = Objects.requireNonNull(contentSha256, "contentSha256");
        evidence = Objects.requireNonNull(evidence, "evidence");
        if (!remoteArchivePath.startsWith("/var/lib/windowstolinux/work/") || byteCount < 0
                || !contentSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("invalid upload receipt");
        }
    }
}
