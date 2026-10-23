package gold.debug.windowstolinux.mod.deployment.service.core;

import java.util.Optional;

/** Nonsecret observation of the original worker's approval policy, never an authorization. */
public record DeploymentApprovalPolicyState(String mode, long revision, Optional<String> pendingMode,
                                            Optional<String> problem, boolean editable) {
    public DeploymentApprovalPolicyState {
        gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentAuthorizationPolicy.requireAllowed(mode, true);
        if (revision < 0 || pendingMode == null || problem == null)
            throw new IllegalArgumentException("Invalid approval policy observation");
        pendingMode.ifPresent(value -> gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentAuthorizationPolicy.requireAllowed(value, true));
    }
}
