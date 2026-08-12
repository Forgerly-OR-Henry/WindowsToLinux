package gold.debug.windowstolinux.shared.ai.client;

import gold.debug.windowstolinux.shared.ai.collaboration.AiInvocationStatus;
import gold.debug.windowstolinux.shared.ai.collaboration.AiRoleBinding;
import gold.debug.windowstolinux.shared.ai.collaboration.AiCollaborationRole;
import gold.debug.windowstolinux.shared.ai.collaboration.ProjectAnalysisRoleContext;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenAiCompatibleRoleClientTest {
    private static final AiRoleBinding BINDING = new AiRoleBinding(AiCollaborationRole.PROJECT_ANALYSIS,
            "analysis", URI.create("http://127.0.0.1/v1/chat/completions"), "model-a");
    private static final ProjectAnalysisRoleContext CONTEXT = new ProjectAnalysisRoleContext("sample-app",
            "JAVA_MAVEN_SPRING_BOOT", "MAVEN_WRAPPER", "FORMALLY_SUPPORTED", List.of());
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-13T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void invokesOnlyTheSelectedProviderOnceAndRecordsValidatedEvidence() {
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<String> request = new AtomicReference<>();
        RoleChatTransport transport = (endpoint, apiKey, body) -> {
            calls.incrementAndGet();
            request.set(body);
            assertEquals("http://127.0.0.1/v1/chat/completions", endpoint.toASCIIString());
            return validResponse("CLEAR");
        };

        var result = new OpenAiCompatibleRoleClient(transport, CLOCK).invoke(BINDING, "opaque-key".toCharArray(), CONTEXT);

        assertEquals(1, calls.get());
        assertEquals(AiInvocationStatus.VALIDATED, result.evidence().status());
        assertEquals("analysis", result.evidence().providerId());
        assertEquals("model-a", result.evidence().model());
        assertTrue(result.evidence().inputSha256().matches("[0-9a-f]{64}"));
        assertTrue(result.evidence().output().isPresent());
        assertFalse(request.get().contains("opaque-key"));
        assertTrue(request.get().contains("PROJECT_ANALYSIS"));
    }

    @Test
    void invalidOutputAndTransportFailureDoNotRetryOrRetainResponseBody() {
        AtomicInteger invalidCalls = new AtomicInteger();
        RoleChatTransport invalid = (endpoint, key, body) -> {
            invalidCalls.incrementAndGet();
            return new RoleChatResponse(200, "{\"content\":\"{\\\"decision\\\":\\\"CLEAR\\\","
                    + "\\\"summary\\\":\\\"leaked-response-marker\\\",\\\"findings\\\":[],\\\"extra\\\":true}\"}");
        };
        var invalidResult = new OpenAiCompatibleRoleClient(invalid, CLOCK)
                .invoke(BINDING, "opaque-key".toCharArray(), CONTEXT);

        assertEquals(1, invalidCalls.get());
        assertEquals(AiInvocationStatus.INVALID_OUTPUT, invalidResult.evidence().status());
        assertFalse(invalidResult.evidence().toString().contains("leaked-response-marker"));

        AtomicInteger failedCalls = new AtomicInteger();
        RoleChatTransport failed = (endpoint, key, body) -> {
            failedCalls.incrementAndGet();
            throw new java.io.IOException("provider unavailable with secret response");
        };
        var failedResult = new OpenAiCompatibleRoleClient(failed, CLOCK)
                .invoke(BINDING, "opaque-key".toCharArray(), CONTEXT);
        assertEquals(1, failedCalls.get());
        assertEquals(AiInvocationStatus.UNAVAILABLE, failedResult.evidence().status());
    }

    private static RoleChatResponse validResponse(String decision) {
        String content = "{\\\"decision\\\":\\\"" + decision + "\\\",\\\"summary\\\":"
                + "\\\"Reviewed deterministic facts\\\",\\\"findings\\\":[\\\"No additional finding\\\"]}";
        return new RoleChatResponse(200, "{\"choices\":[{\"message\":{\"content\":\"" + content + "\"}}]}");
    }
}
