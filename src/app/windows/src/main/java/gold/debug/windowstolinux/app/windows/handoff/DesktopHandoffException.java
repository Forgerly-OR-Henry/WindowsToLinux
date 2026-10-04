package gold.debug.windowstolinux.app.windows.handoff;

import java.io.IOException;
import java.util.Objects;
import java.util.UUID;

import gold.debug.windowstolinux.appapi.task.result.Problem;

/**
 * Structured checked problem for the desktop-owned Windows handoff. / 桌面持有的 Windows 交接结构化受检失败。
 */
public final class DesktopHandoffException extends IOException {
    /**
     * Structured problem occurrence retained for safe reporting.
     * <p>保留用于安全报告的结构化失败实例。
     */
    private final Problem problem;

    /**
     * Creates one handoff problem. / 创建一次交接失败。
     *
     * @param problem structured problem occurrence retained for safe reporting / 保留用于安全报告的结构化失败实例
     * @param cause original problem retained as the nested cause / 保留为嵌套原因的原始失败
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public DesktopHandoffException(Problem problem, Throwable cause) {
        super(Objects.requireNonNull(problem, "problem").message(), cause);
        this.problem = problem;
    }

    /**
     * Creates a typed handoff problem. / 创建类型化交接失败。
     *
     * @param type selected member of the supported type set / 受支持类型集合中的所选项
     * @param diagnostic bounded non-secret detail for diagnostic reporting / 用于诊断报告的有界非秘密详情
     * @param cause original problem retained as the nested cause / 保留为嵌套原因的原始失败
     * @return a typed handoff problem / 类型化交接失败
     */
    public static DesktopHandoffException create(DesktopHandoffFailureType type, String diagnostic,
                                                 Throwable cause) {
        return new DesktopHandoffException(
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
