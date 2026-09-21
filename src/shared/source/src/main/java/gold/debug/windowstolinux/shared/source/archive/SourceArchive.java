package gold.debug.windowstolinux.shared.source.archive;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Metadata for a reproducible, boundary-checked source archive.
 *
 *  <p>可复现且经过边界检查的源码归档元数据。
 *
 * @param archivePath archive path / 归档路径
 * @param contentSha256 content sha 256 / 内容SHA256
 * @param fileCount file count / 文件数量
 * @param byteCount measured content length in bytes / 实测内容长度，单位为字节
 * @param uncompressedByteCount uncompressed byte count / 未压缩字节数量
 * @param excludedEntries entries excluded by the safe archive policy / 安全归档策略排除的条目
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
     * Validates and binds the inputs required by source archive.
     * <p>校验并绑定源码归档所需输入。
     *
     * @param archivePath archive path / 归档路径
     * @param contentSha256 content sha 256 / 内容SHA256
     * @param fileCount file count / 文件数量
     * @param byteCount measured content length in bytes / 实测内容长度，单位为字节
     * @param uncompressedByteCount uncompressed byte count / 未压缩字节数量
     * @param excludedEntries entries excluded by the safe archive policy / 安全归档策略排除的条目
     * @throws IllegalArgumentException if an argument violates the required constraints / 参数违反必要约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
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

    /**
     * Validates and returns lower-case hexadecimal SHA-256 digest and rejects inputs outside the declared constraints.
     * <p>校验并返回小写十六进制 SHA-256 摘要并拒绝超出已声明约束的输入。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     * @return require sha 256 text / 要求SHA256文本
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    private static String requireSha256(String value) {
        Objects.requireNonNull(value, "contentSha256");
        if (!value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("contentSha256 must be lowercase SHA-256");
        }
        return value;
    }
}
