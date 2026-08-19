package gold.debug.windowstolinux.app.service.ai;

import gold.debug.windowstolinux.app.db.DesktopPersistence;
import gold.debug.windowstolinux.app.service.server.DesktopSecretStoreService;
import gold.debug.windowstolinux.shared.ai.client.OpenAiCompatibleRoleClient;
import gold.debug.windowstolinux.shared.ai.transport.RoleChatResult;
import gold.debug.windowstolinux.shared.ai.collaboration.role.AiCollaborationRoleKind;
import gold.debug.windowstolinux.shared.ai.collaboration.invocation.AiInvocationStatus;
import gold.debug.windowstolinux.shared.ai.collaboration.role.ProjectAnalysisRoleContext;
import gold.debug.windowstolinux.shared.model.security.CredentialStorageMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiUseCaseFacadeRoleTest {
    @TempDir Path temporaryDirectory;

    @Test
    void invokesOnlyTheProviderExplicitlyAssignedToTheRole() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        var client = new OpenAiCompatibleRoleClient((endpoint, apiKey, body) -> {
            calls.incrementAndGet();
            assertEquals("https://analysis.example.test/v1/chat/completions", endpoint.toASCIIString());
            assertEquals("selected-secret", new String(apiKey));
            String content = "{\\\"decision\\\":\\\"CLEAR\\\",\\\"summary\\\":\\\"facts reviewed\\\",\\\"findings\\\":[]}";
            return new RoleChatResult(200, "{\"content\":\"" + content + "\"}");
        }, Clock.fixed(Instant.parse("2026-08-13T00:00:00Z"), ZoneOffset.UTC));

        try (DesktopPersistence persistence = DesktopPersistence.open(temporaryDirectory)) {
            AiUseCaseFacade useCases = new AiUseCaseFacade(persistence.aiProfiles(),
                    new DesktopSecretStoreService(persistence.encryptedSecrets()), client);
            useCases.saveNamed(new AiProviderProfile("analysis",
                            URI.create("https://analysis.example.test/v1/chat/completions"), "analysis-model",
                            "ai/analysis", CredentialStorageMode.MASTER_PASSWORD),
                    "master-password".toCharArray(), "selected-secret".toCharArray());
            useCases.assignRole(new AiRoleAssignment(AiCollaborationRoleKind.PROJECT_ANALYSIS, "analysis"));

            var result = useCases.invokeRole(new ProjectAnalysisRoleContext("sample-app", "JAVA_MAVEN_SPRING_BOOT",
                    "MAVEN_WRAPPER", "FORMALLY_SUPPORTED", List.of()), "master-password".toCharArray());

            assertTrue(result.isPresent());
            assertEquals(AiInvocationStatus.VALIDATED, result.orElseThrow().evidence().status());
            assertEquals("analysis-model", result.orElseThrow().evidence().model());
            assertEquals(1, calls.get());
        }
    }
}
