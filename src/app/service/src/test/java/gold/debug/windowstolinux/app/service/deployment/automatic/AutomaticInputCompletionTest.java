package gold.debug.windowstolinux.app.service.deployment.automatic;

import gold.debug.windowstolinux.shared.deploy.contract.AutomaticDeploymentInteraction;

import gold.debug.windowstolinux.app.service.contract.definition.*;

import gold.debug.windowstolinux.app.service.contract.AiApplicationFacade;
import gold.debug.windowstolinux.shared.ai.collaboration.role.*;
import gold.debug.windowstolinux.shared.ai.collaboration.advice.*;
import gold.debug.windowstolinux.shared.ai.collaboration.invocation.*;
import gold.debug.windowstolinux.shared.model.deployment.DeploymentInputField;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AutomaticInputCompletionTest {
    private List<DeploymentInputField> asked = List.of();
    private final List<DeploymentInputField> fields = List.of(
            new DeploymentInputField("api/type","type","help","private-input",List.of("NODE_SERVICE")),
            new DeploymentInputField("api/port","port","help","",List.of()));
    private AutomaticDeploymentInteraction interaction() {
        return new AutomaticDeploymentInteraction() {
            public Optional<Map<String,String>> requestInputs(List<DeploymentInputField> remaining) {
                asked = remaining; Map<String,String> result = new HashMap<>();
                remaining.forEach(field -> result.put(field.id(),field.choices().isEmpty() ? "8080" : field.choices().getFirst()));
                return Optional.of(result);
            }
            public boolean confirm(String key, Map<String,?> details) { throw new AssertionError("AI cannot approve actions"); }
            public char[] requestSecret(String key) { throw new AssertionError("AI cannot request secrets"); }
        };
    }
    private AiApplicationFacade ai(List<String> findings, boolean timeout) {
        return (AiApplicationFacade)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{AiApplicationFacade.class},(proxy,method,args) -> {
            var context = (DeploymentInputRoleContext)args[0];
            assertFalse(context.redactedSummary().contains("private-input"));
            if (timeout) throw new java.sql.SQLException("provider timeout");
            if (findings == null) return Optional.empty();
            return Optional.of(new AiRoleInvocationResult(new AiInvocationEvidence(AiCollaborationRoleKind.PROJECT_ANALYSIS,
                    "fixture","fixture","redacted","a".repeat(64),AiInvocationStatus.VALIDATED,
                    Optional.of(new RoleAdviceAssessment(AiAdviceDecision.CLEAR,"suggestion",findings)),"validated",Instant.now())));
        });
    }
    @Test void acceptsOnlyUniquelyEvidencedSuggestionAndGroupsRemainingFields() {
        var result = new AutomaticInputCompletion(ai(List.of("api/type=NODE_SERVICE","api/port=9999"),false)).resolve(fields,new char[0],interaction());
        assertEquals("8080",result.get("api/port")); assertEquals(List.of(fields.getLast()),asked);
    }
    @Test void wrongSuggestionCannotOverrideDeterministicChoices() {
        new AutomaticInputCompletion(ai(List.of("api/type=JAVA_SOURCE"),false)).resolve(fields,new char[0],interaction());
        assertEquals(fields,asked);
    }
    @Test void unavailableAndTimedOutProvidersBothFallBackToSameGroupedForm() {
        for (boolean timeout : List.of(false,true)) {
            new AutomaticInputCompletion(ai(null,timeout)).resolve(fields,new char[0],interaction()); assertEquals(fields,asked);
        }
    }
    @Test void conversationalSecretsAreRedactedBeforeProviderContext() {
        var context = new DeploymentInputRoleContext(fields,"password=hidden token=private Bearer abc",List.of("api_key=secret"));
        assertFalse(context.redactedSummary().contains("hidden")); assertFalse(context.redactedSummary().contains("Bearer abc"));
        assertFalse(context.redactedSummary().contains("=secret"));
    }
}
