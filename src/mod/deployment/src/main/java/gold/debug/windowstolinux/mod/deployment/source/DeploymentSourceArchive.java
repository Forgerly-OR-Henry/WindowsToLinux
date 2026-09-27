package gold.debug.windowstolinux.mod.deployment.source;

import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;

import gold.debug.windowstolinux.appapi.source.SourceSnapshot;
import gold.debug.windowstolinux.appapi.task.ApiException;

/** Frozen deployment archive with the digest of its actual compressed bytes. / 带实际压缩字节摘要的冻结部署归档。
 * @param snapshot original platform archive snapshot / 原平台归档快照
 * @param sha256 digest of the tar.gz bytes, not the source manifest / tar.gz 字节摘要，区别于来源清单
 * @param byteCount compressed archive bytes / 压缩归档字节数
 * @param uncompressedByteCount original file bytes excluding tar headers / 不含 tar 头的原文件字节数
 * @param originSnapshotId original source snapshot used to build this archive / 构建归档所用的原源码快照
 * @param sourceManifestSha256 digest of that original source snapshot / 原源码快照的清单摘要 */
public record DeploymentSourceArchive(SourceSnapshot snapshot, String sha256, long byteCount,
        long uncompressedByteCount, UUID originSnapshotId, String sourceManifestSha256) implements AutoCloseable {
    /** Retains the exact original snapshot and measured byte identity. / 保留精确原快照及实测字节身份。
     * @param snapshot original snapshot / 原快照
     * @param sha256 compressed content digest / 压缩内容摘要
     * @param byteCount compressed length / 压缩长度
     * @param uncompressedByteCount original file bytes / 原文件字节数
     * @param originSnapshotId original source snapshot / 原源码快照
     * @param sourceManifestSha256 original source manifest digest / 原源码清单摘要 */
    public DeploymentSourceArchive {
        Objects.requireNonNull(snapshot);
        Objects.requireNonNull(originSnapshotId);
        if (sha256 == null || !sha256.matches("[0-9a-f]{64}") || byteCount < 0 || uncompressedByteCount < 0)
            throw new IllegalArgumentException("Invalid source archive identity");
        if (sourceManifestSha256 == null || !sourceManifestSha256.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("Invalid source manifest identity");
    }

    /** Revalidates the frozen archive before returning its platform-owned location. / 返回平台所属位置前重新核验冻结归档。
     * @return exact source.tar.gz location / 精确 source.tar.gz 位置 */
    public Path path() {
        return snapshot.directory().resolve("source.tar.gz");
    }

    /** Releases the original archive files and durable occupancy. / 释放原归档文件及持久占用。
     * @throws ApiException if physical or durable cleanup needs retry / 物理或持久清理需要重试时 */
    @Override
    public void close() throws ApiException {
        snapshot.close();
    }
}
