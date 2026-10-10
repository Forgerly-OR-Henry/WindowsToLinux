package gold.debug.windowstolinux.appapi.ai.model;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.*;

import org.junit.jupiter.api.Test;

class AiOutputTokenContractTest {
    private final List<AiMessage> messages = List.of(new AiMessage(AiRole.USER, "hello", List.of()));

    private final Duration timeout = Duration.ofSeconds(10);

    @Test
    void omittedAndExplicitBudgetsRemainDistinctAndInvalidValuesAreRejected() {
        assertTrue(new AiRequest(AiPurpose.LANGUAGE, messages, timeout, false).maxOutputTokens().isEmpty());
        assertTrue(new AiRequest(AiPurpose.LANGUAGE, messages, timeout, false, List.of()).maxOutputTokens().isEmpty());
        assertEquals(Optional.of(65536), new AiRequest(AiPurpose.LANGUAGE, messages, 65536, timeout, false).maxOutputTokens());
        assertEquals(Optional.of(1), new AiRequest(AiPurpose.VISION, messages, Optional.of(1), timeout, false).maxOutputTokens());
        for (int invalid : new int[]{0, -1, Integer.MIN_VALUE}) {
            assertThrows(IllegalArgumentException.class, () -> new AiRequest(AiPurpose.LANGUAGE, messages, invalid, timeout, false));
            assertThrows(IllegalArgumentException.class, () -> new AiRequest(AiPurpose.LANGUAGE, messages, Optional.of(invalid), timeout, false, List.of(), 1024));
        }
        assertThrows(NullPointerException.class, () -> new AiRequest(AiPurpose.LANGUAGE, messages, (Optional<Integer>) null, timeout, false));
    }

    @Test
    void responseReportsOnlyAnOptionalPositiveEffectiveBudget() {
        var usage = new AiUsage(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
        assertTrue(new AiResponse("ok", "model", "stop", usage).effectiveMaxOutputTokens().isEmpty());
        var response = new AiResponse("ok", "model", "stop", usage, List.of(), Optional.empty(), Optional.of(128), Optional.of(32768));
        assertEquals(Optional.of(32768), response.effectiveMaxOutputTokens());
        for (int invalid : new int[]{0, -1})
            assertThrows(IllegalArgumentException.class, () -> new AiResponse("ok", "model", "stop", usage, List.of(), Optional.empty(), Optional.empty(), Optional.of(invalid)));
    }
}
