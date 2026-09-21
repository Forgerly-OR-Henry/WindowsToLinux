package gold.debug.windowstolinux.app.windows.recovery;

import gold.debug.windowstolinux.shared.model.failure.*;

/**
 * Safe browser failure without terminal, command or page contents. / 不含终端、命令或页面内容的安全浏览器失败。
 */
public final class BrowserRecoveryException extends RuntimeException implements FailureCarrier {
    /**
     * Structured failure occurrence retained for safe reporting.
     * <p>保留用于安全报告的结构化失败实例。
     */
    private final FailureDescriptor failure;
    /**
     * Binds the supplied dependencies and state for browser recovery exception.
     * <p>为浏览器恢复异常绑定传入的依赖及状态。
     *
     * @param type selected member of the supported type set / 受支持类型集合中的所选项
     * @param cause original failure retained as the nested cause / 保留为嵌套原因的原始失败
     */
    public BrowserRecoveryException(BrowserRecoveryFailureType type, Throwable cause) {
        super("Isolated browser operation failed", cause);
        failure = FailureDescriptor.create(type, OperationIdentity.create(), "Isolated browser operation failed");
    }
    /**
     * Returns structured failure occurrence retained for safe reporting.
     * <p>返回保留用于安全报告的结构化失败实例。
     *
     * @return structured failure occurrence retained for safe reporting / 保留用于安全报告的结构化失败实例
     */
    @Override public FailureDescriptor failure() { return failure; }
}
