package gold.debug.windowstolinux.app.main.diagnostic;

import java.util.Objects;

import gold.debug.windowstolinux.appapi.task.Problem;

/**
 * Structured failure that prevents the desktop from starting safely. / 阻止桌面安全启动的结构化失败。
 */
public final class DesktopStartupException extends RuntimeException {
    /**
     * Structured failure occurrence retained for safe reporting.
     * <p>保留用于安全报告的结构化失败实例。
     */
    private final Problem problem;

    /**
     * Creates a startup failure occurrence. / 创建一次启动失败。
     *
     * @param failure structured failure occurrence retained for safe reporting / 保留用于安全报告的结构化失败实例
     * @param cause original failure retained as the nested cause / 保留为嵌套原因的原始失败
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public DesktopStartupException(Problem failure, Throwable cause) {
        super(Objects.requireNonNull(failure, "failure").message(), cause);
        this.problem = failure;
    }

    /**
     * Creates a typed startup failure. / 创建类型化启动失败。
     *
     * @param type selected member of the supported type set / 受支持类型集合中的所选项
     * @param diagnostic bounded non-secret detail for diagnostic reporting / 用于诊断报告的有界非秘密详情
     * @param cause original failure retained as the nested cause / 保留为嵌套原因的原始失败
     * @return a typed startup failure / 类型化启动失败
     */
    public static DesktopStartupException create(DesktopSystemFailureType type, String diagnostic, Throwable cause) {
        return new DesktopStartupException(type.problem(java.util.UUID.randomUUID(), diagnostic), cause);
    }

    /**
     * Returns structured failure occurrence retained for safe reporting.
     * <p>返回保留用于安全报告的结构化失败实例。
     *
     * @return structured failure occurrence retained for safe reporting / 保留用于安全报告的结构化失败实例
     */
    public Problem problem() {
        return problem;
    }
}
