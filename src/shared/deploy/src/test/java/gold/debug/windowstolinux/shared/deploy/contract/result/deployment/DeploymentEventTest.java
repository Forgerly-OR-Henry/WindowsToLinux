package gold.debug.windowstolinux.shared.deploy.contract.result.deployment;

import gold.debug.windowstolinux.shared.model.deployment.DeploymentTraceEvent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DeploymentEventTest {
    @Test
    void automaticProgressRetainsTheFailedStepDiagnostic() {
        var event = DeploymentEvent.result(DeploymentTraceEvent.PUBLISH, false, "REJECT=configuration-path");
        assertEquals("deployment.event.failed", event.message().key());
        assertEquals("publish", event.message().arguments().get("step"));
        assertEquals(event.evidence(), event.message().arguments().get("detail"));
        assertFalse(DeploymentEvent.result(DeploymentTraceEvent.PUBLISH, true, "PUBLISHED=1")
                .message().arguments().containsKey("detail"));
    }
}
