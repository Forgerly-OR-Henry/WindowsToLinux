package gold.debug.windowstolinux.shared.model.failure;

/**
 * Terminal state of the selected recovery action. / 所选恢复动作的终态。
 */
public enum FailureRecoveryDisposition {
    /**
     * The failure requires no recovery action. / 该失败不需要恢复动作。
     */
    NOT_REQUIRED,
    /**
     * A recovery action was selected but not attempted. / 已选择恢复动作但尚未尝试。
     */
    NOT_ATTEMPTED,
    /**
     * Recovery completed and its result was verified. / 恢复已完成且结果已经验证。
     */
    SUCCEEDED,
    /**
     * Recovery ran and failed. / 恢复已执行但失败。
     */
    FAILED,
    /**
     * Recovery may have run, but its final state is unknown. / 恢复可能已执行，但终态未知。
     */
    UNVERIFIED
}
