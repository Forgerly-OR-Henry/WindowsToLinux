package gold.debug.windowstolinux.shared.model.deployment;

/** Controls AI participation in one desktop deployment. / 控制一次桌面部署中的 AI 参与程度。 */
public enum DeploymentAutomationMode {
    /** Deterministic deployment with no AI calls. / 无 AI 调用的确定性部署。 */
    STATIC,
    /** Fixed workflow with advisory checkpoints. / 带建议节点的固定流程。 */
    ASSISTED,
    /** Single deployment agent with independent approval. / 单部署代理及独立审批。 */
    AGENT
}
