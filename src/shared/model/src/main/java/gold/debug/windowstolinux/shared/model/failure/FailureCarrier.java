package gold.debug.windowstolinux.shared.model.failure;

/**
 * Implemented by exceptions that carry one structured failure. / 由携带单个结构化失败的异常实现。
 */
public interface FailureCarrier {
    /**
     * Returns the structured failure occurrence. / 返回结构化失败实例。
     *
     * @return the structured failure occurrence / 结构化失败实例
     */
    FailureDescriptor failure();
}
