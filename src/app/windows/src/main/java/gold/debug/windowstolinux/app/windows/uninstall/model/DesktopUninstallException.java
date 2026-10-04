package gold.debug.windowstolinux.app.windows.uninstall.model;

import java.io.IOException;
import java.util.Objects;
import java.util.UUID;

import gold.debug.windowstolinux.appapi.task.result.Problem;

/**
 * Structured checked problem for desktop uninstall boundaries. / 桌面卸载边界的结构化受检失败。
 */
public final class DesktopUninstallException extends IOException {
    /**
     * Structured problem occurrence retained for safe reporting.
     * <p>保留用于安全报告的结构化失败实例。
     */
    private final Problem problem;

    /**
     * Validates and binds the inputs required by desktop uninstall exception.
     * <p>校验并绑定Desktop卸载异常所需输入。
     *
     * @param problem structured problem occurrence retained for safe reporting / 保留用于安全报告的结构化失败实例
     * @param cause original problem retained as the nested cause / 保留为嵌套原因的原始失败
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    private DesktopUninstallException(Problem problem, Throwable cause) {
        super(Objects.requireNonNull(problem, "problem").message(), cause);
        this.problem = problem;
    }

    /**
     * Creates a typed uninstall problem. / 创建类型化卸载失败。
     *
     * @param type selected member of the supported type set / 受支持类型集合中的所选项
     * @param diagnostic bounded non-secret detail for diagnostic reporting / 用于诊断报告的有界非秘密详情
     * @return a typed uninstall problem / 类型化卸载失败
     */
    public static DesktopUninstallException create(DesktopUninstallFailureType type, String diagnostic) {
        return create(type, diagnostic, null);
    }

    /** Creates typed uninstall evidence while preserving its original cause. / 保留原原因并创建类型化卸载依据。
     * @param type exact uninstall classification / 精确卸载分类
     * @param diagnostic non-secret diagnostic message / 非秘密诊断消息
     * @param cause original failure cause / 原失败原因
     * @return checked exception retaining immutable public evidence / 保留不可变公开依据的受检异常
     * @throws NullPointerException if type or diagnostic is absent / 类型或诊断缺失时
     * @throws IllegalArgumentException if diagnostic is blank / 诊断为空时 */
    public static DesktopUninstallException create(DesktopUninstallFailureType type, String diagnostic,
                                                   Throwable cause) {
        return new DesktopUninstallException(
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
