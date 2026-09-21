package gold.debug.windowstolinux.shared.git;

import java.util.Objects;

/**
 * A user-selected mutable or immutable Git reference that is resolved to a commit before analysis.
 *
 *  <p>用户选择的可变或不可变 Git 引用；分析前会将其解析为 Commit。
 */
public sealed interface GitReference permits GitReference.Branch, GitReference.Tag, GitReference.Commit, GitReference.DefaultBranch {
    /**
     * Remote HEAD, resolved and pinned by the controlled fetch before analysis. / 远端 HEAD，在分析前由受控抓取解析并固定。
     */
    record DefaultBranch() implements GitReference {
        /**
         * Returns the remote default reference. / 返回远端默认引用。
         *
         * @return the remote default reference / 远端默认引用
         */
        @Override public String value() { return "HEAD"; }
    }
    /**
     * Returns the exact reference text passed as one Git argument.
     *
     *  <p>返回作为单个 Git 参数传递的精确引用文本。
     *
     * @return the validated reference / 已验证的引用
     */
    String value();

    /**
     * A branch reference. / 分支引用。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     */
    record Branch(String value) implements GitReference {
        /**
         * Creates a branch reference. / 创建分支引用。
         *
         * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
         */
        public Branch {
            value = requireName(value, "branch");
        }
    }

    /**
     * A tag reference. / 标签引用。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     */
    record Tag(String value) implements GitReference {
        /**
         * Creates a tag reference. / 创建标签引用。
         *
         * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
         */
        public Tag {
            value = requireName(value, "tag");
        }
    }

    /**
     * A full immutable commit identifier. / 完整不可变 Commit 标识。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     */
    record Commit(String value) implements GitReference {
        /**
         * Creates a commit reference. / 创建 Commit 引用。
         *
         * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
         * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
         * @throws NullPointerException if a required input is absent / 必需输入缺失时
         */
        public Commit {
            value = Objects.requireNonNull(value, "commit").trim().toLowerCase(java.util.Locale.ROOT);
            if (!value.matches("[0-9a-f]{40}")) {
                throw new IllegalArgumentException("commit must be a full lowercase SHA-1 identifier");
            }
        }
    }

    /**
     * Validates and returns human-readable name or diagnostic field label and rejects inputs outside the declared constraints.
     * <p>校验并返回可读名称或诊断字段标签并拒绝超出已声明约束的输入。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     * @param name human-readable name or diagnostic field label / 可读名称或诊断字段标签
     * @return require name text / 要求名称文本
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    private static String requireName(String value, String name) {
        value = Objects.requireNonNull(value, name).trim();
        if (!value.matches("[A-Za-z0-9][A-Za-z0-9._/-]{0,254}") || value.contains("..")
                || value.contains("//") || value.endsWith("/") || value.startsWith("-")) {
            throw new IllegalArgumentException(name + " is not a safe Git reference name");
        }
        return value;
    }
}
