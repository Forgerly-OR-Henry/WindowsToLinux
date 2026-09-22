package gold.debug.windowstolinux.shared.ai.recovery;

import static gold.debug.windowstolinux.shared.ai.recovery.RecoveryModelClient.OutcomeStatus.*;
import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.util.*;

import gold.debug.windowstolinux.shared.ai.transport.RoleChatResult;
import org.junit.jupiter.api.Test;

class RecoveryModelClientTest {
    private final URI endpoint = URI.create("https://example.test/v1/chat/completions");
    @Test
    void visionUsesImageInputAndMalformedResponsesAreNotNetworkFailures() throws Exception {
        List<String> bodies = new ArrayList<>();
        var client = new RecoveryModelClient((url, key, body) -> {
            bodies.add(body);
            return new RoleChatResult(200, "not json");
        });
        assertEquals(INVALID, client.observe(endpoint, "vision", new char[]{'k'}, new byte[]{1, 2}).status());
        assertTrue(bodies.getFirst().contains("image_url"));
        assertTrue(bodies.getFirst().contains("data:image/png;base64,AQI="));
        assertFalse(bodies.getFirst().contains("Authorization"));
    }

    @Test
    void unsupportedImagesAndTransportFaultsHaveDifferentStatuses() throws Exception {
        assertEquals(UNSUPPORTED,
                new RecoveryModelClient((u, k, b) -> new RoleChatResult(400, "image input not supported"))
                        .observe(endpoint, "text", new char[]{'k'}, new byte[]{1}).status());
        assertEquals(UNAVAILABLE, new RecoveryModelClient((u, k, b) -> {
            throw new java.io.IOException("secret response");
        }).observe(endpoint, "vision", new char[]{'k'}, new byte[]{1}).status());
        assertEquals(UNAVAILABLE,
                new RecoveryModelClient((u, k, b) -> new RoleChatResult(503, "vision unsupported service temporarily"))
                        .observe(endpoint, "vision", new char[]{'k'}, new byte[]{1}).status());
    }

    @Test
    void observationCannotSmuggleActionsAndCommandsNeedStrictSchemas() throws Exception {
        assertThrows(Exception.class,
                () -> RecoveryModelClient.parseObservation("{\"observation\":\"ok\",\"command\":\"rm\"}"));
        assertThrows(Exception.class,
                () -> RecoveryModelClient.parseObservation("{\"observation\":\"a\",\"observation\":\"b\"}"));
        assertThrows(Exception.class, () -> RecoveryModelClient.parseObservation("{\"observation\":\"ok\"} {}"));
        assertThrows(Exception.class, () -> RecoveryModelClient
                .parseAction("{\"command\":\"id\\nreboot\",\"reason\":\"x\",\"expected\":\"x\",\"highImpact\":false}"));
        var action = RecoveryModelClient
                .parseAction("{\"command\":\"reboot\",\"reason\":\"x\",\"expected\":\"restart\",\"highImpact\":false}");
        assertTrue(action.highImpact());
        assertFalse(action.toString().contains("reboot"));
    }

    @Test
    void successfulTextReplyCannotPassIndependentVisionProbe() {
        var client = new RecoveryModelClient((u, k, b) -> new RoleChatResult(200,
                "{\"choices\":[{\"message\":{\"content\":\"{\\\"observation\\\":\\\"generic text\\\"}\"}}]}"));
        assertThrows(IllegalArgumentException.class, () -> client.probeVision(endpoint, "vision", new char[]{'k'}));
    }
}
