package gold.debug.windowstolinux.mod.deployment.ai;

import java.util.Map;

import gold.debug.windowstolinux.appapi.ai.model.AiPurpose;
import gold.debug.windowstolinux.appapi.ai.model.AiToolCall;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DeploymentStructuredSubmissionRegressionTest {
    @Test
    void requiredApprovalSubmissionStillRejectsMultipleVerdictsWithoutAnyExecution() {
        var fixture = new NativeAiFixture();
        fixture.nativeReply(new AiToolCall("first", "submit_result", "{}"), new AiToolCall("second", "submit_result", "{}"));
        assertThrows(java.io.IOException.class, () -> new DeploymentAiJson(fixture.context)
            .ask(AiPurpose.APPROVAL, "Review", Map.of(), fixture.configurationRevision, fixture.task));
        assertEquals(1, fixture.requests.size());
        assertTrue(fixture.requests.getFirst().requiredToolName().isEmpty());
    }

    @Test
    void repeatedLanguageBatchesCannotExpandTheOriginalTwoSubmissionAttempts() {
        var fixture = new NativeAiFixture();
        for (int attempt = 0; attempt < 2; attempt++)
            fixture.nativeReply(new AiToolCall("first-" + attempt, "submit_result", "{}"), new AiToolCall("second-" + attempt, "submit_result", "{}"));
        assertThrows(java.io.IOException.class, () -> new DeploymentAiJson(fixture.context)
            .ask(AiPurpose.LANGUAGE, "Analyse", Map.of(), fixture.configurationRevision, fixture.task));
        assertEquals(2, fixture.requests.size());
        assertTrue(fixture.requests.stream().allMatch(request -> request.requiredToolName().isEmpty()),
            "Language reasoning keeps automatic tool selection");
        var receipts = fixture.requests.getLast().messages().stream().flatMap(message -> message.toolResult().stream()).toList();
        assertEquals(2, receipts.size());
        assertTrue(receipts.stream().allMatch(receipt -> receipt.error() && receipt.text().contains("no action was executed")));
    }

}
