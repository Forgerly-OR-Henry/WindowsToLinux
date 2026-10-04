package gold.debug.windowstolinux.app.windows.update.model;

import java.io.IOException;
import java.util.Objects;
import java.util.UUID;

import gold.debug.windowstolinux.appapi.task.result.Problem;

/**
 * Structured checked problem for desktop update verification and replacement. / 桌面更新验证及替换的结构化受检失败。
 */
public final class DesktopUpdateException extends IOException {
    /**
     * Structured problem occurrence retained for safe reporting.
     * <p>保留用于安全报告的结构化失败实例。
     */
    private final Problem problem;

    /**
     * Validates and binds the inputs required by desktop update exception.
     * <p>校验并绑定Desktop更新异常所需输入。
     *
     * @param problem structured problem occurrence retained for safe reporting / 保留用于安全报告的结构化失败实例
     * @param cause original problem retained as the nested cause / 保留为嵌套原因的原始失败
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    private DesktopUpdateException(Problem problem, Throwable cause) {
        super(Objects.requireNonNull(problem, "problem").message(), cause);
        this.problem = problem;
    }

    /**
     * Creates a typed update problem. / 创建类型化更新失败。
     *
     * @param type selected member of the supported type set / 受支持类型集合中的所选项
     * @param diagnostic bounded non-secret detail for diagnostic reporting / 用于诊断报告的有界非秘密详情
     * @return a typed update problem / 类型化更新失败
     */
    public static DesktopUpdateException create(DesktopUpdateFailureType type, String diagnostic) {
        return create(type, diagnostic, null);
    }

    /**
     * Creates a typed update problem with a safe cause. / 创建带安全原因的类型化更新失败。
     *
     * @param type selected member of the supported type set / 受支持类型集合中的所选项
     * @param diagnostic bounded non-secret detail for diagnostic reporting / 用于诊断报告的有界非秘密详情
     * @param cause original problem retained as the nested cause / 保留为嵌套原因的原始失败
     * @return a typed update problem with a safe cause / 带安全原因的类型化更新失败
     */
    public static DesktopUpdateException create(DesktopUpdateFailureType type, String diagnostic, Throwable cause) {
        return new DesktopUpdateException(Objects.requireNonNull(type, "type").problem(UUID.randomUUID(), diagnostic),
            cause);
    }

    /**
     * Returns structured problem occurrence retained for safe reporting.
     * <p>返回保留用于安全报告的结构化失败实例。
     *
     * @return structured problem occurrence retained for safe reporting / 保留用于安全报告的结构化失败实例
     */
    public Problem problem() {
        return problem;
    }
}
