package gold.debug.windowstolinux.mod.deployment.file.git;

import java.util.Objects;

import gold.debug.windowstolinux.appapi.task.ApiException;
import gold.debug.windowstolinux.mod.deployment.file.*;

/** Immutable resolved Git identity with separately owned analysis material and archive. / 不可变解析 Git 身份及分别所属的分析素材和归档。
 * @param remote original credential-free remote / 原不含凭据远端
 * @param commit exact immutable commit / 精确不可变提交
 * @param source original frozen source material / 原冻结来源素材
 * @param archive independently frozen compressed archive / 独立冻结压缩归档 */
public record DeploymentGitMaterial(DeploymentGitRemote remote, String commit, DeploymentSourceMaterial source,
                                    DeploymentSourceArchive archive) implements AutoCloseable {
    /** Retains exact Git identity and the original platform handles. / 保留精确 Git 身份及原平台句柄。
     * @param remote original remote / 原远端
     * @param commit full SHA-1 commit / 完整 SHA-1 提交
     * @param source original source / 原来源
     * @param archive original archive / 原归档 */
    public DeploymentGitMaterial {
        Objects.requireNonNull(remote);
        Objects.requireNonNull(source);
        Objects.requireNonNull(archive);
        if (commit == null || !commit.matches("[0-9a-f]{40}"))
            throw new IllegalArgumentException("Invalid Git commit identity");
    }

    /** Releases both original frozen resources, preserving any failed lease for retry. / 释放两个原冻结资源，保留任何失败租约供重试。
     * @throws ApiException if physical or durable cleanup remains uncertain / 物理或持久清理仍不确定时 */
    @Override
    public void close() throws ApiException {
        try {
            archive.close();
        } finally {
            source.close();
        }
    }
}
