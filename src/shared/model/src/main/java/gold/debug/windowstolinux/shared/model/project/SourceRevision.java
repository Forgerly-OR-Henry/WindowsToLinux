package gold.debug.windowstolinux.shared.model.project;

import java.net.URI;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable source identity used to bind analysis, target-host build, and deployment records.
 *
 * <p>用于绑定分析、目标机构建和部署记录的不可变源码身份。
 *
 * @param sourceSha256 the deterministic source archive digest / 确定性源码归档摘要
 * @param commit the optional pinned Git commit / 可选的固定 Git Commit
 * @param remote the credential-free Git remote when the source was checked out from Git / 源码来自 Git 检出时不含凭据的 Git 远端
 * @param submoduleCommits the pinned submodule commits / 固定的子模块 Commit
 */
public record SourceRevision(String sourceSha256, Optional<String> commit, Optional<URI> remote,
                             Map<String, String> submoduleCommits) {
    /**
     * Creates a {@code SourceRevision} instance.
     *
     * <p>创建 {@code SourceRevision} 实例。
     */
    public SourceRevision {
        sourceSha256 = requireSha256(sourceSha256, "sourceSha256");
        commit = Objects.requireNonNull(commit, "commit").map(value -> requireSha1(value, "commit"));
        remote = Objects.requireNonNull(remote, "remote").map(SourceRevision::requireCredentialFreeRemote);
        submoduleCommits = Map.copyOf(Objects.requireNonNull(submoduleCommits, "submoduleCommits"));
        submoduleCommits.forEach((path, value) -> {
            if (path == null || !path.matches("[A-Za-z0-9][A-Za-z0-9._/-]{0,511}") || path.contains("..")) {
                throw new IllegalArgumentException("submodule paths must be bounded relative paths");
            }
            requireSha1(value, "submodule commit");
        });
        if (commit.isEmpty() && !submoduleCommits.isEmpty()) {
            throw new IllegalArgumentException("submodule commits require a pinned parent commit");
        }
        if (remote.isPresent() && commit.isEmpty()) {
            throw new IllegalArgumentException("a Git remote requires one pinned Git commit");
        }
    }

    /** Creates a local or legacy source revision without a Git provenance record. / 创建没有 Git 溯源记录的本地或旧版源码修订。 */
    public SourceRevision(String sourceSha256, Optional<String> commit, Map<String, String> submoduleCommits) {
        this(sourceSha256, commit, Optional.empty(), submoduleCommits);
    }

    private static String requireSha256(String value, String name) {
        value = Objects.requireNonNull(value, name).trim().toLowerCase(java.util.Locale.ROOT);
        if (!value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(name + " must be a full lowercase SHA-256 identifier");
        }
        return value;
    }

    private static String requireSha1(String value, String name) {
        value = Objects.requireNonNull(value, name).trim().toLowerCase(java.util.Locale.ROOT);
        if (!value.matches("[0-9a-f]{40}")) {
            throw new IllegalArgumentException(name + " must be a full lowercase SHA-1 identifier");
        }
        return value;
    }

    private static URI requireCredentialFreeRemote(URI value) {
        URI normalized = Objects.requireNonNull(value, "remote").normalize();
        String scheme = normalized.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("https") || scheme.equalsIgnoreCase("ssh")
                || scheme.equalsIgnoreCase("file"))
                || (!scheme.equalsIgnoreCase("file") && (normalized.getHost() == null || normalized.getHost().isBlank()))
                || normalized.getRawUserInfo() != null || normalized.getRawQuery() != null
                || normalized.getRawFragment() != null || normalized.toString().length() > 2048) {
            throw new IllegalArgumentException("remote must be a bounded credential-free Git URI");
        }
        return normalized;
    }
}
