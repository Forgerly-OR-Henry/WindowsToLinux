package gold.debug.windowstolinux.shared.ai.collaboration;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AiDecisionCoordinatorTest {
    private final AiDecisionCoordinator coordinator = new AiDecisionCoordinator();

    @Test
    void deterministicStopAlwaysWins() {
        var decision = coordinator.reconcile(DeterministicDecision.STOP,
                List.of(result(AiAdviceDecision.CLEAR, AiCollaborationRole.PROJECT_ANALYSIS)));

        assertEquals(CollaborationDisposition.SAFE_STOP, decision.disposition());
        assertEquals("deterministic-stop", decision.reason());
    }

    @Test
    void validatedConflictRequiresUserAndUnavailableProviderCannotGrantPermission() {
        var conflict = coordinator.reconcile(DeterministicDecision.ALLOW, List.of(
                result(AiAdviceDecision.CLEAR, AiCollaborationRole.PROJECT_ANALYSIS),
                result(AiAdviceDecision.SAFE_STOP, AiCollaborationRole.DEPLOYMENT_RISK_REVIEW)));
        assertEquals(CollaborationDisposition.USER_DECISION_REQUIRED, conflict.disposition());

        AiInvocationEvidence unavailable = new AiInvocationEvidence(AiCollaborationRole.ERROR_EXPLANATION,
                "errors", "model-c", "stepCode=health-check;safeDiagnostic=failed", "b".repeat(64),
                AiInvocationStatus.UNAVAILABLE, Optional.empty(), "selected-provider-unavailable", Instant.EPOCH);
        var preserved = coordinator.reconcile(DeterministicDecision.ALLOW,
                List.of(new AiRoleInvocationResult(unavailable)));
        assertEquals(CollaborationDisposition.DETERMINISTIC_ONLY, preserved.disposition());
        assertEquals("selected-model-unavailable-or-invalid", preserved.reason());
    }

    private static AiRoleInvocationResult result(AiAdviceDecision decision, AiCollaborationRole role) {
        RoleAdvice advice = new RoleAdvice(decision, "bounded summary", List.of());
        return new AiRoleInvocationResult(new AiInvocationEvidence(role, role.name().toLowerCase().replace('_', '-'),
                "model", "redacted=facts", "a".repeat(64), AiInvocationStatus.VALIDATED,
                Optional.of(advice), "fixed-schema-validated", Instant.EPOCH));
    }
}
