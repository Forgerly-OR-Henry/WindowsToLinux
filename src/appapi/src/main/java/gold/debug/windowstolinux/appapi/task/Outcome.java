package gold.debug.windowstolinux.appapi.task;

/**
 * Business result independent of remaining resource cleanup. / 独立于尚待完成资源清理的业务结果。
 */
public enum Outcome {
    /**
     * The requested business result was confirmed. / 请求的业务结果已确认。
     */
    SUCCEEDED,
    /**
     * The business operation failed with known evidence. / 已有证据确认业务操作失败。
     */
    FAILED,
    /**
     * Cooperative cancellation was confirmed. / 协作取消已确认。
     */
    CANCELLED,
    /**
     * The effect or completion cannot yet be confirmed. / 副作用或完成状态尚无法确认。
     */
    UNKNOWN
}
