package gold.debug.windowstolinux.shared.git;

import gold.debug.windowstolinux.shared.model.failure.FailureCarrier;
import gold.debug.windowstolinux.shared.model.failure.FailureDescriptor;
import gold.debug.windowstolinux.shared.model.failure.OperationIdentity;

import java.util.Objects;

/**
 * A structured Git snapshot failure that never exposes remote output or credentials. / 不暴露远端输出或凭据的结构化 Git 快照失败。
 */
public final class GitSnapshotException extends Exception implements FailureCarrier {
    /**
     * Structured failure occurrence retained for safe reporting.
     * <p>保留用于安全报告的结构化失败实例。
     */
    private final FailureDescriptor failure;

    /**
     * Creates one Git snapshot failure occurrence. / 创建一次 Git 快照失败。
     *
     * @param failure structured failure occurrence retained for safe reporting / 保留用于安全报告的结构化失败实例
     * @param cause original failure retained as the nested cause / 保留为嵌套原因的原始失败
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public GitSnapshotException(FailureDescriptor failure, Throwable cause) {
        super(Objects.requireNonNull(failure, "failure").diagnostic(), cause);
        this.failure = failure;
    }

    /**
     * Creates a typed Git snapshot failure. / 创建类型化 Git 快照失败。
     *
     * @param type selected member of the supported type set / 受支持类型集合中的所选项
     * @param diagnostic bounded non-secret detail for diagnostic reporting / 用于诊断报告的有界非秘密详情
     * @return a typed Git snapshot failure / 类型化 Git 快照失败
     */
    public static GitSnapshotException create(GitSnapshotFailureType type, String diagnostic) {
        return create(type, diagnostic, null);
    }

    /**
     * Creates a typed Git snapshot failure with its original cause. / 创建带原始原因的类型化 Git 快照失败。
     *
     * @param type selected member of the supported type set / 受支持类型集合中的所选项
     * @param diagnostic bounded non-secret detail for diagnostic reporting / 用于诊断报告的有界非秘密详情
     * @param cause original failure retained as the nested cause / 保留为嵌套原因的原始失败
     * @return a typed Git snapshot failure with its original cause / 带原始原因的类型化 Git 快照失败
     */
    public static GitSnapshotException create(GitSnapshotFailureType type, String diagnostic, Throwable cause) {
        return new GitSnapshotException(
                FailureDescriptor.create(type, OperationIdentity.create(), diagnostic), cause);
    }

    /**
     * Returns structured failure occurrence retained for safe reporting.
     * <p>返回保留用于安全报告的结构化失败实例。
     *
     * @return structured failure occurrence retained for safe reporting / 保留用于安全报告的结构化失败实例
     */
    @Override public FailureDescriptor failure() { return failure; }
}
