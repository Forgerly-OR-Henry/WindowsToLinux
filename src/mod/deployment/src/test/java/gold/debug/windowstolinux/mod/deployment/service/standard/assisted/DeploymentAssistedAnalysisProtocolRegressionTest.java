package gold.debug.windowstolinux.mod.deployment.service.standard.assisted;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import gold.debug.windowstolinux.appapi.ai.model.AiToolCall;
import gold.debug.windowstolinux.mod.deployment.ai.NativeAiFixture;
import gold.debug.windowstolinux.mod.deployment.file.DeploymentSourceBrowser;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.input.DeploymentReviewField;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class DeploymentAssistedAnalysisProtocolRegressionTest {
    @TempDir
    Path source;

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2})
    void invalidStructureIsCorrectedBeforeReadingSourceAndTheOfferedSchemaMatchesTheParser(int invalidKind) throws Exception {
        Files.writeString(source.resolve("README.md"), "Node 24\n");
        var fixture = new NativeAiFixture();
        String revision = "a".repeat(64);
        var invalid = new AiToolCall("invalid", "submit_result", invalidKind == 1
            ? "{\"action\":\"READ\",\"sourceRevision\":\"" + revision + "\",\"arguments\":{\"path\":\"../absent\"}}"
            : "{\"action\":\"READ\",\"arguments\":{\"path\":\"../absent\",\"offset\":0,\"limit\":10}}");
        if (invalidKind == 2)
            fixture.nativeReply(invalid, new AiToolCall("invalid-second", "submit_result", invalid.arguments()));
        else fixture.nativeReply(invalid);
        fixture.nativeReply(new AiToolCall("read", "submit_result", "{\"action\":\"READ\",\"sourceRevision\":\"" + revision + "\",\"arguments\":{\"path\":\"README.md\",\"offset\":0,\"limit\":10}}"));
        fixture.nativeReply(new AiToolCall("advise", "submit_result", "{\"action\":\"ADVISE\",\"sourceRevision\":\"" + revision + "\",\"arguments\":{\"suggestions\":[{\"field\":\"app/version\",\"candidate\":\"24\",\"evidence\":[\"source/0\"]}],\"summary\":\"Read source\",\"unresolved\":[]}}"));
        var result = new DeploymentAssistedAnalysis(fixture.context, new DeploymentSourceBrowser(source, revision), fixture.configurationRevision)
            .analyze(List.of(new DeploymentReviewField("app/version", "version", "", "", List.of("24"))), Map.of(), fixture.task);
        assertEquals(Map.of("app/version", "24"), result.values());
        assertEquals(3, fixture.requests.size());
        assertTrue(fixture.requests.getFirst().requiredToolName().isEmpty());
        var schema = new ObjectMapper().readTree(fixture.requests.getFirst().tools().getFirst().inputSchema());
        assertEquals(3, schema.path("required").size());
        assertEquals(4, schema.at("/properties/action/enum").size());
        assertEquals(3, schema.at("/properties/arguments/oneOf").size());
        assertTrue(fixture.requests.get(1).messages().stream().flatMap(message -> message.toolResult().stream())
            .anyMatch(receipt -> receipt.error() && receipt.text().contains("SUBMISSION_REJECTED")));
        var finalFacts = new ObjectMapper().readTree(fixture.requests.getLast().messages().getLast().text());
        assertEquals(11, finalFacts.path("remaining").asInt());
        assertEquals("README.md", finalFacts.path("observations").get(0).path("path").asText());
    }
}
