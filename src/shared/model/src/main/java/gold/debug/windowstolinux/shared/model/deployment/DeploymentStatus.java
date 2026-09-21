package gold.debug.windowstolinux.shared.model.deployment;

/**
 * Accurate terminal state of a managed-deployment publishing transaction.
 *
 *  <p>受管部署发布事务的准确终态。
 */
public enum DeploymentStatus {
    /**
     * Represents the {@code SUCCEEDED} option.
     *
     *  <p>表示 {@code SUCCEEDED} 选项。
     */
    SUCCEEDED,
    /**
     * Represents the {@code FAILED_BUILD} option.
     *
     *  <p>表示 {@code FAILED_BUILD} 选项。
     */
    FAILED_BUILD,
    /**
     * Represents the {@code FAILED_ROLLED_BACK} option.
     *
     *  <p>表示 {@code FAILED_ROLLED_BACK} 选项。
     */
    FAILED_ROLLED_BACK,
    /**
     * Represents the {@code FAILED_FIRST_DEPLOYMENT} option.
     *
     *  <p>表示 {@code FAILED_FIRST_DEPLOYMENT} 选项。
     */
    FAILED_FIRST_DEPLOYMENT,
    /**
     * Represents the {@code MANUAL_RECOVERY_REQUIRED} option.
     *
     *  <p>表示 {@code MANUAL_RECOVERY_REQUIRED} 选项。
     */
    MANUAL_RECOVERY_REQUIRED,
    /**
     * Represents the {@code PRECONDITION_REJECTED} option.
     *
     *  <p>表示 {@code PRECONDITION_REJECTED} 选项。
     */
    PRECONDITION_REJECTED
}
