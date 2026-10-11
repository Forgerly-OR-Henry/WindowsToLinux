package gold.debug.windowstolinux.mod.deployment.service.agent.review;

import static org.junit.jupiter.api.Assertions.*;

import gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentManagedHelperBundle;
import gold.debug.windowstolinux.mod.deployment.service.agent.contract.DeploymentAgentInputs;
import org.junit.jupiter.api.Test;

class DeploymentAgentHealthReviewContractTest {
    @Test
    void independentlyPinnedContractRejectsProtocolOrHelperDrift() {
        assertEquals(DeploymentManagedHelperBundle.EXPECTED_SHA256, DeploymentAgentHealthReviewContract.HELPER_SHA256);
        assertEquals(DeploymentAgentHealthReviewContract.HELPER_SHA256, DeploymentAgentInputs.hash(DeploymentManagedHelperBundle.renderScript()));
        assertDoesNotThrow(() -> DeploymentAgentHealthReviewContract.requireIdentity(DeploymentManagedHelperBundle.PROTOCOL_VERSION,
            DeploymentManagedHelperBundle.EXPECTED_SHA256));
        assertThrows(IllegalArgumentException.class, () -> DeploymentAgentHealthReviewContract.requireIdentity(
            DeploymentManagedHelperBundle.PROTOCOL_VERSION + 1, DeploymentManagedHelperBundle.EXPECTED_SHA256));
        assertThrows(IllegalArgumentException.class, () -> DeploymentAgentHealthReviewContract.requireIdentity(
            DeploymentManagedHelperBundle.PROTOCOL_VERSION, "f".repeat(64)));
        assertThrows(IllegalArgumentException.class, () -> DeploymentAgentHealthReviewContract.requireIdentity(
            DeploymentManagedHelperBundle.PROTOCOL_VERSION, null));
        assertEquals(DeploymentAgentHealthReviewContract.text(), DeploymentAgentHealthReviewContract.text());
    }

    @Test
    void fixedContractStatesExactArgumentsEffectsReachabilityAndApprovalLimits() {
        String text = DeploymentAgentHealthReviewContract.text();
        for (String required : java.util.List.of(DeploymentAgentHealthReviewContract.REVISION,
            DeploymentAgentHealthReviewContract.HELPER_SHA256,
            "application-health APPLICATION_ID OWNERSHIP_MANIFEST_SHA256 CANONICAL_HEALTH_PAYLOAD",
            "exactly three positional arguments", "not an application/deployment id or resource target path",
            "/opt/windowstolinux/apps/APPLICATION_ID", "windowstolinux-APPLICATION_ID.service",
            "/var/lib/windowstolinux/application-locks/APPLICATION_ID", "open/create/truncate",
            "nonblocking exclusive admission and run locks", "skips only this application's maintenance-marker existence rejection",
            "MainPID=0", "State.Running=false", "Unknown/unverified or active jobs reject",
            "every non-COMMAND kind rejects before application_health_loaded", "stale custom flag cannot make HTTP",
            "complete original dynamic entry, argv, expected output, program", "particular HTTP response socket",
            "No grant is asserted obtained", "after APP, after host authorization and immediately before actual send",
            "valid DENY is final"))
            assertTrue(text.contains(required), required);
        assertFalse(text.contains("application_health() {"));
        assertFalse(text.contains("HEALTHY=1"));
    }
}
