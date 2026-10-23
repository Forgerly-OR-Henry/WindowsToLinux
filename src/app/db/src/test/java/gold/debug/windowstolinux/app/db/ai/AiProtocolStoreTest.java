package gold.debug.windowstolinux.app.db.ai;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.time.Instant;

import gold.debug.windowstolinux.app.db.connect.PlatformStoreTestFixture;
import gold.debug.windowstolinux.app.ai.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AiProtocolStoreTest {
    @TempDir
    Path directory;

    @Test
    void missingExplicitProtocolIsRejectedWithoutBackfill() throws Exception {
        try (var db = PlatformStoreTestFixture.open(directory)) {
            PlatformStoreTestFixture.credential(db, "ai/a");
            try (var c = db.database().openConnection(); var s = c.createStatement()) {
                s.executeUpdate("INSERT INTO ai_model(model_id,credential_id,endpoint_url,model_name,display_name,display_order) VALUES('a','ai/a','https://example.test/api','model','A',0)");
            }
            assertThrows(java.sql.SQLException.class, () -> db.aiProfiles().listConfigured());
            assertTrue(db.preferences().find("ai_model_protocol_a").isEmpty());
            assertTrue(db.preferences().find("ai_explicit_protocol_migration_v1").isEmpty());
        }
    }

    @Test
    void protocolAndIndependentToolCheckSurviveRestartAndProtocolChangesInvalidateEvidence() throws Exception {
        var old = new StoredAiModelProfile("a", "https://example.test/v1/chat/completions", "model", "ai/a", ModelReasoningLevel.DEFAULT, ModelProtocol.OPENAI_CHAT_COMPLETIONS);
        var updated = new StoredAiModelProfile(old.id(), old.endpoint(), old.model(), old.credentialKey(), old.reasoningEffort(), ModelProtocol.ANTHROPIC_MESSAGES);
        try (var db = PlatformStoreTestFixture.open(directory)) {
            PlatformStoreTestFixture.credential(db, "ai/a");
            var store = db.aiProfiles();
            store.saveVerified(old, "A", Instant.now());
            store.saveOutputLimit("a", 128);
            for (var capability : java.util.List.of(AiCapabilityType.VISION, AiCapabilityType.TOOL_CALLING, AiCapabilityType.VOICE_FILE))
                store.recordVerification(old, 1, capability, Instant.now());
            store.saveConfiguration(updated, "A");
            var saved = store.listConfigured().getFirst();
            assertEquals(2, saved.revision());
            for (var capability : java.util.List.of(AiCapabilityType.TEXT, AiCapabilityType.VISION, AiCapabilityType.TOOL_CALLING))
                assertFalse(saved.verified(capability));
            assertFalse(saved.verified(AiCapabilityType.VOICE_FILE));
            assertThrows(java.sql.SQLException.class, () -> store.recordVerification(old, 1, AiCapabilityType.TOOL_CALLING, Instant.now()));
            store.recordVerification(updated, 2, AiCapabilityType.TOOL_CALLING, Instant.now());
        }
        try (var db = PlatformStoreTestFixture.open(directory)) {
            var saved = db.aiProfiles().listConfigured().getFirst();
            assertEquals(updated, saved.profile());
            assertTrue(saved.verified(AiCapabilityType.TOOL_CALLING));
            assertFalse(saved.verified(AiCapabilityType.TEXT));
            db.aiProfiles().saveOutputLimit("a", 256);
            assertTrue(db.aiProfiles().listConfigured().getFirst().verified(AiCapabilityType.TOOL_CALLING));
            assertEquals(saved.revision(), db.aiProfiles().listConfigured().getFirst().revision());
        }
    }
}
