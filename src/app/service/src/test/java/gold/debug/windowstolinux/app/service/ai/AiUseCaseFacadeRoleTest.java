package gold.debug.windowstolinux.app.service.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import gold.debug.windowstolinux.app.db.DesktopPersistence;
import gold.debug.windowstolinux.app.service.SecurityTestFixture;
import gold.debug.windowstolinux.app.service.contract.profile.AiProviderProfile;
import gold.debug.windowstolinux.app.service.server.DesktopSecretStoreService;
import gold.debug.windowstolinux.shared.ai.client.OpenAiCompatibleRoleClient;
import gold.debug.windowstolinux.shared.ai.collaboration.invocation.AiInvocationStatus;
import gold.debug.windowstolinux.shared.ai.collaboration.role.AiCollaborationRoleKind;
import gold.debug.windowstolinux.shared.ai.collaboration.role.ProjectAnalysisRoleContext;
import gold.debug.windowstolinux.shared.ai.transport.RoleChatResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AiUseCaseFacadeRoleTest extends SecurityTestFixture {
    @TempDir
    Path temporaryDirectory;

    @Test
    void invokesTheEnabledProviderWithTheRequestedRoleContract() throws Exception {
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
                    SecurityTestFixture.secrets(persistence), client);
            var profile = new AiProviderProfile("analysis",
                    URI.create("https://analysis.example.test/v1/chat/completions"), "analysis-model", "ai/analysis");
            try (var store = SecurityTestFixture.secrets(persistence).open()) {
                store.save(profile.credentialKey(), "selected-secret".toCharArray());
            }

            persistence.aiProfiles().saveVerified(profile.stored(), profile.id(), java.time.Instant.now());
            persistence.aiProfiles().purposes().save(gold.debug.windowstolinux.shared.model.ai.AiPurposeType.DEPLOYMENT,
                    java.util.List
                            .of(new gold.debug.windowstolinux.shared.model.ai.AiPurposeAssignment(profile.id(), true)));

            var result = useCases.invokeRole(new ProjectAnalysisRoleContext("sample-app", "JAVA_MAVEN_SPRING_BOOT",
                    "MAVEN_WRAPPER", "FORMALLY_SUPPORTED", List.of()));

            assertTrue(result.isPresent());
            assertEquals(AiInvocationStatus.VALIDATED, result.orElseThrow().evidence().status());
            assertEquals("analysis-model", result.orElseThrow().evidence().model());
            assertEquals(1, calls.get());
        }
    }
}
