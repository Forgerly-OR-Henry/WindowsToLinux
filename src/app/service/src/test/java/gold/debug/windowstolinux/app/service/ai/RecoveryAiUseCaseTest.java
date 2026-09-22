package gold.debug.windowstolinux.app.service.ai;

import static gold.debug.windowstolinux.shared.ai.recovery.RecoveryModelClient.OutcomeStatus.*;
import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.*;

import gold.debug.windowstolinux.app.db.entity.*;
import gold.debug.windowstolinux.shared.ai.recovery.RecoveryModelClient;
import gold.debug.windowstolinux.shared.model.ai.*;
import org.junit.jupiter.api.Test;

class RecoveryAiUseCaseTest {
    @org.junit.jupiter.api.io.TempDir
    java.nio.file.Path directory;
    private StoredAiProviderConfiguration model(String id) {
        return new StoredAiProviderConfiguration(new StoredAiProviderProfile(id,
                "https://example.test/v1/chat/completions", id, "secret/" + id, "MASTER_PASSWORD"), id, 0, 1,
                Optional.of(Instant.now()), Optional.of(Instant.now()));
    }

    @Test
    void routingHonorsProvidedPurposeSnapshotAndNeverReordersByInventory() {
        var values = new ArrayList<>(List.of(model("second"), model("first")));
        var calls = new ArrayList<String>();
        var result = RecoveryAiUseCase.route(values, true, (p, vision) -> {
            assertTrue(vision);
            values.clear();
            calls.add(p.profile().id());
            return new RecoveryModelClient.Reply(UNSUPPORTED, "");
        });
        assertEquals(List.of("second", "first"), calls);
        assertEquals("visionUnsupported", result.code());
    }

    @Test
    void validResponseStopsFallbackAndFailuresStayDistinct() {
        var calls = new ArrayList<String>();
        var result = RecoveryAiUseCase.route(List.of(model("one"), model("two")), true, (p, v) -> {
            calls.add(p.profile().id());
            return new RecoveryModelClient.Reply(VALID, "facts");
        });
        assertEquals(List.of("one"), calls);
        assertEquals("facts", result.content());
        assertEquals("modelUnavailable", RecoveryAiUseCase
                .route(List.of(model("one")), true, (p, v) -> new RecoveryModelClient.Reply(UNAVAILABLE, "")).code());
        assertEquals("modelInvalid", RecoveryAiUseCase
                .route(List.of(model("one")), true, (p, v) -> new RecoveryModelClient.Reply(INVALID, "")).code());
    }

    @Test
    void imageAndDecisionUseOnlyTheirExplicitPurposeLists() throws Exception {
        try (var db = gold.debug.windowstolinux.app.db.DesktopPersistence.open(directory)) {
            var secrets = new gold.debug.windowstolinux.app.service.server.DesktopSecretStoreService(
                    db.encryptedSecrets());
            char[] master = "model-test-master".toCharArray();
            try (var store = secrets.open(
                    gold.debug.windowstolinux.shared.model.security.CredentialStorageMode.MASTER_PASSWORD, master)) {
                store.save("ai/key", "test-api-key".toCharArray());
            }
            for (String id : List.of("deployment", "vision")) {
                db.aiProfiles().saveVerified(
                        new StoredAiProviderProfile(id, "https://example.test/v1/chat/completions", id, "ai/key",
                                "MASTER_PASSWORD"),
                        id, Instant.now(), id.equals("vision") ? AiCapabilityType.VISION : AiCapabilityType.TEXT);
                db.aiProfiles().purposes().save(id.equals("vision") ? AiPurposeType.VISION : AiPurposeType.DEPLOYMENT,
                        List.of(new AiPurposeAssignment(id, true)));
            }
            var calls = new ArrayList<String>();
            var json = new com.fasterxml.jackson.databind.ObjectMapper();
            var client = new RecoveryModelClient((url, key, body) -> {
                String model = json.readTree(body).path("model").asText();
                boolean image = body.contains("image_url");
                calls.add(model + "/" + image);
                String content = image
                        ? "{\"observation\":\"shell prompt\"}"
                        : "{\"command\":\"uname -a\",\"reason\":\"inspect OS\",\"expected\":\"OS facts\",\"highImpact\":false}";
                return new gold.debug.windowstolinux.shared.ai.transport.RoleChatResult(200, json
                        .writeValueAsString(Map.of("choices", List.of(Map.of("message", Map.of("content", content))))));
            });
            var ai = new RecoveryAiUseCase(db.aiProfiles(), secrets, client);
            var observed = ai.observe(
                    new gold.debug.windowstolinux.shared.model.recovery.TerminalObservation("", new byte[]{1}, 1),
                    master);
            assertEquals("ok", observed.code());
            assertEquals("ok", ai.decide(RecoveryModelClient.parseObservation(observed.content()), master).code());
            assertEquals(List.of("vision/true", "deployment/false"), calls);
            calls.clear();
            assertEquals("text shell",
                    ai.observe(new gold.debug.windowstolinux.shared.model.recovery.TerminalObservation("text shell",
                            new byte[0], 1), master).content());
            assertTrue(calls.isEmpty());
            db.aiProfiles().purposes().save(AiPurposeType.DEPLOYMENT, List.of());
            assertEquals("regularUnavailable", ai.decide("facts", master).code());
            assertTrue(calls.isEmpty());
        }
    }
}
