package gold.debug.windowstolinux.appapi.ai.model;

import java.time.Duration;
import java.util.*;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AiModelSelectionContractTest {
    @Test
    void copiesKeepDirectSelectionAndExplicitDefaultDistinctFromInheritance() {
        var tool = new AiToolDefinition("submit", "submit", "{\"type\":\"object\"}");
        var original = new AiRequest(AiPurpose.LANGUAGE, List.of(new AiMessage(AiRole.USER, "test", List.of())), 32, Duration.ofSeconds(5), false, List.of(tool));
        assertTrue(original.reasoningOverride().isEmpty());
        var selected = original.withModel("reviewer").withReasoning(AiReasoningMode.DEFAULT).withRequiredTool("submit").withGenerationRecovery(1);
        assertEquals(Optional.of("reviewer"), selected.modelId());
        assertEquals(Optional.of(AiReasoningMode.DEFAULT), selected.reasoningOverride());
        assertEquals(Optional.of("submit"), selected.requiredToolName());
        assertThrows(IllegalArgumentException.class, () -> original.withModel("../../external"));
    }
}
