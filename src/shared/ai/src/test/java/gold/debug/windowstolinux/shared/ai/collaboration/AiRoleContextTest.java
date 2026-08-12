package gold.debug.windowstolinux.shared.ai.collaboration;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiRoleContextTest {
    @Test
    void contextsExposeOnlyRoleSpecificRedactedFacts() {
        AiRoleContext project = new ProjectAnalysisRoleContext("sample-app", "JAVA_MAVEN_SPRING_BOOT",
                "MAVEN_WRAPPER", "FORMALLY_SUPPORTED", List.of("analysis.missing.port"));
        AiRoleContext risk = new DeploymentRiskRoleContext("sample-app", "a".repeat(64), "UBUNTU", "24.04",
                "X86_64", "FORMALLY_SUPPORTED", false, List.of("api", "web"));

        assertFalse(project.redactedSummary().contains("C:\\private\\source"));
        assertFalse(project.redactedSummary().toLowerCase().contains("password"));
        assertFalse(risk.redactedSummary().toLowerCase().contains("credential"));
        assertTrue(risk.redactedSummary().contains("releaseIdentity=" + "a".repeat(64)));
    }

    @Test
    void errorContextRemovesCredentialShapesAndBoundsDiagnostic() {
        String privateKey = "-----BEGIN OPENSSH PRIVATE KEY-----hidden-----END OPENSSH PRIVATE KEY-----";
        ErrorExplanationRoleContext context = new ErrorExplanationRoleContext("publish-release",
                "Authorization: Bearer abc password=hunter2 token:secret " + privateKey + " x".repeat(2_000));

        assertFalse(context.redactedSummary().contains("abc"));
        assertFalse(context.redactedSummary().contains("hunter2"));
        assertFalse(context.redactedSummary().contains("token:secret"));
        assertFalse(context.redactedSummary().contains("OPENSSH PRIVATE KEY"));
        assertTrue(context.safeDiagnostic().length() <= 1_027);
    }
}
