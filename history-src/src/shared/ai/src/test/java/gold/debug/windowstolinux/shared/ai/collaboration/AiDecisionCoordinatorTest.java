package gold.debug.windowstolinux.shared.ai.collaboration;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import gold.debug.windowstolinux.shared.ai.collaboration.advice.AiAdviceDecision;
import gold.debug.windowstolinux.shared.ai.collaboration.advice.RoleAdviceAssessment;
import gold.debug.windowstolinux.shared.ai.collaboration.invocation.AiInvocationEvidence;
import gold.debug.windowstolinux.shared.ai.collaboration.invocation.AiInvocationStatus;
import gold.debug.windowstolinux.shared.ai.collaboration.invocation.AiRoleInvocationResult;
import gold.debug.windowstolinux.shared.ai.collaboration.role.AiCollaborationRoleKind;
import org.junit.jupiter.api.Test;

class AiDecisionCoordinatorTest {
    private final AiDecisionCoordinator coordinator = new AiDecisionCoordinator();

    @Test
    void deterministicStopAlwaysWins() {
        var decision = coordinator.reconcile(DeterministicDecision.STOP,
                List.of(result(AiAdviceDecision.CLEAR, AiCollaborationRoleKind.PROJECT_ANALYSIS)));

        assertEquals(CollaborationDisposition.SAFE_STOP, decision.disposition());
        assertEquals("deterministic-stop", decision.reason());
    }

    @Test
    void validatedConflictRequiresUserAndUnavailableProviderCannotGrantPermission() {
        var conflict = coordinator.reconcile(DeterministicDecision.ALLOW,
                List.of(result(AiAdviceDecision.CLEAR, AiCollaborationRoleKind.PROJECT_ANALYSIS),
                        result(AiAdviceDecision.SAFE_STOP, AiCollaborationRoleKind.DEPLOYMENT_RISK_REVIEW)));
        assertEquals(CollaborationDisposition.USER_DECISION_REQUIRED, conflict.disposition());

        AiInvocationEvidence unavailable = new AiInvocationEvidence(AiCollaborationRoleKind.ERROR_EXPLANATION, "errors",
                "model-c", "stepCode=health-check;safeDiagnostic=failed", "b".repeat(64),
                AiInvocationStatus.UNAVAILABLE, Optional.empty(), "selected-provider-unavailable", Instant.EPOCH);
        var preserved = coordinator.reconcile(DeterministicDecision.ALLOW,
                List.of(new AiRoleInvocationResult(unavailable)));
        assertEquals(CollaborationDisposition.DETERMINISTIC_ONLY, preserved.disposition());
        assertEquals("selected-model-unavailable-or-invalid", preserved.reason());
    }

    private static AiRoleInvocationResult result(AiAdviceDecision decision, AiCollaborationRoleKind role) {
        RoleAdviceAssessment advice = new RoleAdviceAssessment(decision, "bounded summary", List.of());
        return new AiRoleInvocationResult(new AiInvocationEvidence(role, role.name().toLowerCase().replace('_', '-'),
                "model", "redacted=facts", "a".repeat(64), AiInvocationStatus.VALIDATED, Optional.of(advice),
                "fixed-schema-validated", Instant.EPOCH));
    }
}
