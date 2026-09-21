package gold.debug.windowstolinux.shared.model.deployment;
/** Human confirmation policy after mandatory local and AI approval. / 强制本地及 AI 审批后的人工确认策略。 */
public enum AgentApprovalMode {
    /** Confirms each executable instruction. / 确认每条可执行指令。 */
    MANUAL_REVIEW,
    /** Confirms approved high-risk instructions. / 确认已批准高危指令。 */
    AUTOMATIC,
    /** Executes approved instructions without risk confirmation. / 已批准指令不再进行风险确认。 */
    FULL_CONTROL
}
