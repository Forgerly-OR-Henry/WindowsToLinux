package gold.debug.windowstolinux.shared.model.archive;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;

/**
 * A locally prepared {@code tar.gz} source archive with compressed and post-extraction size bounds.
 *
 *  <p>本地准备的 {@code tar.gz} 源码归档，包含压缩大小和解压后大小边界。
 *
 * @param localArchive local archive / 本地归档
 * @param contentSha256 content sha 256 / 内容SHA256
 * @param byteCount measured content length in bytes / 实测内容长度，单位为字节
 * @param uncompressedByteCount uncompressed byte count / 未压缩字节数量
 */
public record SourceArchiveDescriptor(Path localArchive, String contentSha256, long byteCount, long uncompressedByteCount) {
    /**
     * Validates and binds the inputs required by source archive descriptor.
     * <p>校验并绑定源码归档描述符所需输入。
     *
     * @param localArchive local archive / 本地归档
     * @param contentSha256 content sha 256 / 内容SHA256
     * @param byteCount measured content length in bytes / 实测内容长度，单位为字节
     * @param uncompressedByteCount uncompressed byte count / 未压缩字节数量
     * @throws IllegalArgumentException if an argument violates the required constraints / 参数违反必要约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
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
