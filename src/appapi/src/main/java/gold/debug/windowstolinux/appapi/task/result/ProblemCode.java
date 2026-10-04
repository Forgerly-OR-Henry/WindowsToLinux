package gold.debug.windowstolinux.appapi.task.result;

/**
 * Stable failure category for public capability boundaries. / 公开能力边界使用的稳定失败类别。
 */
public enum ProblemCode {
    /**
     * Input violates the public capability contract. / 输入违反公开能力契约。
     */
    INVALID_INPUT,
    /**
     * A required capability is currently unavailable. / 所需能力当前不可用。
     */
    UNAVAILABLE,
    /**
     * Requested versions or contracts are incompatible. / 请求的版本或契约不兼容。
     */
    INCOMPATIBLE,
    /**
     * The caller lacks authorization for this resource. / 调用方缺少此资源的授权。
     */
    ACCESS_DENIED,
    /**
     * Existing resource occupancy prevents admission. / 已有资源占用阻止接纳。
     */
    BUSY,
    /**
     * The requested identity has no matching resource. / 请求的身份没有匹配资源。
     */
    NOT_FOUND,
    /**
     * An input or output operation failed. / 输入或输出操作失败。
     */
    IO_FAILURE,
    /**
     * The operation exceeded its allowed deadline. / 操作超过允许期限。
     */
    TIMEOUT,
    /**
     * Cooperative cancellation was confirmed. / 协作取消已确认。
     */
    CANCELLED,
    /**
     * The effect or completion cannot yet be confirmed. / 副作用或完成状态尚无法确认。
     */
    UNKNOWN,
    /**
     * An implementation violated an accepted public contract. / 实现违反已接受的公开契约。
     */
    CONTRACT_VIOLATION
}
