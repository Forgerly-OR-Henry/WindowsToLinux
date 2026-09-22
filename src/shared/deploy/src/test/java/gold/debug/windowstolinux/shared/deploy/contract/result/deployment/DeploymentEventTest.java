package gold.debug.windowstolinux.shared.deploy.contract.result.deployment;

import static org.junit.jupiter.api.Assertions.*;

import gold.debug.windowstolinux.shared.model.deployment.DeploymentTraceEvent;
import org.junit.jupiter.api.Test;

class DeploymentEventTest {
    @Test
    void structuredFailuresKeepTheirDiagnosticAndRecoveryDescriptorInProgress() {
        var failure = gold.debug.windowstolinux.shared.model.failure.FailureDescriptor.create(
                gold.debug.windowstolinux.shared.deploy.error.DeploymentExecutionFailureType.PRECONDITION_REJECTED,
                gold.debug.windowstolinux.shared.model.failure.OperationIdentity.create(),
                "Target free space is insufficient");
        var event = DeploymentEvent.failed(DeploymentTraceEvent.TARGET_SPACE, failure);
        assertEquals(failure.diagnostic(), event.message().arguments().get("detail"));
        assertEquals(failure, event.failure().orElseThrow());
        assertEquals(event.message(),
                event.withOperationIdentity(gold.debug.windowstolinux.shared.model.failure.OperationIdentity.create())
                        .message());
    }

    @Test
    void automaticProgressRetainsTheFailedStepDiagnostic() {
        var event = DeploymentEvent.result(DeploymentTraceEvent.PUBLISH, false, "REJECT=configuration-path");
        assertEquals("deployment.event.failed", event.message().key());
        assertEquals("publish", event.message().arguments().get("step"));
        assertEquals(event.evidence(), event.message().arguments().get("detail"));
        assertFalse(DeploymentEvent.result(DeploymentTraceEvent.PUBLISH, true, "PUBLISHED=1").message().arguments()
                .containsKey("detail"));
    }
}
