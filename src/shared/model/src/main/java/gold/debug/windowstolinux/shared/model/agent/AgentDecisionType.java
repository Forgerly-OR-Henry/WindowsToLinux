package gold.debug.windowstolinux.shared.model.agent;

/** Deployment agent's bounded decision. / 部署 Agent 的受限决策。 */
public enum AgentDecisionType {
    /** Select one registered action. / 选择一个已登记动作。 */
    EXECUTE,
    /** Pause for missing user input. / 因缺少用户输入而暂停。 */
    NEED_INPUT,
    /** Yield to the next deployment model. / 交接至下一个部署模型。 */
    UNABLE,
    /** Claim completion, subject to actual results. / 声明完成，仍须实际结果验证。 */
    COMPLETE;
}
