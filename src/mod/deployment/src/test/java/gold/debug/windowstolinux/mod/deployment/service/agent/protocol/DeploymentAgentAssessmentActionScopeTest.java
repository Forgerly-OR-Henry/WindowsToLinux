package gold.debug.windowstolinux.mod.deployment.service.agent.protocol;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class DeploymentAgentAssessmentActionScopeTest {
    @Test
    void unknownCoverageDoesNotChooseResolutionAndUnresolvedSubmissionKeepsPendingEvidence() {
        var evidence = new DeploymentAgentEvidence();
        String revision = "a".repeat(64);
        String server = evidence.observe("", revision, 0, "SERVER", "", true);
        evidence.output(server, "coverage={baseline=OBSERVED, tools=UNKNOWN}; assessmentCoverage={platform=NOT_PERFORMED_BY_THIS_OBSERVATION}");
        String read = evidence.observe("", revision, 0, "READ", "cli/Cargo.toml", false);
        evidence.output(read, "actual frozen manifest");
        var unresolved = new DeploymentAgentAssessment(List.of(server, read), "Source-backed plan", "Blocking source conflict still requires investigation",
            DeploymentAgentAssessment.ResolutionType.UNRESOLVED, "Investigate before planning");
        var failure = assertThrows(IllegalArgumentException.class,
            () -> evidence.assess("Plan", unresolved, Map.of(), revision, 0));
        assertTrue(failure.getMessage().contains("action=Plan field=assessment.resolution submitted=UNRESOLVED"));
        assertEquals(List.of(server), evidence.state().get("pending"));
        assertTrue(((Map<?, ?>) evidence.state().get("decisions")).isEmpty());
        var chosen = new DeploymentAgentAssessment(List.of(server, read), "Source supports the proposed component layout",
            "Unobserved tools will be prepared before building; no conflict prevents this declaration",
            DeploymentAgentAssessment.ResolutionType.CONSISTENT, "Submit this source-backed plan");
        assertDoesNotThrow(() -> evidence.assess("Plan", chosen, Map.of(), revision, 0));
        assertEquals(List.of(), evidence.state().get("pending"));
        assertTrue(evidence.read(server, 0, 6000).contains("tools=UNKNOWN"));
        assertEquals(2, ((List<?>) evidence.index(0, 50).get("entries")).size(), "Assessment cannot mint observations or execute preparation");
    }

    @Test
    void pendingSummaryReportsItsActualTruncationWithoutReplacingTheOriginalBody() {
        var evidence = new DeploymentAgentEvidence();
        String revision = "b".repeat(64);
        String id = evidence.observe("cli", revision, 3, "COMMAND_RESULT", "", true);
        String body = "x".repeat(250) + " actual trailing failure";
        evidence.output(id, body);
        var pending = (Map<?, ?>) ((Map<?, ?>) evidence.state().get("pendingFacts")).get(id);
        assertEquals("COMMAND_RESULT", pending.get("kind"));
        assertEquals(true, pending.get("summaryTruncated"));
        assertEquals(body.length(), pending.get("retainedCharacters"));
        assertFalse(pending.get("summary").toString().contains("trailing failure"));
        assertTrue(evidence.read(id, 200, 6000).endsWith(body.substring(200)));
        assertEquals(List.of(id), evidence.state().get("pending"));
        assertEquals(1, ((List<?>) evidence.index(0, 50).get("entries")).size());
    }
}
