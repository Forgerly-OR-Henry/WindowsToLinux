package gold.debug.windowstolinux.app.db.ai;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;

import gold.debug.windowstolinux.app.ai.model.*;
import gold.debug.windowstolinux.app.db.connect.PlatformStoreTestFixture;
import gold.debug.windowstolinux.appapi.ai.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

class AiReasoningStoreTest {
    @TempDir
    Path directory;

    @Test
    void legacyMigrationIsIdempotentAndPurposeDefaultsSurviveRestart() throws Exception {
        var legacy = new StoredAiModelProfile("sample", "https://example.test/chat", "fixture", "ai/a", ModelReasoningLevel.LOW);
        var medium = Map.of(AiPurpose.LANGUAGE, AiReasoningMode.MEDIUM, AiPurpose.APPROVAL, AiReasoningMode.MEDIUM, AiPurpose.VISION, AiReasoningMode.MEDIUM);
        try (var db = PlatformStoreTestFixture.open(directory)) {
            PlatformStoreTestFixture.credential(db, "ai/a");
            db.aiProfiles().saveVerified(legacy, "A", Instant.now());
            try (var connection = db.database().openConnection(); var remove = connection.prepareStatement("DELETE FROM desktop_preference WHERE preference_key LIKE 'ai_model_reasoning_%' AND preference_key NOT LIKE 'ai_model_reasoning_effort_%'")) {
                remove.executeUpdate();
            }
            db.aiProfiles().reasoning().initialize("sample", medium);
            var model = db.aiProfiles().listConfigured().getFirst();
            assertEquals(AiReasoningMode.LOW, model.profile().reasoningDefaults().get(AiPurpose.LANGUAGE));
            assertTrue(model.verified(AiCapabilityType.TEXT), "Migrating the same legacy value preserves its evidence");
            db.aiProfiles().reasoning().save("sample", model.revision(), Map.of(AiPurpose.LANGUAGE, AiReasoningMode.LOW,
                AiPurpose.APPROVAL, AiReasoningMode.HIGH, AiPurpose.VISION, AiReasoningMode.DEFAULT));
            db.aiProfiles().reasoning().initialize("sample", medium);
            assertThrows(java.sql.SQLException.class, () -> db.aiProfiles().reasoning().save("sample", model.revision(), medium));
            assertFalse(db.aiProfiles().listConfigured().getFirst().verified(AiCapabilityType.TEXT));
        }
        try (var db = PlatformStoreTestFixture.open(directory)) {
            db.aiProfiles().reasoning().initialize("sample", medium);
            var profile = db.aiProfiles().listConfigured().getFirst().profile();
            assertEquals(AiReasoningMode.LOW, profile.reasoningDefaults().get(AiPurpose.LANGUAGE));
            assertEquals(AiReasoningMode.HIGH, profile.reasoningDefaults().get(AiPurpose.APPROVAL));
            assertEquals(AiReasoningMode.DEFAULT, profile.reasoningDefaults().get(AiPurpose.VISION));
        }
    }

    @Test
    void missingDefaultsUseConfigOnceAndInvalidateChangedEvidence() throws Exception {
        try (var db = PlatformStoreTestFixture.open(directory)) {
            PlatformStoreTestFixture.credential(db, "ai/a");
            db.aiProfiles().saveVerified(new StoredAiModelProfile("fresh", "https://example.test/chat", "fixture", "ai/a"), "Fresh", Instant.now());
            var before = db.aiProfiles().listConfigured().getFirst();
            try (var connection = db.database().openConnection(); var remove = connection.prepareStatement("DELETE FROM desktop_preference WHERE preference_key LIKE 'ai_model_reasoning_%'")) {
                remove.executeUpdate();
            }
            db.aiProfiles().reasoning().initialize("fresh", Map.of(AiPurpose.LANGUAGE, AiReasoningMode.MEDIUM,
                AiPurpose.APPROVAL, AiReasoningMode.MEDIUM, AiPurpose.VISION, AiReasoningMode.MEDIUM));
            var initialized = db.aiProfiles().listConfigured().getFirst();
            assertTrue(initialized.revision() > before.revision());
            assertFalse(initialized.verified(AiCapabilityType.TEXT));
            db.aiProfiles().reasoning().initialize("fresh", Map.of(AiPurpose.LANGUAGE, AiReasoningMode.LOW,
                AiPurpose.APPROVAL, AiReasoningMode.LOW, AiPurpose.VISION, AiReasoningMode.LOW));
            assertEquals(initialized, db.aiProfiles().listConfigured().getFirst(), "Later Config changes do not overwrite database choices");
        }
    }
}
