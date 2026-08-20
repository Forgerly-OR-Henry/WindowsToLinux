package gold.debug.windowstolinux.shared.deploy.contract.result.deployment;

/** Terminal state of one component in an application transaction. / 应用事务中一个组件的终态。 */
public enum ComponentTransactionState {
    /** No target mutation occurred because a precondition failed. / 因前置条件失败而未修改目标机。 */
    PRECONDITION_REJECTED,
    /** Candidate build failed before switching. / 候选构建在切换前失败。 */
    BUILD_FAILED,
    /** A valid candidate was discarded before switching any release. / 有效候选在任何发布切换前被丢弃。 */
    CANDIDATE_DISCARDED,
    /** The new component release is healthy and verified. / 新组件发布健康且已验证。 */
    SUCCEEDED,
    /** The old component release and runtime state were restored. / 已恢复旧组件发布和运行状态。 */
    RESTORED,
    /** A first-deployment candidate was removed after failure. / 首次部署候选在失败后已移除。 */
    FIRST_DEPLOYMENT_REVERTED,
    /** Recovery or verification failed and needs an operator. / 恢复或验证失败，需要人工处理。 */
    MANUAL_RECOVERY_REQUIRED
}
