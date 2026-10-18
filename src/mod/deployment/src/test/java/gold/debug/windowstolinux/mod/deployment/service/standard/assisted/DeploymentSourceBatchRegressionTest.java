package gold.debug.windowstolinux.mod.deployment.service.standard.assisted;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import gold.debug.windowstolinux.appapi.ai.model.AiToolCall;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.mod.deployment.ai.NativeAiFixture;
import gold.debug.windowstolinux.mod.deployment.file.DeploymentSourceBrowser;
import gold.debug.windowstolinux.mod.deployment.service.core.model.project.kind.DeploymentProjectKind;
import gold.debug.windowstolinux.mod.deployment.service.deploy.analyze.part.DiscoveredProjectPart;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

class DeploymentSourceBatchRegressionTest {
    @TempDir
    Path source;

    private final NativeAiFixture fixture = new NativeAiFixture();

    @Test
    void oversizedBatchReturnsCompleteErrorsAndCanRecoverWithoutReadingRejectedSource() throws Exception {
        Files.writeString(source.resolve("README.md"), "Go service\n");
        rejectableBatch();
        fixture.nativeReply(new AiToolCall("valid-read", "read", "{\"path\":\"README.md\",\"offset\":0,\"limit\":10}"));
        fixture.nativeReply(new AiToolCall("identified", "identify", "{\"components\":[{\"id\":\"main\",\"root\":\".\",\"type\":\"GO_SERVICE\",\"evidence\":[\"README.md\"]}]}"));
        assertEquals(recognized(), compare());
        assertEquals(3, fixture.requests.size());
        var retry = fixture.requests.get(1);
        var errors = retry.messages().stream().flatMap(message -> message.toolResult().stream()).toList();
        assertEquals(5, errors.size());
        assertTrue(errors.stream().allMatch(result -> result.error() && result.text().contains("no source was read")));
        var facts = new ObjectMapper().readTree(retry.messages().getLast().text());
        assertEquals(11, facts.path("remaining").asInt());
        assertFalse(facts.path("observations").toString().contains("output"));
        for (var tool : retry.tools().stream().filter(tool -> List.of("list", "read", "search").contains(tool.name())).toList()) {
            var schema = new ObjectMapper().readTree(tool.inputSchema());
            assertEquals(100, schema.at("/properties/limit/maximum").asInt());
            assertEquals(1, schema.at("/properties/limit/minimum").asInt());
            assertEquals(1000000, schema.at("/properties/offset/maximum").asInt());
        }
        var finalRequest = fixture.requests.getLast();
        var submission = new ObjectMapper().readTree(finalRequest.tools().stream().filter(tool -> tool.name().equals("identify"))
            .findFirst().orElseThrow().inputSchema());
        var component = submission.at("/properties/components/items");
        assertEquals(4, component.path("required").size());
        assertEquals("README.md", component.at("/properties/evidence/items/enum/0").asText());
        assertEquals(1, component.at("/properties/evidence/items/enum").size());
        assertTrue(component.at("/properties/type/enum").toString().contains("GO_SERVICE"));
    }

    @Test
    void rejectedBatchesCannotResetTheOriginalDecisionBudget() throws Exception {
        for (int turn = 0; turn < 12; turn++) rejectableBatch();
        assertThrows(java.io.IOException.class, this::compare);
        assertEquals(12, fixture.requests.size());
    }

    @Test
    void originalFinalTurnRetainsItsBudgetAndRequestsIdentification() throws Exception {
        Files.writeString(source.resolve("README.md"), "Go service\n");
        for (int turn = 0; turn < 11; turn++)
            fixture.nativeReply(new AiToolCall("read-" + turn, "read", "{\"path\":\"README.md\",\"offset\":0,\"limit\":1}"));
        fixture.nativeReply(new AiToolCall("identified", "identify", "{\"components\":[{\"id\":\"main\",\"root\":\".\",\"type\":\"GO_SERVICE\",\"evidence\":[\"README.md\"]}]}"));
        assertEquals(recognized(), compare());
        assertEquals(12, fixture.requests.size());
        assertEquals(1, new ObjectMapper().readTree(fixture.requests.getLast().messages().getLast().text()).path("remaining").asInt());
        assertTrue(fixture.requests.getLast().messages().getFirst().text().contains("Reserve the final turn for identify/unable or conclude"));
    }

    private List<DiscoveredProjectPart> compare() throws Exception {
        return DeploymentSourceComparison.compare(fixture.context, new ServerId("server"),
            new DeploymentSourceBrowser(source, "a".repeat(64)), recognized(), fixture.task);
    }

    private static List<DiscoveredProjectPart> recognized() {
        return List.of(new DiscoveredProjectPart("main", Path.of(""), List.of(DeploymentProjectKind.GO_SERVICE)));
    }

    private void rejectableBatch() {
        fixture.nativeReply(java.util.stream.IntStream.range(0, 5).mapToObj(index ->
                new AiToolCall("rejected-" + index, "read", "{\"path\":\"absent.txt\",\"offset\":0,\"limit\":200}"))
            .toArray(AiToolCall[]::new));
    }
}
