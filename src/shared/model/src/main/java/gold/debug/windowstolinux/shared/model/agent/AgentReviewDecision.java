package gold.debug.windowstolinux.shared.model.agent;

/** Independent review result; only ALLOW admits execution. / 独立审批结果，仅 ALLOW 允许执行。 */
public enum AgentReviewDecision {
    /** Evidence supports execution. / 证据支持执行。 */
    ALLOW,
    /** Valid rejection; never try another reviewer. / 有效拒绝，不接替审批模型。 */
    DENY,
    /** Insufficient evidence; execution remains blocked. / 证据不足，保持阻断。 */
    NEEDS_EVIDENCE;
}
