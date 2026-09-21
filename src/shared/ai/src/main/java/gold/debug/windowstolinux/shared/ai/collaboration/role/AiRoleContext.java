package gold.debug.windowstolinux.shared.ai.collaboration.role;

/**
 * Sealed minimal redacted facts accepted by one collaboration role. / 一个协作角色可接受的封闭最小脱敏事实。
 */
public sealed interface AiRoleContext permits ProjectAnalysisRoleContext, DeploymentRiskRoleContext,
        ErrorExplanationRoleContext, DeploymentInputRoleContext {
    /**
     * Returns the only role allowed to receive this context. / 返回唯一允许接收此上下文的角色。
     *
     * @return the only role allowed to receive this context / 唯一允许接收此上下文的角色
     */
    AiCollaborationRoleKind role();

    /**
     * Returns a bounded redacted input summary retained for audit. / 返回为审计保留的有界脱敏输入摘要。
     *
     * @return a bounded redacted input summary retained for audit / 为审计保留的有界脱敏输入摘要
     */
    String redactedSummary();
}
