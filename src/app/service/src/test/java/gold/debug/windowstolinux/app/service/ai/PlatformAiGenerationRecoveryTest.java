package gold.debug.windowstolinux.app.service.ai;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Optional;

import gold.debug.windowstolinux.appapi.task.result.Problem;
import gold.debug.windowstolinux.appapi.task.result.ProblemCode;
import org.junit.jupiter.api.Test;

class PlatformAiGenerationRecoveryTest {
    @Test
    void connectionTimeoutRequiresTheExactTransportClassification() {
        var connect = Map.of("timeoutPhase", "HTTP_CONNECT", "transportFailureKind", "HTTP_CONNECT_TIMEOUT",
            "transportFailureType", "java.net.http.HttpConnectTimeoutException");
        assertTrue(PlatformAiService.recoverableGenerationTransport(problem(ProblemCode.TIMEOUT, "AI_TIMEOUT", connect)));
        assertFalse(PlatformAiService.recoverableGenerationTransport(problem(ProblemCode.CANCELLED, "AI_TIMEOUT", connect)));
        assertFalse(PlatformAiService.recoverableGenerationTransport(problem(ProblemCode.TIMEOUT, "AI_TIMEOUT",
            Map.of("timeoutPhase", "HTTP_REQUEST", "transportFailureType", "java.net.http.HttpTimeoutException"))));
        assertFalse(PlatformAiService.recoverableGenerationTransport(problem(ProblemCode.TIMEOUT, "AI_TIMEOUT",
            Map.of("timeoutPhase", "REQUEST_BUDGET"))));
        assertTrue(PlatformAiService.recoverableGenerationTransport(problem(ProblemCode.IO_FAILURE, "AI_TRANSPORT_UNKNOWN",
            Map.of("transportFailureKind", "HTTP_BODY_INCOMPLETE"))));
    }

    private static Problem problem(ProblemCode code, String event, Map<String, String> details) {
        return new Problem(code, event, "fixture", details, Optional.empty());
    }
}
