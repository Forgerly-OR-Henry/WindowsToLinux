package gold.debug.windowstolinux.shared.git.snapshot;

import gold.debug.windowstolinux.shared.git.remote.GitRemote;
import gold.debug.windowstolinux.shared.source.archive.SourceArchive;

import java.nio.file.Path;
import java.util.Objects;

/**
 * A checkout pinned to one commit and paired with a deterministic source archive.
 *
 * <p>固定到一个 Commit 并配有确定性源码归档的检出结果。
 *
 * @param remote the credential-free remote / 不含凭据的远端
 * @param commit the resolved full commit / 已解析的完整 Commit
 * @param checkoutDirectory the isolated checkout directory / 隔离检出目录
 * @param archive the source-only archive / 纯源码归档
 */
public record GitSnapshot(GitRemote remote, String commit, Path checkoutDirectory, SourceArchive archive) {
    /**
     * Creates a {@code GitSnapshot} instance.
     *
     * <p>创建 {@code GitSnapshot} 实例。
     */
    public GitSnapshot {
        remote = Objects.requireNonNull(remote, "remote");
        commit = Objects.requireNonNull(commit, "commit").trim().toLowerCase(java.util.Locale.ROOT);
        if (!commit.matches("[0-9a-f]{40}")) {
            throw new IllegalArgumentException("commit must be a full lowercase SHA-1 identifier");
        }
        checkoutDirectory = Objects.requireNonNull(checkoutDirectory, "checkoutDirectory").toAbsolutePath().normalize();
        archive = Objects.requireNonNull(archive, "archive");
    }
}
