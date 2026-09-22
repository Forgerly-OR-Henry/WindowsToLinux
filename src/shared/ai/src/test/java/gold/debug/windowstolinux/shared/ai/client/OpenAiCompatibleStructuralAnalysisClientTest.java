package gold.debug.windowstolinux.shared.ai.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;

import gold.debug.windowstolinux.shared.ai.AiAnalysisException;
import gold.debug.windowstolinux.shared.ai.generation.prompt.AiResponseLanguageType;
import gold.debug.windowstolinux.shared.ai.generation.prompt.StructuralAnalysisPrompt;
import gold.debug.windowstolinux.shared.ai.parser.ChatCompletionResponseParser;
import gold.debug.windowstolinux.shared.ai.redaction.RedactedDeploymentProjectFacts;
import gold.debug.windowstolinux.shared.model.project.DeploymentBuildToolType;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectFacts;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OpenAiCompatibleStructuralAnalysisClientTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void sendsOnlyRedactedStaticFactsAndParsesEscapedContent() throws Exception {
        DeploymentProjectFacts facts = new DeploymentProjectFacts(temporaryDirectory, "demo",
                DeploymentProjectType.SPRING_BOOT, DeploymentBuildToolType.MAVEN_WRAPPER, List.of(), List.of(),
                List.of());

        String body = StructuralAnalysisPrompt.requestBody("gpt-5", RedactedDeploymentProjectFacts.from(facts),
                AiResponseLanguageType.SIMPLIFIED_CHINESE);
        assertTrue(body.contains("applicationId=demo"));
        assertTrue(body.contains("Respond in Simplified Chinese"));
        assertFalse(body.contains(temporaryDirectory.toString()));
        assertTrue(new ChatCompletionResponseParser()
                .parse("{\"choices\":[{\"message\":{\"content\":\"line one\\nline two\"}}]}").explanation()
                .contains("line two"));

        String english = StructuralAnalysisPrompt.requestBody("gpt-5", RedactedDeploymentProjectFacts.from(facts),
                AiResponseLanguageType.ENGLISH);
        assertTrue(english.contains("Respond in English"));
        assertFalse(english.matches("(?s).*\\p{IsHan}.*"));
    }

    @Test
    void exposesAStableMessageKeyAndEnglishDiagnosticForInvalidResponses() {
        AiAnalysisException failure = assertThrows(AiAnalysisException.class,
                () -> new ChatCompletionResponseParser().parse("{}"));

        assertEquals("ai.error.responseContentMissing", failure.failure().userMessage().key());
        assertEquals("AI response does not contain an explanation field", failure.failure().diagnostic());
    }

    @Test
    void keepsTypedDeploymentFactsRedactedToIdentityTypeAndFixedBuildTool() {
        DeploymentProjectFacts facts = new DeploymentProjectFacts(temporaryDirectory, "demo",
                DeploymentProjectType.NODE_SERVICE, DeploymentBuildToolType.NPM, List.of(), List.of(), List.of());

        String body = StructuralAnalysisPrompt.requestBody("gpt-5", RedactedDeploymentProjectFacts.from(facts),
                AiResponseLanguageType.ENGLISH);

        assertTrue(body.contains("applicationId=demo"));
        assertTrue(body.contains("projectType=NODE_SERVICE"));
        assertTrue(body.contains("fixedBuildTool=NPM"));
        assertFalse(body.contains(temporaryDirectory.toString()));
    }
}
