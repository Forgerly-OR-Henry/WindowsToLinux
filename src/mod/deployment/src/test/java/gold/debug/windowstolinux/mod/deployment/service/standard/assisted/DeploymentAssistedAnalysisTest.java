package gold.debug.windowstolinux.mod.deployment.service.standard.assisted;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import gold.debug.windowstolinux.appapi.ai.AiService;
import gold.debug.windowstolinux.appapi.ai.model.AiRequest;
import gold.debug.windowstolinux.appapi.ai.model.AiResponse;
import gold.debug.windowstolinux.appapi.ai.model.AiUsage;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.appapi.task.run.CancelToken;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.mod.deployment.file.DeploymentSourceBrowser;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.input.DeploymentReviewField;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Checks bounded source feedback without model or server access. / 不访问模型或服务器，检查有界源码反馈。 */
class DeploymentAssistedAnalysisTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String REVISION = "a".repeat(64);

    @TempDir
    Path root;

    private final AtomicInteger calls = new AtomicInteger();

    private JsonNode currentSchema;

    @Test
    void missingReadCanBeCorrectedWithoutAddingFalseEvidence() throws Exception {
        var analysis = analysis((turn, facts) -> {
            if (turn == 0) return read().replace("README.md", "absent.txt");
            if (turn == 1) {
                assertEquals("FILE_NOT_FOUND", facts.path("feedback").path("code").asText());
                assertTrue(facts.path("observations").isEmpty());
                assertEquals(11, facts.path("remaining").asInt());
                return read();
            }
            assertEquals(1, facts.path("observations").size());
            return advice("24");
        });
        assertEquals(Map.of("app/version", "24"), analysis.analyze(fields(), Map.of(), task()).values());
        assertEquals(3, calls.get());
    }

    @Test
    void aMissingReadCannotSupportAdvice() throws Exception {
        var analysis = analysis((turn, facts) -> {
            assertTrue(facts.path("evidenceReferences").isEmpty());
            if (turn > 1) assertEquals("ADVICE_EVIDENCE_REJECTED", facts.at("/feedback/code").asText());
            return turn == 0 ? read().replace("README.md", "absent.txt") : advice("24");
        });
        assertThrows(IOException.class, () -> analysis.analyze(fields(), Map.of(), task()));
        assertEquals(12, calls.get());
    }

    @Test
    void listCitationIsRejectedThenActualReadCanSupportCorrectedAdvice() throws Exception {
        var analysis = analysis((turn, facts) -> {
            if (turn == 0) {
                assertEquals(0, currentSchema.at("/properties/arguments/oneOf/2/properties/suggestions/maxItems").asInt());
                return read().replace("\"READ\"", "\"LIST\"").replace("README.md", ".");
            }
            if (turn == 1) {
                assertTrue(facts.path("evidenceReferences").isEmpty());
                assertEquals("LIST", facts.at("/observations/0/action").asText());
                return advice("24");
            }
            if (turn == 2) {
                assertEquals("ADVICE_EVIDENCE_REJECTED", facts.at("/feedback/code").asText());
                assertTrue(facts.path("evidenceReferences").isEmpty());
                assertEquals(10, facts.path("remaining").asInt());
                return read();
            }
            assertEquals("source/1", facts.at("/evidenceReferences/0").asText());
            assertEquals("source/1", currentSchema.at("/properties/arguments/oneOf/2/properties/suggestions/items/properties/evidence/items/enum/0").asText());
            return advice("24").replace("source/0", "source/1");
        });
        var result = analysis.analyze(fields(), Map.of(), task());
        assertEquals(Map.of("app/version", "24"), result.values());
        assertEquals(java.util.Set.of("source/1"), result.locations().keySet());
        assertEquals(4, calls.get());
    }

    @Test
    void duplicateQueryRetainsExactObservationAndCanFinishWithExistingEvidence() throws Exception {
        var analysis = analysis((turn, facts) -> {
            if (turn == 0) return read();
            assertEquals("README.md", facts.path("observations").get(0).path("path").asText());
            assertEquals(0, facts.path("observations").get(0).path("offset").asInt());
            assertEquals(10, facts.path("observations").get(0).path("limit").asInt());
            if (turn == 1) return read();
            assertEquals(1, facts.path("observations").size());
            assertEquals("DUPLICATE_QUERY", facts.path("feedback").path("code").asText());
            return advice("24");
        });
        var result = analysis.analyze(fields(), Map.of(), task());
        assertEquals(Map.of("app/version", "24"), result.values());
        assertEquals(1, result.locations().size());
        assertEquals(3, calls.get());
    }

    @Test
    void repeatedQueriesCannotExpandTheDecisionBudget() throws Exception {
        var analysis = analysis((turn, facts) -> read());
        assertThrows(IOException.class, () -> analysis.analyze(fields(), Map.of(), task()));
        assertEquals(12, calls.get());
    }

    @Test
    void mismatchedRevisionIsDiscardedBeforeSourceAccessAndCanBeCorrected() throws Exception {
        var analysis = analysis((turn, facts) -> {
            if (turn == 0)
                return read().replace(REVISION, "b".repeat(64)).replace("README.md", "../outside-secret.txt");
            if (turn == 1) {
                assertEquals("SOURCE_REVISION_MISMATCH", facts.path("feedback").path("code").asText());
                assertTrue(facts.path("observations").isEmpty());
                return read();
            }
            assertTrue(facts.path("feedback").isEmpty());
            return advice("24");
        });
        assertEquals(Map.of("app/version", "24"), analysis.analyze(fields(), Map.of(), task()).values());
        assertEquals(3, calls.get());
    }

    @Test
    void repeatedRevisionMismatchesCannotExpandTheDecisionBudget() throws Exception {
        var analysis = analysis((turn, facts) -> advice("24").replace(REVISION, "b".repeat(64)));
        assertThrows(IOException.class, () -> analysis.analyze(fields(), Map.of(), task()));
        assertEquals(12, calls.get());
    }

    @Test
    void oversizedPageIsDiscardedBeforeSourceAccessAndCanBeCorrected() throws Exception {
        var analysis = analysis((turn, facts) -> {
            if (turn == 0)
                return read().replace("\"limit\":10", "\"limit\":200").replace("README.md", "../outside-secret.txt");
            if (turn == 1) {
                assertEquals("INVALID_PAGE", facts.path("feedback").path("code").asText());
                assertTrue(facts.path("observations").isEmpty());
                assertEquals(11, facts.path("remaining").asInt());
                return read();
            }
            assertTrue(facts.path("feedback").isEmpty());
            assertEquals(1, facts.path("observations").size());
            assertEquals(10, facts.path("observations").get(0).path("limit").asInt());
            return advice("24");
        });
        assertEquals(Map.of("app/version", "24"), analysis.analyze(fields(), Map.of(), task()).values());
        assertEquals(3, calls.get());
    }

    @Test
    void repeatedInvalidPagesCannotExpandTheDecisionBudgetOrCreateEvidence() throws Exception {
        var invalid = List.of(
            read().replace("\"limit\":10", "\"limit\":200"),
            read().replace("\"limit\":10", "\"limit\":0"),
            read().replace("\"limit\":10", "\"limit\":1.5"),
            read().replace("\"limit\":10", "\"limit\":\"10\""),
            read().replace("\"offset\":0", "\"offset\":-1"),
            read().replace("\"offset\":0", "\"offset\":1000001"),
            read().replace("\"offset\":0", "\"offset\":2147483648"));
        var analysis = analysis((turn, facts) -> {
            assertTrue(facts.path("observations").isEmpty());
            if (turn > 0)
                assertEquals("INVALID_PAGE", facts.path("feedback").path("code").asText());
            return invalid.get(turn % invalid.size());
        });
        assertThrows(IOException.class, () -> analysis.analyze(fields(), Map.of(), task()));
        assertEquals(12, calls.get());
    }

    @Test
    void invalidPathWithValidPaginationIsStillRejected() throws Exception {
        var analysis = analysis((turn, facts) -> read().replace("README.md", "../outside-secret.txt"));
        assertThrows(SecurityException.class, () -> analysis.analyze(fields(), Map.of(), task()));
        assertEquals(1, calls.get());
    }

    @Test
    void sourceVersionRangeIsRejectedAsAConcreteChoice() throws Exception {
        var analysis = analysis((turn, facts) -> turn == 0 ? read() : advice(">=24 <25"));
        assertThrows(SecurityException.class, () -> analysis.analyze(fields(), Map.of(), task()));
        assertEquals(2, calls.get());
    }

    private DeploymentAssistedAnalysis analysis(BiFunction<Integer, JsonNode, String> respond) throws Exception {
        Files.writeString(root.resolve("README.md"), "Required runtime: >=24 <25\n");
        var ai = (AiService) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{AiService.class}, (proxy, method, args) -> {
            assertEquals("call", method.getName());
            var request = (AiRequest) args[0];
            var facts = JSON.readTree(request.messages().getLast().text());
            currentSchema = JSON.readTree(request.tools().getFirst().inputSchema());
            String output = respond.apply(calls.getAndIncrement(), facts);
            assertFalse(request.jsonObjectResponse());
            assertEquals("submit_result", request.tools().getFirst().name());
            return new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(new AiResponse("", "controlled", "tool_calls",
                new AiUsage(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()),
                List.of(new gold.debug.windowstolinux.appapi.ai.model.AiToolCall("submission_" + calls.get(), "submit_result", output)), Optional.empty())), Optional.empty());
        });
        var context = (ModContext) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{ModContext.class}, (proxy, method, args) -> {
            if (method.getName().equals("logs"))
                return (gold.debug.windowstolinux.appapi.task.log.LogService) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{gold.debug.windowstolinux.appapi.task.log.LogService.class}, (peer, m, a) -> m.getName().equals("register") ? new gold.debug.windowstolinux.appapi.task.log.LogTarget(java.util.UUID.randomUUID()) : null);
            assertEquals("ai", method.getName());
            return ai;
        });
        return new DeploymentAssistedAnalysis(context, new DeploymentSourceBrowser(root, REVISION), REVISION);
    }

    private static TaskContext task() {
        return (TaskContext) Proxy.newProxyInstance(TaskContext.class.getClassLoader(), new Class<?>[]{TaskContext.class}, (proxy, method, args) -> {
            if (method.getName().equals("id")) return java.util.UUID.fromString("00000000-0000-0000-0000-000000000001");
            assertEquals("cancelToken", method.getName());
            return (CancelToken) () -> false;
        });
    }

    private static List<DeploymentReviewField> fields() {
        return List.of(new DeploymentReviewField("app/version", "Runtime version", "", "", List.of("22", "24")));
    }

    private static String read() {
        return "{\"action\":\"READ\",\"sourceRevision\":\"" + REVISION + "\",\"arguments\":{\"path\":\"README.md\",\"offset\":0,\"limit\":10}}";
    }

    private static String advice(String candidate) {
        return "{\"action\":\"ADVISE\",\"sourceRevision\":\"" + REVISION + "\",\"arguments\":{\"suggestions\":[{\"field\":\"app/version\",\"candidate\":\"" + candidate
            + "\",\"evidence\":[\"source/0\"]}],\"summary\":\"Observed runtime requirement\",\"unresolved\":[]}}";
    }
}
