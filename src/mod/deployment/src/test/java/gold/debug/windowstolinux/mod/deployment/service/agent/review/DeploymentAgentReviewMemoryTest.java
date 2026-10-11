package gold.debug.windowstolinux.mod.deployment.service.agent.review;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class DeploymentAgentReviewMemoryTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String BINDING = "a".repeat(64);

    private static final String HASH = "b".repeat(64);

    @Test
    void returnedRangesMergeOverlapAndAdjacencyWithoutFillingGapsOrConflatingRoutes() {
        var memory = new DeploymentAgentReviewMemory(BINDING);
        returned(memory, "evidence", "/body", 0, 4, 30);
        returned(memory, "evidence", "/body", 8, 12, 30);
        returned(memory, "evidence", "/body", 3, 6, 30);
        returned(memory, "evidence", "/body", 12, 14, 30);
        returned(memory, "request", "script", 0, 4, 30);
        var entries = project(memory).at("/deliveredRanges/entries");
        assertEquals(2, entries.size());
        assertEquals(HASH, entries.get(0).path("sha256").asText());
        assertEquals(30, entries.get(0).path("total").asInt());
        assertEquals("/body", entries.get(0).at("/arguments/pointer").asText());
        assertEquals(JSON.valueToTree(java.util.List.of(new DeploymentAgentReviewMemory.Range(0, 6),
            new DeploymentAgentReviewMemory.Range(8, 14))), entries.get(0).path("ranges"));
        returned(memory, "evidence", "/body", 6, 8, 30);
        assertEquals(JSON.valueToTree(java.util.List.of(new DeploymentAgentReviewMemory.Range(0, 14))),
            project(memory).at("/deliveredRanges/entries/0/ranges"));
    }

    @Test
    void zeroProtectedAndNonPageResultsNeverPretendToCoverBody() {
        var memory = new DeploymentAgentReviewMemory(BINDING);
        returned(memory, "request", "script", 30, 30, 30);
        var args = JSON.valueToTree(Map.of("part", "stdin"));
        memory.delivered("request", args, Map.of("kind", "PROTECTED_BOUND_DATA"));
        memory.delivered("request", args, Map.of("error", "FAILED"));
        memory.delivered("source_read", args, Map.of("text", "source data"));
        assertTrue(project(memory).at("/deliveredRanges/entries").isEmpty());
    }

    @Test
    void wholeBodyIdentityAndUtf16LengthRemainDistinctFromPageBytes() {
        var memory = new DeploymentAgentReviewMemory(BINDING);
        var args = JSON.valueToTree(Map.of("pointer", "/body"));
        String firstBody = "中😀xy";
        String secondBody = "中😀xz";
        String firstHash = gold.debug.windowstolinux.mod.deployment.service.agent.contract.DeploymentAgentInputs.hash(firstBody);
        String secondHash = gold.debug.windowstolinux.mod.deployment.service.agent.contract.DeploymentAgentInputs.hash(secondBody);
        memory.delivered("evidence", args, Map.of("offset", 0, "next", 3, "total", firstBody.length(), "sha256", firstHash, "text", "中😀"));
        memory.delivered("evidence", args, Map.of("offset", 0, "next", 3, "total", secondBody.length(), "sha256", secondHash, "text", "中😀"));
        var entries = project(memory).at("/deliveredRanges/entries");
        assertEquals(2, entries.size(), "Equal delivered fragments cannot conflate distinct whole bodies");
        assertEquals(firstHash, entries.get(0).path("sha256").asText());
        assertEquals(5, entries.get(0).path("total").asInt());
        assertEquals(3, entries.get(0).at("/ranges/0/endExclusive").asInt());
        assertEquals(secondHash, entries.get(1).path("sha256").asText());
    }

    @Test
    void capacityOverflowKeepsAccurateRetainedRangesAndClearlyMarksIncompleteTracking() {
        var memory = new DeploymentAgentReviewMemory(BINDING);
        for (int i = 0; i < 25; i++) returned(memory, "evidence", "/body" + i, 0, 3, 10);
        assertEquals("CAPACITY_EXCEEDED_INCOMPLETE", project(memory).at("/deliveredRanges/trackingStatus").asText());
        assertEquals(24, project(memory).at("/deliveredRanges/entries").size());
        assertEquals(3, project(memory).at("/deliveredRanges/entries/0/ranges/0/endExclusive").asInt());
        returned(memory, "evidence", "/body0", 3, 10, 10);
        assertEquals(10, project(memory).at("/deliveredRanges/entries/0/ranges/0/endExclusive").asInt());
        assertEquals("CAPACITY_EXCEEDED_INCOMPLETE", project(memory).at("/deliveredRanges/trackingStatus").asText());
    }

    @Test
    void noteReplacementRequiresBindingAndRejectsUtf16AndUtf8OverflowAtomically() {
        var memory = new DeploymentAgentReviewMemory(BINDING);
        var saved = memory.saveNotes(BINDING, "original doubts");
        assertEquals("NONE", saved.get("authority"));
        assertThrows(IllegalArgumentException.class, () -> memory.saveNotes("c".repeat(64), "foreign"));
        assertThrows(IllegalArgumentException.class, () -> memory.saveNotes(BINDING, "x".repeat(3001)));
        assertThrows(IllegalArgumentException.class, () -> memory.saveNotes(BINDING, "中".repeat(2001)));
        assertEquals("original doubts", project(memory).at("/modelNotes/text").asText());
        memory.saveNotes(BINDING, "中".repeat(2000));
        assertEquals(2000, project(memory).at("/modelNotes/text").asText().length());
        memory.saveNotes(BINDING, "");
        assertEquals("", project(memory).at("/modelNotes/text").asText());
    }

    @Test
    void sameBindingStillDoesNotShareMemoryAcrossReviewSessions() {
        var first = new DeploymentAgentReviewMemory(BINDING);
        first.saveNotes(BINDING, "reviewed this body");
        returned(first, "request", "script", 0, 10, 10);
        var second = new DeploymentAgentReviewMemory(BINDING);
        assertTrue(project(second).at("/deliveredRanges/entries").isEmpty());
        assertEquals("", project(second).at("/modelNotes/text").asText());
        assertEquals("MODEL_AUTHORED_DATA", project(first).at("/modelNotes/trust").asText());
        assertEquals("NONE", project(first).path("authority").asText());
        assertFalse(project(second).toString().contains("reviewed this body"));
    }

    private static void returned(DeploymentAgentReviewMemory memory, String tool, String route, int start, int end, int total) {
        memory.delivered(tool, JSON.valueToTree(Map.of(tool.equals("request") ? "part" : "pointer", route)),
            Map.of("offset", start, "next", end, "total", total, "sha256", HASH, "text", "x".repeat(end - start)));
    }

    private static JsonNode project(DeploymentAgentReviewMemory memory) {
        return JSON.valueToTree(memory.projection());
    }
}
