package gold.debug.windowstolinux.app.windows.workspace;

import java.io.IOException;
import java.util.Objects;

import gold.debug.windowstolinux.shared.model.failure.FailureCarrier;
import gold.debug.windowstolinux.shared.model.failure.FailureDescriptor;
import gold.debug.windowstolinux.shared.model.failure.OperationIdentity;

/**
 * Structured checked failure for the desktop-owned Windows workspace. / 桌面持有的 Windows 工作区结构化受检失败。
 */
public final class WindowsWorkspaceException extends IOException implements FailureCarrier {
    /**
     * Structured failure occurrence retained for safe reporting.
     * <p>保留用于安全报告的结构化失败实例。
     */
    private final FailureDescriptor failure;

    /**
     * Creates one workspace failure. / 创建一次工作区失败。
     *
     * @param failure structured failure occurrence retained for safe reporting / 保留用于安全报告的结构化失败实例
     * @param cause original failure retained as the nested cause / 保留为嵌套原因的原始失败
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public WindowsWorkspaceException(FailureDescriptor failure, Throwable cause) {
        super(Objects.requireNonNull(failure, "failure").diagnostic(), cause);
        this.failure = failure;
    }

    /**
     * Creates a typed workspace failure. / 创建类型化工作区失败。
     *
     * @param type selected member of the supported type set / 受支持类型集合中的所选项
     * @param diagnostic bounded non-secret detail for diagnostic reporting / 用于诊断报告的有界非秘密详情
     * @param cause original failure retained as the nested cause / 保留为嵌套原因的原始失败
     * @return a typed workspace failure / 类型化工作区失败
     */
    public static WindowsWorkspaceException create(WindowsWorkspaceFailureType type, String diagnostic,
            Throwable cause) {
        return new WindowsWorkspaceException(FailureDescriptor.create(type, OperationIdentity.create(), diagnostic),
                cause);
    }

    /**
     * Returns structured failure occurrence retained for safe reporting.
     * <p>返回保留用于安全报告的结构化失败实例。
     *
     * @return structured failure occurrence retained for safe reporting / 保留用于安全报告的结构化失败实例
     */
    @Override
    public FailureDescriptor failure() {
        return failure;
    }
}
