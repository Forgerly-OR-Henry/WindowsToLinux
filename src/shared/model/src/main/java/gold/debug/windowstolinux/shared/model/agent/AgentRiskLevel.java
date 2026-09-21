package gold.debug.windowstolinux.shared.model.agent;

/** Local and reviewer risk ordered by severity. / 按严重程度排序的本地及审批风险。 */
public enum AgentRiskLevel {
    /** Bounded read or reversible operation. / 有界读取或可恢复操作。 */
    NORMAL,
    /** Permitted operation with material server effects. / 具有显著服务器影响的允许操作。 */
    HIGH,
    /** Outside authorization; cannot be approved. / 超出授权，不能批准。 */
    FORBIDDEN;
}
