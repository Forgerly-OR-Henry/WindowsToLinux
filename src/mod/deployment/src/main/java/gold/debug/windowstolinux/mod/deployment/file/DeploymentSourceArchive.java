package gold.debug.windowstolinux.mod.deployment.file;

import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;

import gold.debug.windowstolinux.appapi.file.FileSnapshot;
import gold.debug.windowstolinux.appapi.task.ApiException;

/** Frozen deployment archive with the digest of its actual compressed bytes. / 带实际压缩字节摘要的冻结部署归档。
 * @param snapshot original platform archive snapshot / 原平台归档快照
 * @param sha256 digest of the tar.gz bytes, not the source manifest / tar.gz 字节摘要，区别于来源清单
 * @param byteCount compressed archive bytes / 压缩归档字节数
 * @param uncompressedByteCount original file bytes excluding tar headers / 不含 tar 头的原文件字节数
 * @param originSnapshotId original source snapshot used to build this archive / 构建归档所用的原源码快照
 * @param sourceManifestSha256 digest of that original source snapshot / 原源码快照的清单摘要
 * @param relativeRoot exact subtree under the original snapshot, empty for the whole root / 原快照下的精确子树，全根为空 */
public record DeploymentSourceArchive(FileSnapshot snapshot, String sha256, long byteCount,
                                      long uncompressedByteCount, UUID originSnapshotId, String sourceManifestSha256,
                                      String relativeRoot) implements AutoCloseable {
    /** Retains the exact original snapshot and measured byte identity. / 保留精确原快照及实测字节身份。
     * @param snapshot original snapshot / 原快照
     * @param sha256 compressed content digest / 压缩内容摘要
     * @param byteCount compressed length / 压缩长度
     * @param uncompressedByteCount original file bytes / 原文件字节数
     * @param originSnapshotId original source snapshot / 原源码快照
     * @param sourceManifestSha256 original source manifest digest / 原源码清单摘要
     * @param relativeRoot original snapshot subtree / 原快照子树 */
    public DeploymentSourceArchive {
        Objects.requireNonNull(snapshot);
        Objects.requireNonNull(originSnapshotId);
        if (sha256 == null || !sha256.matches("[0-9a-f]{64}") || byteCount < 0 || uncompressedByteCount < 0)
            throw new IllegalArgumentException("Invalid source archive identity");
        if (sourceManifestSha256 == null || !sourceManifestSha256.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("Invalid source manifest identity");
        if (!validRelativeRoot(relativeRoot))
            throw new IllegalArgumentException("Invalid archive source root");
    }

    /** Accepts only canonical snapshot-relative path segments, including legitimate Unicode names. / 仅接受规范快照相对路径段，保留合法 Unicode 名称。
     * @param root relative subtree or empty whole root / 相对子树或空全根
     * @return whether the root cannot escape the snapshot / 是否无法逃出快照 */
    static boolean validRelativeRoot(String root) {
        if (root == null)
            return false;
        if (root.isEmpty())
            return true;
        if (root.startsWith("/") || root.endsWith("/") || root.indexOf('\\') >= 0 || root.indexOf(':') >= 0)
            return false;
        for (String part : root.split("/", -1)) {
            if (part.isEmpty() || part.equals(".") || part.equals("..")
                || part.codePoints().anyMatch(Character::isISOControl))
                return false;
        }
        return true;
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
