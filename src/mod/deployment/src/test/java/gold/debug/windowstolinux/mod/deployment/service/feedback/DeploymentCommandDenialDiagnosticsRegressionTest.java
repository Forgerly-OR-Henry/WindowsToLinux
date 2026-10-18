package gold.debug.windowstolinux.mod.deployment.service.feedback;

import gold.debug.windowstolinux.mod.deployment.ai.NativeAiFixture;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DeploymentCommandDenialDiagnosticsRegressionTest {
    @Test
    void unknownGraphRetainsTheOriginalPublicCauseWithoutClaimingATerminalResult() {
        var fixture = new NativeAiFixture();
        DeploymentDiagnostics.operationFailure(fixture.context, fixture.task,
            new IllegalStateException("Original precondition failed\npassword=credential-fixture"));
        var event = fixture.events.getFirst();
        assertEquals("DEPLOYMENT_OPERATION_FAILURE", event.eventCode());
        assertTrue(event.message().contains("Original precondition failed"));
        assertFalse(event.message().contains("credential-fixture"));
        assertTrue(event.outcome().isEmpty());
    }

    @Test
    void assistedAndAgentShareOnlyTheBoundedPublicRefusalDiagnostic() {
        var fixture = new NativeAiFixture();
        DeploymentDiagnostics.commandReviewDenied(fixture.context, fixture.task,
            "Ownership evidence missing\npassword=credential-fixture\nBearer private-fixture\n" + "x".repeat(5000));
        var event = fixture.events.getFirst();
        assertEquals("DEPLOYMENT_COMMAND_REVIEW_DENIED", event.eventCode());
        assertTrue(event.message().startsWith("Ownership evidence missing"));
        assertTrue(event.message().length() <= 4096);
        assertFalse(event.message().contains("credential-fixture"));
        assertFalse(event.message().contains("private-fixture"));
        assertTrue(event.outcome().isEmpty());
        assertTrue(event.details().isEmpty());
        assertTrue(event.taskId().isPresent());
    }
}
