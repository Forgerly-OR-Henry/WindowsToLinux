package gold.debug.windowstolinux.shared.ai.client;

import gold.debug.windowstolinux.shared.ai.parser.ChatCompletionsResponseParser;
import gold.debug.windowstolinux.shared.ai.prompt.StructuralAnalysisPrompt;
import gold.debug.windowstolinux.shared.ai.prompt.AiResponseLanguage;
import gold.debug.windowstolinux.shared.ai.redaction.RedactedDeploymentProjectFacts;
import gold.debug.windowstolinux.shared.model.project.DeploymentBuildTool;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectFacts;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenAiCompatibleStructuralAnalyzerTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void sendsOnlyRedactedStaticFactsAndParsesEscapedContent() throws Exception {
        DeploymentProjectFacts facts = new DeploymentProjectFacts(temporaryDirectory, "demo",
                DeploymentProjectType.SPRING_BOOT, DeploymentBuildTool.MAVEN_WRAPPER,
                List.of(), List.of(), List.of());

        String body = StructuralAnalysisPrompt.requestBody("gpt-5", RedactedDeploymentProjectFacts.from(facts),
                AiResponseLanguage.SIMPLIFIED_CHINESE);
        assertTrue(body.contains("applicationId=demo"));
        assertTrue(body.contains("Respond in Simplified Chinese"));
        assertFalse(body.contains(temporaryDirectory.toString()));
        assertTrue(new ChatCompletionsResponseParser().parse(
                "{\"choices\":[{\"message\":{\"content\":\"line one\\nline two\"}}]}"
        ).explanation().contains("line two"));

        String english = StructuralAnalysisPrompt.requestBody("gpt-5", RedactedDeploymentProjectFacts.from(facts),
                AiResponseLanguage.ENGLISH);
        assertTrue(english.contains("Respond in English"));
        assertFalse(english.matches("(?s).*\\p{IsHan}.*"));
    }

    @Test
    void exposesAStableMessageKeyAndEnglishDiagnosticForInvalidResponses() {
        AiAnalysisException failure = assertThrows(AiAnalysisException.class,
                () -> new ChatCompletionsResponseParser().parse("{}"));

        assertEquals("ai.error.responseContentMissing", failure.userMessage().key());
        assertEquals("AI response does not contain an explanation field", failure.diagnostic());
    }

    @Test
    void keepsTypedDeploymentFactsRedactedToIdentityTypeAndFixedBuildTool() {
        DeploymentProjectFacts facts = new DeploymentProjectFacts(temporaryDirectory, "demo",
                DeploymentProjectType.NODE_SERVICE, DeploymentBuildTool.NPM, List.of(), List.of(), List.of());

        String body = StructuralAnalysisPrompt.requestBody("gpt-5", RedactedDeploymentProjectFacts.from(facts),
                AiResponseLanguage.ENGLISH);

        assertTrue(body.contains("applicationId=demo"));
        assertTrue(body.contains("projectType=NODE_SERVICE"));
        assertTrue(body.contains("fixedBuildTool=NPM"));
        assertFalse(body.contains(temporaryDirectory.toString()));
    }
}
