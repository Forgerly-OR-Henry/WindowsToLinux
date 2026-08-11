package gold.debug.windowstolinux.shared.model.archive;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;

/**
 * A locally prepared {@code tar.gz} source archive with compressed and post-extraction size bounds.
 *
 * <p>本地准备的 {@code tar.gz} 源码归档，包含压缩大小和解压后大小边界。
 *
 * @param localArchive the {@code localArchive} value / {@code localArchive} 值
 * @param contentSha256 the {@code contentSha256} value / {@code contentSha256} 值
 * @param byteCount the {@code byteCount} value / {@code byteCount} 值
 * @param uncompressedByteCount the {@code uncompressedByteCount} value / {@code uncompressedByteCount} 值
 */
public record SourceArchiveDescriptor(Path localArchive, String contentSha256, long byteCount, long uncompressedByteCount) {
    /**
     * Creates a {@code SourceArchiveDescriptor} instance.
     *
     * <p>创建 {@code SourceArchiveDescriptor} 实例。
     *
     * @param localArchive the {@code localArchive} value / {@code localArchive} 值
     * @param contentSha256 the {@code contentSha256} value / {@code contentSha256} 值
     * @param byteCount the {@code byteCount} value / {@code byteCount} 值
     * @param uncompressedByteCount the {@code uncompressedByteCount} value / {@code uncompressedByteCount} 值
     * @throws IllegalArgumentException if an argument violates the required constraints / 参数违反必要约束时
     * @throws NullPointerException if a required argument is {@code null} / 必要参数为 {@code null} 时
     */
    public SourceArchiveDescriptor {
        localArchive = Objects.requireNonNull(localArchive, "localArchive").toAbsolutePath().normalize();
        Path archiveFileName = localArchive.getFileName();
        if (archiveFileName == null || !archiveFileName.toString().toLowerCase(Locale.ROOT).endsWith(".tar.gz")) {
            throw new IllegalArgumentException("localArchive must end in .tar.gz");
        }
        contentSha256 = Objects.requireNonNull(contentSha256, "contentSha256");
        if (!contentSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("contentSha256 must be lowercase SHA-256");
        }
        if (byteCount < 0 || uncompressedByteCount < 0) {
            throw new IllegalArgumentException("archive sizes must not be negative");
        }
    }
}
