package gold.debug.windowstolinux.app.ai.model;

/** Independent capability verification outcomes. / 独立能力验证结果。 */
public enum AiCapabilityStateType {
    /** No current probe evidence. / 无当前验证证据。 */
    UNVERIFIED,
    /** Actual input and expected response verified. / 已验证实际输入及预期响应。 */
    AVAILABLE,
    /** Provider explicitly rejects this capability. / 提供方明确不支持此能力。 */
    UNSUPPORTED,
    /** Verification failed without proving lack of support. / 验证失败，不能据此证明不支持。 */
    FAILED
}
