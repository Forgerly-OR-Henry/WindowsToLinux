package gold.debug.windowstolinux.mod.deployment.service.standard;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import gold.debug.windowstolinux.appapi.ai.model.AiToolCall;
import org.junit.jupiter.api.Test;

class DeploymentLiveSubmissionShapeTest {
    @Test
    void duplicateTrailingAndMalformedArgumentsNeverLeakPrivateJson() throws Exception {
        var json = new ObjectMapper();
        for (String input : new String[]{"{\"private-credential\":1,\"private-credential\":2}",
            "{\"private-credential\":1} {}", "{\"private-credential\":\"secret"}) {
            String diagnostic = DeploymentLiveSubmissionShape.describe(new AiToolCall("sample", "submit_result", input));
            assertEquals("INVALID", json.readTree(diagnostic).path("syntax").asText());
            assertFalse(diagnostic.contains("private"));
            assertFalse(diagnostic.contains("secret"));
            assertTrue(diagnostic.length() < 500);
        }
        String diagnostic = DeploymentLiveSubmissionShape.describe(new AiToolCall("sample", "submit_result",
            "{\"action\":\"private-credential\",\"sourceRevision\":\"private-secret\",\"arguments\":{\"private\":1}}"));
        assertEquals("OTHER", json.readTree(diagnostic).path("action").asText());
        assertFalse(diagnostic.contains("private"));
    }
}
