package gold.debug.windowstolinux.shared.deploy.error;

import gold.debug.windowstolinux.shared.model.failure.FailureDefinition;
import gold.debug.windowstolinux.shared.model.failure.FailureRecoveryAction;
import gold.debug.windowstolinux.shared.model.failure.FailureSeverityLevel;

/** Failures owned by reviewed deployment orchestration. / 经审阅部署编排持有的失败类型。 */
public enum DeploymentExecutionFailureType implements FailureDefinition {
    PRECONDITION_REJECTED("deployment.preflight.rejected", "preflight", "deployment.error.preconditionRejected", FailureSeverityLevel.ERROR, FailureRecoveryAction.REQUEST_USER_CORRECTION),
    BUILD_FAILED("deployment.build.failed", "build", "deployment.error.buildFailed", FailureSeverityLevel.ERROR, FailureRecoveryAction.CLEANUP),
    SWITCH_UNVERIFIED("deployment.switch.unverified", "switch", "deployment.error.switchUnverified", FailureSeverityLevel.ERROR, FailureRecoveryAction.ROLLBACK),
    PUBLISH_FAILED("deployment.publish.failed", "publish", "deployment.error.publishFailed", FailureSeverityLevel.ERROR, FailureRecoveryAction.ROLLBACK),
    HEALTH_FAILED("deployment.health.failed", "health", "deployment.error.healthFailed", FailureSeverityLevel.ERROR, FailureRecoveryAction.ROLLBACK),
    OBSERVATION_UNVERIFIED("deployment.observation.unverified", "observation", "deployment.error.observationUnverified", FailureSeverityLevel.ERROR, FailureRecoveryAction.REQUIRE_MANUAL_RECOVERY),
    ARITHMETIC_OVERFLOW("deployment.preflight.arithmetic-overflow", "preflight", "deployment.error.arithmeticOverflow", FailureSeverityLevel.ERROR, FailureRecoveryAction.REQUEST_USER_CORRECTION),
    CLEANUP_UNVERIFIED("deployment.cleanup.unverified", "cleanup", "deployment.error.cleanupUnverified", FailureSeverityLevel.ERROR, FailureRecoveryAction.REQUIRE_MANUAL_RECOVERY),
    ROLLBACK_UNVERIFIED("deployment.rollback.unverified", "rollback", "deployment.error.rollbackUnverified", FailureSeverityLevel.ERROR, FailureRecoveryAction.REQUIRE_MANUAL_RECOVERY),
    LOCAL_OBSERVATION_PERSISTENCE_FAILED("deployment.persistence.observation-save-failed", "persistence", "deployment.error.observationSaveFailed", FailureSeverityLevel.WARNING, FailureRecoveryAction.RETRY);

    private final String code;
    private final String phase;
    private final String messageKey;
    private final FailureSeverityLevel severity;
    private final FailureRecoveryAction recoveryAction;

    DeploymentExecutionFailureType(String code, String phase, String messageKey,
                                   FailureSeverityLevel severity, FailureRecoveryAction recoveryAction) {
        this.code = code;
        this.phase = phase;
        this.messageKey = messageKey;
        this.severity = severity;
        this.recoveryAction = recoveryAction;
    }

    @Override public String code() { return code; }
    @Override public String domain() { return "deployment"; }
    @Override public String phase() { return phase; }
    @Override public String messageKey() { return messageKey; }
    @Override public FailureSeverityLevel severity() { return severity; }
    @Override public FailureRecoveryAction recoveryAction() { return recoveryAction; }
}
