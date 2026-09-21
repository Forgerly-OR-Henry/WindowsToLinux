package gold.debug.windowstolinux.shared.model.project;

import java.net.URI;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable source identity used to bind analysis, target-host build, and deployment records.
 *
 *  <p>用于绑定分析、目标机构建和部署记录的不可变源码身份。
 *
 * @param sourceSha256 the deterministic source archive digest / 确定性源码归档摘要
 * @param commit the optional pinned Git commit / 可选的固定 Git Commit
 * @param remote the credential-free Git remote when the source was checked out from Git / 源码来自 Git 检出时不含凭据的 Git 远端
 * @param submoduleCommits the pinned submodule commits / 固定的子模块 Commit
 */
public record SourceRevision(String sourceSha256, Optional<String> commit, Optional<URI> remote,
                             Map<String, String> submoduleCommits) {
    /**
     * Validates and binds the inputs required by source revision.
     * <p>校验并绑定源码修订所需输入。
     *
     * @param sourceSha256 SHA-256 identity of the frozen source snapshot / 已冻结源码快照的 SHA-256 身份
     * @param commit the optional pinned Git commit / 可选的固定 Git Commit
     * @param remote the credential-free Git remote when the source was checked out from Git / 源码来自 Git 检出时不含凭据的 Git 远端
     * @param submoduleCommits the pinned submodule commits / 固定的子模块 Commit
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
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

    /**
     * Creates a local or legacy source revision without a Git provenance record. / 创建没有 Git 溯源记录的本地或旧版源码修订。
     *
     * @param sourceSha256 SHA-256 identity of the frozen source snapshot / 已冻结源码快照的 SHA-256 身份
     * @param commit the optional pinned Git commit / 可选的固定 Git Commit
     * @param submoduleCommits the pinned submodule commits / 固定的子模块 Commit
     */
    public SourceRevision(String sourceSha256, Optional<String> commit, Map<String, String> submoduleCommits) {
        this(sourceSha256, commit, Optional.empty(), submoduleCommits);
    }

    /**
     * Validates and returns lower-case hexadecimal SHA-256 digest and rejects inputs outside the declared constraints.
     * <p>校验并返回小写十六进制 SHA-256 摘要并拒绝超出已声明约束的输入。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     * @param name human-readable name or diagnostic field label / 可读名称或诊断字段标签
     * @return require sha 256 text / 要求SHA256文本
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    private static String requireSha256(String value, String name) {
        value = Objects.requireNonNull(value, name).trim().toLowerCase(java.util.Locale.ROOT);
        if (!value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(name + " must be a full lowercase SHA-256 identifier");
        }
        return value;
    }

    /**
     * Validates and returns full Git object identifier in hexadecimal SHA-1 form and rejects inputs outside the declared constraints.
     * <p>校验并返回十六进制 SHA-1 形式的完整 Git 对象标识并拒绝超出已声明约束的输入。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     * @param name human-readable name or diagnostic field label / 可读名称或诊断字段标签
     * @return require sha 1 text / 要求SHA1文本
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    private static String requireSha1(String value, String name) {
        value = Objects.requireNonNull(value, name).trim().toLowerCase(java.util.Locale.ROOT);
        if (!value.matches("[0-9a-f]{40}")) {
            throw new IllegalArgumentException(name + " must be a full lowercase SHA-1 identifier");
        }
        return value;
    }

    /**
     * Validates and returns credential free remote and rejects inputs outside the declared constraints.
     * <p>校验并返回凭据剩余远端并拒绝超出已声明约束的输入。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     * @return constructed or resolved URI / 构造或解析得到的URI
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
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
