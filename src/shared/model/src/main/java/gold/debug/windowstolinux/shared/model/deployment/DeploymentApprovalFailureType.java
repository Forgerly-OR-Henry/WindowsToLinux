package gold.debug.windowstolinux.shared.model.deployment;

import gold.debug.windowstolinux.shared.model.failure.FailureDefinition;
import gold.debug.windowstolinux.shared.model.failure.FailureRecoveryAction;
import gold.debug.windowstolinux.shared.model.failure.FailureSeverityLevel;

/** Stable rejection types for an environment-setup approval. / 环境准备批准的稳定拒绝类型。 */
public enum DeploymentApprovalFailureType implements FailureDefinition {
    CONFIRMATION_REQUIRED("deployment.approval.confirmation-required", "approval",
            "deployment.error.confirmationRequired"),
    SERVER_MISMATCH("deployment.approval.server-mismatch", "approval",
            "deployment.error.approvalServerMismatch");

    private final String code;
    private final String phase;
    private final String messageKey;

    DeploymentApprovalFailureType(String code, String phase, String messageKey) {
        this.code = code;
        this.phase = phase;
        this.messageKey = messageKey;
    }

    @Override public String code() { return code; }
    @Override public String domain() { return "deployment"; }
    @Override public String phase() { return phase; }
    @Override public String messageKey() { return messageKey; }
    @Override public FailureSeverityLevel severity() { return FailureSeverityLevel.ERROR; }
    @Override public FailureRecoveryAction recoveryAction() { return FailureRecoveryAction.REQUEST_USER_CORRECTION; }
}
