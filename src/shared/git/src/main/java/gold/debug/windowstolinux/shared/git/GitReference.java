package gold.debug.windowstolinux.shared.git;

import java.util.Objects;

/**
 * A user-selected mutable or immutable Git reference that is resolved to a commit before analysis.
 *
 * <p>用户选择的可变或不可变 Git 引用；分析前会将其解析为 Commit。
 */
public sealed interface GitReference permits GitReference.Branch, GitReference.Tag, GitReference.Commit {
    /**
     * Returns the exact reference text passed as one Git argument.
     *
     * <p>返回作为单个 Git 参数传递的精确引用文本。
     *
     * @return the validated reference / 已验证的引用
     */
    String value();

    /** A branch reference. / 分支引用。 */
    record Branch(String value) implements GitReference {
        /** Creates a branch reference. / 创建分支引用。 */
        public Branch {
            value = requireName(value, "branch");
        }
    }

    /** A tag reference. / 标签引用。 */
    record Tag(String value) implements GitReference {
        /** Creates a tag reference. / 创建标签引用。 */
        public Tag {
            value = requireName(value, "tag");
        }
    }

    /** A full immutable commit identifier. / 完整不可变 Commit 标识。 */
    record Commit(String value) implements GitReference {
        /** Creates a commit reference. / 创建 Commit 引用。 */
        public Commit {
            value = Objects.requireNonNull(value, "commit").trim().toLowerCase(java.util.Locale.ROOT);
            if (!value.matches("[0-9a-f]{40}")) {
                throw new IllegalArgumentException("commit must be a full lowercase SHA-1 identifier");
            }
        }
    }

    private static String requireName(String value, String name) {
        value = Objects.requireNonNull(value, name).trim();
        if (!value.matches("[A-Za-z0-9][A-Za-z0-9._/-]{0,254}") || value.contains("..")
                || value.contains("//") || value.endsWith("/") || value.startsWith("-")) {
            throw new IllegalArgumentException(name + " is not a safe Git reference name");
        }
        return value;
    }
}
