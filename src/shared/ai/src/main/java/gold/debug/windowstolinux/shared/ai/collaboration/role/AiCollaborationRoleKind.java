package gold.debug.windowstolinux.shared.ai.collaboration.role;

/**
 * Fixed AI collaboration roles with separate provider selection and context boundaries. / 具有独立提供者选择和上下文边界的固定 AI 协作角色。
 */
public enum AiCollaborationRoleKind {
    /**
     * Optional project-structure explanation. / 可选的项目结构解释。
     */
    PROJECT_ANALYSIS,
    /**
     * Optional review of an already deterministic deployment plan. / 对既有确定性部署计划的可选复核。
     */
    DEPLOYMENT_RISK_REVIEW,
    /**
     * Optional explanation of a redacted controlled failure. / 对已脱敏受控失败的可选解释。
     */
    ERROR_EXPLANATION
}
