package gold.debug.windowstolinux.shared.ai.collaboration.invocation;

/**
 * Validation state of exactly one explicitly selected provider invocation. / 对一个显式选择提供者调用的验证状态。
 */
public enum AiInvocationStatus {
    /**
     * The response matched the fixed role schema. / 响应符合固定角色模式。
     */
    VALIDATED,
    /**
     * The selected provider was unavailable or timed out. / 所选提供者不可用或超时。
     */
    UNAVAILABLE,
    /**
     * The selected provider returned invalid or unsafe output. / 所选提供者返回无效或不安全输出。
     */
    INVALID_OUTPUT
}
