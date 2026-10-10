package gold.debug.windowstolinux.appapi.ai.model;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

class AiRequiredToolContractTest {
    private final List<AiMessage> messages = List.of(new AiMessage(AiRole.USER, "Submit the reviewed result", List.of()));

    private final Duration timeout = Duration.ofSeconds(17);

    private final AiToolDefinition submit = new AiToolDefinition("submit_result", "Submit a result", "{\"type\":\"object\"}");

    @Test
    void everyLegacyConstructorRetainsAutomaticToolSelection() {
        var requests = List.of(
            new AiRequest(AiPurpose.APPROVAL, messages, Optional.of(128), timeout, false, List.of(submit), 4096),
            new AiRequest(AiPurpose.APPROVAL, messages, 128, timeout, false, List.of(submit), 4096),
            new AiRequest(AiPurpose.APPROVAL, messages, Optional.of(128), timeout, false, List.of(submit)),
            new AiRequest(AiPurpose.APPROVAL, messages, 128, timeout, false, List.of(submit)),
            new AiRequest(AiPurpose.APPROVAL, messages, Optional.of(128), timeout, false),
            new AiRequest(AiPurpose.APPROVAL, messages, 128, timeout, false),
            new AiRequest(AiPurpose.APPROVAL, messages, timeout, false, List.of(submit)),
            new AiRequest(AiPurpose.APPROVAL, messages, timeout, false));
        for (var request : requests)
            assertTrue(request.requiredToolName().isEmpty());
        assertEquals(4096, requests.getFirst().maxRequestBytes());
        assertEquals(1024 * 1024, requests.get(2).maxRequestBytes());
        assertTrue(requests.getLast().maxOutputTokens().isEmpty());
    }

    @Test
    void requiringARegisteredToolCreatesAnImmutableCopyWithAllOriginalBounds() {
        var mutableMessages = new ArrayList<>(messages);
        var mutableTools = new ArrayList<>(List.of(submit,
            new AiToolDefinition("inspect", "Inspect a result", "{\"type\":\"object\"}")));
        var original = new AiRequest(AiPurpose.APPROVAL, mutableMessages, Optional.empty(), timeout, true, mutableTools, 4096);
        var required = original.withRequiredTool("submit_result");
        mutableMessages.clear();
        mutableTools.clear();

        assertNotSame(original, required);
        assertTrue(original.requiredToolName().isEmpty());
        assertEquals(Optional.of("submit_result"), required.requiredToolName());
        assertEquals(original.purpose(), required.purpose());
        assertEquals(messages, required.messages());
        assertEquals(original.maxOutputTokens(), required.maxOutputTokens());
        assertEquals(timeout, required.timeout());
        assertEquals(original.jsonObjectResponse(), required.jsonObjectResponse());
        assertEquals(original.tools(), required.tools());
        assertEquals(4096, required.maxRequestBytes());
        assertThrows(UnsupportedOperationException.class, () -> required.messages().clear());
        assertThrows(UnsupportedOperationException.class, () -> required.tools().clear());
        assertEquals(Optional.of("inspect"), required.withRequiredTool("inspect").requiredToolName());
        assertEquals(Optional.of("submit_result"), required.requiredToolName());
    }

    @Test
    void bothPublicEntryPointsRejectMissingUnknownAndInvalidToolNames() {
        var original = new AiRequest(AiPurpose.APPROVAL, messages, 128, timeout, false, List.of(submit));
        for (String invalid : List.of("", " ", "missing", "submit_result ", "submit/result", "x".repeat(65))) {
            assertThrows(IllegalArgumentException.class, () -> original.withRequiredTool(invalid));
            assertThrows(IllegalArgumentException.class, () -> new AiRequest(AiPurpose.APPROVAL, messages,
                Optional.of(128), timeout, false, List.of(submit), 4096, Optional.of(invalid)));
        }
        var noTools = new AiRequest(AiPurpose.APPROVAL, messages, timeout, false);
        assertThrows(IllegalArgumentException.class, () -> noTools.withRequiredTool("submit_result"));
        assertThrows(IllegalArgumentException.class, () -> new AiRequest(AiPurpose.APPROVAL, messages,
            Optional.empty(), timeout, false, List.of(), 4096, Optional.of("submit_result")));
        assertThrows(NullPointerException.class, () -> original.withRequiredTool(null));
        assertThrows(NullPointerException.class, () -> new AiRequest(AiPurpose.APPROVAL, messages,
            Optional.empty(), timeout, false, List.of(submit), 4096, null));
        var required = original.withRequiredTool("submit_result");
        assertThrows(IllegalArgumentException.class, () -> new AiRequest(required.purpose(), required.messages(),
            required.maxOutputTokens(), required.timeout(), false, required.tools(), 1024 * 1024 + 1, required.requiredToolName()));
        assertThrows(IllegalArgumentException.class, () -> new AiRequest(required.purpose(), required.messages(),
            required.maxOutputTokens(), Duration.ZERO, false, required.tools(), 4096, required.requiredToolName()));
        assertThrows(IllegalArgumentException.class, () -> new AiRequest(required.purpose(), required.messages(),
            Optional.of(0), required.timeout(), false, required.tools(), 4096, required.requiredToolName()));
    }
}
