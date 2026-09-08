package gold.debug.windowstolinux.app.service.contract.definition;

import gold.debug.windowstolinux.app.service.deployment.single.DeploymentHandoff;
import gold.debug.windowstolinux.shared.model.deployment.DeploymentStatus;
import java.util.Map;

/** Whole-operation handoff; unsuccessful operations never expose success links. */
public record AutomaticDeploymentOutcome(String applicationId, DeploymentStatus status,
                                         Map<String, DeploymentHandoff> handoffs) {
    /** Validates that only successful complete transactions provide handoffs. */
    public AutomaticDeploymentOutcome {
        handoffs = Map.copyOf(handoffs);
        if (status != DeploymentStatus.SUCCEEDED && !handoffs.isEmpty())
            throw new IllegalArgumentException("unsuccessful operation cannot expose handoffs");
    }
}
