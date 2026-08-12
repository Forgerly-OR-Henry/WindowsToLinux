package gold.debug.windowstolinux.shared.ai.collaboration;

/** Final collaboration disposition; model advice never creates an execution permission. / 最终协作处置；模型建议绝不创建执行许可。 */
public enum CollaborationDisposition {
    /** Continue only under the pre-existing deterministic decision. / 仅依据既有确定性决策继续。 */
    DETERMINISTIC_ONLY,
    /** Stop because an authoritative fact or validated safety concern requires it. / 因权威事实或已验证安全问题而停止。 */
    SAFE_STOP,
    /** Stop pending an explicit human decision. / 停止并等待明确的人工决定。 */
    USER_DECISION_REQUIRED
}
