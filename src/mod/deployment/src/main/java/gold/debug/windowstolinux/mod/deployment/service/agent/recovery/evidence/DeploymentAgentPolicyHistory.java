package gold.debug.windowstolinux.mod.deployment.service.agent.recovery.evidence;

import java.util.List;

import gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentApprovalPolicies.Confirmed;

/** Reads appended confirmations without rewriting the original Agent identity or prior command evidence. */
public final class DeploymentAgentPolicyHistory {
    public static Confirmed confirmed(List<DeploymentAgentActionLog.Event> events, String initial) {
        Confirmed latest = null;
        for (var event : events) {
            if (!event.type().equals("APPROVAL_POLICY_CONFIRMED")) continue;
            if (!event.action().matches("0|[1-9][0-9]{0,18}"))
                throw new IllegalArgumentException("Invalid original policy revision");
            var next = new Confirmed(event.detail(), Long.parseLong(event.action()));
            if (latest != null && next.revision() != latest.revision() + 1)
                throw new IllegalArgumentException("Original policy confirmation chain changed");
            latest = next;
        }
        return latest == null ? new Confirmed(initial, 0) : latest;
    }

    private DeploymentAgentPolicyHistory() {
    }
}
