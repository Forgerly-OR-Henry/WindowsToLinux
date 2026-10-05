package gold.debug.windowstolinux.mod.git.api;

/** Explicit source reference resolved to one immutable commit. / 解析为单个不可变提交的明确来源引用。 */
public enum GitReferenceKind {
    /** Original remote default branch. / 原远端默认分支。 */
    DEFAULT_BRANCH,
    /** Explicit remote branch. / 明确远端分支。 */
    BRANCH,
    /** Explicit remote tag. / 明确远端标签。 */
    TAG,
    /** Full immutable SHA-1 commit. / 完整不可变 SHA-1 提交。 */
    COMMIT
}
