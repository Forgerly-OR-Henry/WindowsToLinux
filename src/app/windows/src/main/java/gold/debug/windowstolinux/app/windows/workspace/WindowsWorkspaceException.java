package gold.debug.windowstolinux.app.windows.workspace;

import java.io.IOException;
import java.util.Objects;
import java.util.UUID;

import gold.debug.windowstolinux.appapi.task.Problem;

/**
 * Structured checked problem for the desktop-owned Windows workspace. / 桌面持有的 Windows 工作区结构化受检失败。
 */
public final class WindowsWorkspaceException extends IOException {
    /**
     * Structured problem occurrence retained for safe reporting.
     * <p>保留用于安全报告的结构化失败实例。
     */
    private final Problem problem;

    /**
     * Creates one workspace problem. / 创建一次工作区失败。
     *
     * @param problem structured problem occurrence retained for safe reporting / 保留用于安全报告的结构化失败实例
     * @param cause original problem retained as the nested cause / 保留为嵌套原因的原始失败
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public WindowsWorkspaceException(Problem problem, Throwable cause) {
        super(Objects.requireNonNull(problem, "problem").message(), cause);
        this.problem = problem;
    }

    /**
     * Creates a typed workspace problem. / 创建类型化工作区失败。
     *
     * @param type selected member of the supported type set / 受支持类型集合中的所选项
     * @param diagnostic bounded non-secret detail for diagnostic reporting / 用于诊断报告的有界非秘密详情
     * @param cause original problem retained as the nested cause / 保留为嵌套原因的原始失败
     * @return a typed workspace problem / 类型化工作区失败
     */
    public static WindowsWorkspaceException create(WindowsWorkspaceFailureType type, String diagnostic,
            Throwable cause) {
        return new WindowsWorkspaceException(
                Objects.requireNonNull(type, "type").problem(UUID.randomUUID(), diagnostic), cause);
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
