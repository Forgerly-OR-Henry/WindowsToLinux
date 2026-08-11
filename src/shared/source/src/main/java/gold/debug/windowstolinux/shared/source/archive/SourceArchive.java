package gold.debug.windowstolinux.shared.source.archive;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Metadata for a reproducible, boundary-checked source archive.
 *
 * <p>可复现且经过边界检查的源码归档元数据。
 *
 * @param archivePath the {@code archivePath} value / {@code archivePath} 值
 * @param contentSha256 the {@code contentSha256} value / {@code contentSha256} 值
 * @param fileCount the {@code fileCount} value / {@code fileCount} 值
 * @param byteCount the {@code byteCount} value / {@code byteCount} 值
 * @param uncompressedByteCount the {@code uncompressedByteCount} value / {@code uncompressedByteCount} 值
 * @param excludedEntries the {@code excludedEntries} value / {@code excludedEntries} 值
 */
public record SourceArchive(
        Path archivePath,
        String contentSha256,
        long fileCount,
        long byteCount,
        long uncompressedByteCount,
        List<String> excludedEntries
) {
    /**
     * Creates a {@code SourceArchive} instance.
     *
     * <p>创建 {@code SourceArchive} 实例。
     *
     * @param archivePath the {@code archivePath} value / {@code archivePath} 值
     * @param contentSha256 the {@code contentSha256} value / {@code contentSha256} 值
     * @param fileCount the {@code fileCount} value / {@code fileCount} 值
     * @param byteCount the {@code byteCount} value / {@code byteCount} 值
     * @param uncompressedByteCount the {@code uncompressedByteCount} value / {@code uncompressedByteCount} 值
     * @param excludedEntries the {@code excludedEntries} value / {@code excludedEntries} 值
     * @throws IllegalArgumentException if an argument violates the required constraints / 参数违反必要约束时
     * @throws NullPointerException if a required argument is {@code null} / 必要参数为 {@code null} 时
     */
    public SourceArchive {
        archivePath = Objects.requireNonNull(archivePath, "archivePath").toAbsolutePath().normalize();
        Path archiveFileName = archivePath.getFileName();
        if (archiveFileName == null || !archiveFileName.toString().toLowerCase(Locale.ROOT).endsWith(".tar.gz")) {
            throw new IllegalArgumentException("archivePath must end in .tar.gz");
        }
        contentSha256 = requireSha256(contentSha256);
        if (fileCount < 0 || byteCount < 0 || uncompressedByteCount < 0) {
            throw new IllegalArgumentException("archive counters must not be negative");
        }
        excludedEntries = List.copyOf(Objects.requireNonNull(excludedEntries, "excludedEntries"));
    }

    private static String requireSha256(String value) {
        Objects.requireNonNull(value, "contentSha256");
        if (!value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("contentSha256 must be lowercase SHA-256");
        }
        return value;
    }
}
