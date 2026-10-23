package gold.debug.windowstolinux.mod.deployment.service.agent.protocol;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;

import gold.debug.windowstolinux.mod.deployment.service.agent.protocol.*;
import org.junit.jupiter.api.Test;

class DeploymentAgentEvidenceTest {
    private final DeploymentAgentEvidence evidence = new DeploymentAgentEvidence();

    private final String revision = "a".repeat(64);

    @Test
    void configuredObservationCapacityKeepsOriginalReadsBeyondTheOldFourHundredLimit() {
        String first = evidence.observe("", revision, 0, "READ", "api/main.py", false);
        evidence.output(first, "actual source entrypoint");
        for (int index = 1; index < 600; index++) evidence.observe("", revision, 0, "LIST", "api", false);
        evidence.identify(List.of(finding("api", List.of(first))), Map.of("api", revision), revision);
        evidence.requireIdentified("api", "api", revision);
        assertTrue(evidence.read(first, 0, 6000).contains("actual source entrypoint"));
        assertEquals(first, ((Map<?, ?>) ((List<?>) evidence.index(0, 1).get("entries")).getFirst()).get("id"));
        assertThrows(IllegalArgumentException.class, () -> evidence.requireIdentified("api", "web", revision));
        assertThrows(IllegalArgumentException.class, () -> evidence.requireIdentified("api", "api", "b".repeat(64)));
    }

    @Test
    void smallerConfiguredObservationLimitRejectsWithoutEvictionOrIdentityReuse() {
        var small = new DeploymentAgentEvidence(2);
        String first = small.observe("", revision, 0, "READ", "api/main.py", false);
        small.output(first, "exact original source");
        assertEquals("o2", small.observe("", revision, 0, "LIST", "api", false));
        assertThrows(IllegalStateException.class, () -> small.observe("", revision, 0, "READ", "api/other.py", false));
        assertThrows(IllegalArgumentException.class, () -> small.index(3, 1));
        assertEquals(2, ((List<?>) small.index(0, 50).get("entries")).size());
        assertTrue(small.read(first, 0, 6000).contains("exact original source"));
        small.identify(List.of(finding("api", List.of(first))), Map.of("api", revision), revision);
    }

    @Test
    void databaseSourceSelectionRequiresExactManifestAndCurrentComponentConsumptionReads() {
        String manifest = evidence.observe("", revision, 0, "READ", "windowstolinux-db.properties", false);
        String consumer = evidence.observe("cli", revision, 0, "READ", "cli/store.py", false);
        String sql = evidence.observe("", revision, 0, "READ", "schema.sql", false);
        var selection = new DeploymentAgentDatabaseSourceSelection(".", List.of("snapshots"), List.of(manifest, sql), List.of(consumer));
        assertDoesNotThrow(() -> evidence.requireDatabaseSource("cli", "cli", selection, revision));
        assertThrows(IllegalArgumentException.class, () -> evidence.requireReads("cli", "cli", List.of(manifest), revision), "New resource selection does not loosen the plan's source-root boundary");
        assertThrows(IllegalArgumentException.class, () -> evidence.requireDatabaseSource("cli", "cli", selection, "b".repeat(64)));
        assertThrows(IllegalArgumentException.class, () -> evidence.requireDatabaseSource("native", "native", selection, revision));
        String indexed = evidence.observe("", revision, 0, "INDEX", "windowstolinux-db.properties", false);
        String unrelated = evidence.observe("", revision, 0, "READ", "README.md", false);
        String wrongManifest = evidence.observe("", revision, 0, "READ", "other/windowstolinux-db.properties", false);
        for (String invalid : List.of(indexed, unrelated, wrongManifest, sql)) {
            var bad = new DeploymentAgentDatabaseSourceSelection(".", List.of("snapshots"), List.of(invalid), List.of(consumer));
            assertThrows(IllegalArgumentException.class, () -> evidence.requireDatabaseSource("cli", "cli", bad, revision));
        }
        String foreign = evidence.observe("native", revision, 0, "READ", "cli/store.py", false);
        assertThrows(IllegalArgumentException.class, () -> evidence.requireDatabaseSource("cli", "cli",
            new DeploymentAgentDatabaseSourceSelection(".", List.of("snapshots"), List.of(manifest), List.of(foreign)), revision));
    }

    @Test
    void pathIndexCannotSubstituteForActualReadEvidence() {
        String index = evidence.observe("", revision, 0, "INDEX", "api", false);
        evidence.output(index, "api/main.py\n");
        assertThrows(IllegalArgumentException.class,
            () -> evidence.identify(List.of(finding("api", List.of(index))), Map.of("api", revision), revision));
        String read = evidence.observe("", revision, 0, "READ", "api/main.py", false);
        evidence.identify(List.of(finding("api", List.of(read))), Map.of("api", revision), revision);
        evidence.requireIdentified("api", "api", revision);
    }

    @Test
    void independentIdentificationRequiresActualReadsInItsOwnRoot() {
        String tool = evidence.observe("api", revision, 1, "TOOL_analyze", "api/main.py", true);
        assertThrows(IllegalArgumentException.class, () -> evidence.identify(List.of(finding("api", List.of(tool))), Map.of("api", revision), revision));
        String wrong = evidence.observe("web", revision, 1, "READ", "api/main.py", false);
        assertThrows(IllegalArgumentException.class, () -> evidence.identify(List.of(finding("api", List.of(wrong))), Map.of("api", revision), revision));
        String actual = evidence.observe("", revision, 0, "READ", "api/main.py", false);
        evidence.identify(List.of(finding("api", List.of(actual))), Map.of("api", revision), revision);
        evidence.requireIdentified("api", "api", revision);
        assertThrows(IllegalArgumentException.class, () -> evidence.requireIdentified("api", "web", revision));
        assertThrows(IllegalArgumentException.class, () -> evidence.requireIdentified("api", "api", "b".repeat(64)));
    }

    @Test
    void omissionsConflictsAndUnknownResultsCannotBeIgnoredByCriticalActions() {
        String read = evidence.observe("", revision, 0, "READ", "main.py", false);
        String result = evidence.observe("api", revision, 2, "TOOL_analyze", "", true);
        evidence.output(result, "unsupported static detector: custom entrypoint omitted");
        assertThrows(IllegalArgumentException.class, () -> evidence.assess("Command", assessment(List.of(read), DeploymentAgentAssessment.ResolutionType.CONSISTENT), Map.of("api", revision), revision, 2));
        assertThrows(IllegalArgumentException.class, () -> evidence.assess("Command", assessment(List.of(result), DeploymentAgentAssessment.ResolutionType.UNRESOLVED), Map.of("api", revision), revision, 2));
        assertThrows(IllegalArgumentException.class, () -> evidence.assess("Command", assessment(List.of(result), DeploymentAgentAssessment.ResolutionType.RESOLVED), Map.of("api", revision), revision, 3));
        evidence.assess("Command", assessment(List.of(read, result), DeploymentAgentAssessment.ResolutionType.RESOLVED), Map.of("api", revision), revision, 2);
        assertTrue(((List<?>) evidence.state().get("pending")).isEmpty());
        assertTrue(evidence.read(result, 0, 6000).contains("custom entrypoint omitted"));
    }

    @Test
    void patchInvalidatesOldIdentificationWhileFeedbackSurvivesRollingHistory() {
        String read = evidence.observe("api", revision, 1, "READ", "api/main.py", false);
        evidence.identify(List.of(finding("api", List.of(read))), Map.of("api", revision), revision);
        String result = evidence.observe("api", revision, 1, "COMMAND_RESULT", "", true);
        evidence.output(result, "exit=1 missing custom generator");
        for (int i = 0; i < 30; i++) evidence.observe("api", revision, 1, "LIST", "", false);
        assertTrue(evidence.state().get("pendingFacts").toString().contains("missing custom generator"));
        evidence.invalidate("api");
        assertThrows(IllegalArgumentException.class, () -> evidence.requireIdentified("api", "api", revision));
        assertThrows(IllegalArgumentException.class, () -> evidence.identify(List.of(finding("api", List.of(read))), Map.of("api", "b".repeat(64)), revision));
        assertTrue(evidence.read(result, 0, 6000).contains("exit=1"));
    }

    @Test
    void olderMetadataAndBodyRemainQueryableAfterLatestWindowMoves() {
        String first = evidence.observe("api", revision, 1, "READ", "api/main.py", false);
        evidence.output(first, "x".repeat(13000));
        evidence.page(first, "api/main.py", 0, 100, true);
        for (int i = 0; i < 60; i++) evidence.observe("api", revision, 1, "LIST", "api", false);
        assertEquals(50, ((List<?>) evidence.latestIndex().get("entries")).size());
        var oldest = (Map<?, ?>) ((List<?>) evidence.index(0, 1).get("entries")).getFirst();
        assertEquals(first, oldest.get("id"));
        assertEquals(13000, oldest.get("totalCharacters"));
        assertEquals(12000, oldest.get("retainedCharacters"));
        assertEquals(true, oldest.get("truncated"));
        assertTrue(evidence.read(first, 11900, 100).endsWith("x".repeat(100)));
        assertThrows(IllegalArgumentException.class, () -> evidence.identify(List.of(finding("api", List.of(first))), Map.of("api", "b".repeat(64)), revision));
    }

    @Test
    void sourceLineContinuationIsIndependentOfCharacterPagingAndQueriesKeepOriginalReadId() {
        String first = evidence.observe("", revision, 0, "READ", "api/main.py", false);
        evidence.output(first, "FIRST_PAGE_ONLY\n" + "source body ".repeat(300));
        evidence.page(first, "api/main.py", 0, 100, false);
        evidence.readPage(first, 0, 135, 100, true);
        var entry = (Map<?, ?>) ((List<?>) evidence.index(0, 50).get("entries")).getFirst();
        assertEquals(false, entry.get("truncated"));
        assertEquals(true, entry.get("hasMore"));
        assertEquals(135, entry.get("totalLines"));
        assertEquals(100, entry.get("nextLine"));
        assertEquals(0, entry.get("lineOffset"));
        String query = evidence.read(first, 2000, 1000);
        assertTrue(query.startsWith(first + " component="));
        assertTrue(query.contains("lineOffset=0 totalLines=135 nextLine=100 hasMore=true"));
        assertTrue(query.contains("characters of this retained READ body only"));
        assertEquals(1, ((List<?>) evidence.index(0, 50).get("entries")).size(), "Observation character paging cannot create or read missing source lines");
        String last = evidence.observe("", revision, 0, "READ", "api/main.py", false);
        evidence.output(last, "TAIL_ENTRYPOINT_FACT");
        evidence.page(last, "api/main.py", 100, 100, false);
        evidence.readPage(last, 100, 135, 135, false);
        assertNotEquals(first, last);
        assertFalse(evidence.read(first, 0, 6000).contains("TAIL_ENTRYPOINT_FACT"));
        assertTrue(evidence.read(last, 0, 6000).contains("lineOffset=100 totalLines=135 nextLine=135 hasMore=false"));
        assertTrue(evidence.read(last, 0, 6000).contains("TAIL_ENTRYPOINT_FACT"));
        String metadata = evidence.observe("", revision, 0, "INDEX", "api", false);
        assertThrows(IllegalArgumentException.class, () -> evidence.readPage(metadata, 0, 135, 100, true));
    }

    private static DeploymentAgentIdentification finding(String id, List<String> ids) {
        return new DeploymentAgentIdentification(id, id, "Python from actual source", "custom daemon", "none declared", "verify build and health", ids);
    }

    private static DeploymentAgentAssessment assessment(List<String> ids, DeploymentAgentAssessment.ResolutionType resolution) {
        return new DeploymentAgentAssessment(ids, "Static tool is incomplete for custom entrypoint", "Read custom generator; replace recipe with scoped command", resolution, "Build independently and inspect output");
    }
}
