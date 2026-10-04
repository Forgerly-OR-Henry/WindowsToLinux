package gold.debug.windowstolinux.app.db.ai;

import gold.debug.windowstolinux.app.db.connect.PlatformStoreTestFixture;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.sql.*;
import java.time.Instant;
import java.util.*;

import gold.debug.windowstolinux.app.db.ai.StoredAiModelProfile;
import gold.debug.windowstolinux.app.ai.model.AiCapabilityType;
import gold.debug.windowstolinux.app.ai.model.AiPurposeAssignment;
import gold.debug.windowstolinux.app.ai.model.AiPurposeType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AiPriorityStoreTest {
    @TempDir
    Path directory;

    private StoredAiModelProfile profile(String id) {
        return new StoredAiModelProfile(id, "https://example.test/v1/chat/completions", "model", "ai/" + id);
    }

    @Test
    void displayAndPurposeOrderRemainIndependentAcrossRestart() throws Exception {
        try (var db = PlatformStoreTestFixture.open(directory)) {
            for (String id : List.of("a", "b", "c", "d"))
                PlatformStoreTestFixture.credential(db, "ai/" + id);
            PlatformStoreTestFixture.credential(db, "new/exact/key");
            var repo = db.aiProfiles();
            for (var id : List.of("a", "b", "c"))
                repo.saveVerified(profile(id), id, Instant.now());
            repo.purposes().save(AiPurposeType.DEPLOYMENT,
                List.of(new AiPurposeAssignment("a", false), new AiPurposeAssignment("c", true)));
            repo.purposes().save(AiPurposeType.APPROVAL,
                List.of(new AiPurposeAssignment("c", true), new AiPurposeAssignment("a", true)));
            repo.reorder(List.of("c", "a", "b"));
            assertThrows(SQLException.class, () -> repo.reorder(List.of("b", "b", "c")));
            assertThrows(SQLException.class, () -> repo.reorder(List.of("a", "c")));
            repo.saveVerified(profile("a"), "Renamed", Instant.now());
        }
        try (var db = PlatformStoreTestFixture.open(directory)) {
            for (String id : List.of("a", "b", "c", "d"))
                PlatformStoreTestFixture.credential(db, "ai/" + id);
            PlatformStoreTestFixture.credential(db, "new/exact/key");
            var repo = db.aiProfiles();
            assertEquals(List.of("c", "a", "b"), repo.listConfigured().stream().map(v -> v.profile().id()).toList());
            assertEquals(List.of("a", "c"), repo.purposes().list(AiPurposeType.DEPLOYMENT).stream()
                .map(AiPurposeAssignment::profileId).toList());
            assertEquals(List.of("c"),
                repo.configuredFor(AiPurposeType.DEPLOYMENT).stream().map(v -> v.profile().id()).toList());
            assertEquals(List.of("c", "a"),
                repo.configuredFor(AiPurposeType.APPROVAL).stream().map(v -> v.profile().id()).toList());
            repo.saveNamed(profile("d"));
            assertEquals("d", repo.listConfigured().getLast().profile().id());
            assertEquals(2, repo.purposes().list(AiPurposeType.DEPLOYMENT).size());
        }
    }

    @Test
    void capabilityChangesInvalidateOldEvidenceAndFailedMembershipSaveIsAtomic() throws Exception {
        try (var db = PlatformStoreTestFixture.open(directory)) {
            for (String id : List.of("a", "b", "c", "d"))
                PlatformStoreTestFixture.credential(db, "ai/" + id);
            PlatformStoreTestFixture.credential(db, "new/exact/key");
            var repo = db.aiProfiles();
            repo.saveVerified(profile("a"), "A", Instant.now());
            repo.recordVerification(profile("a"), 1, AiCapabilityType.VISION, Instant.now());
            var members = List.of(new AiPurposeAssignment("a", true));
            for (var type : List.of(AiPurposeType.DEPLOYMENT, AiPurposeType.APPROVAL, AiPurposeType.VISION))
                repo.purposes().save(type, members);
            assertThrows(SQLException.class, () -> repo.purposes().save(AiPurposeType.VOICE, members));
            assertThrows(IllegalArgumentException.class, () -> repo.purposes().save(AiPurposeType.APPROVAL,
                List.of(members.getFirst(), members.getFirst())));
            assertThrows(SQLException.class, () -> repo.purposes().save(AiPurposeType.APPROVAL,
                List.of(new AiPurposeAssignment("missing", true))));
            assertEquals(members, repo.purposes().list(AiPurposeType.APPROVAL));
            var changed = new StoredAiModelProfile("a", profile("a").endpoint(), "changed", "new/exact/key");
            repo.saveVerified(changed, "A", Instant.now(), AiCapabilityType.TEXT);
            assertTrue(repo.configuredFor(AiPurposeType.VISION).isEmpty());
            assertEquals(1, repo.configuredFor(AiPurposeType.APPROVAL).size());
            assertThrows(SQLException.class,
                () -> repo.recordVerification(profile("a"), 1, AiCapabilityType.VISION, Instant.now()));
            assertTrue(repo.listConfigured().getFirst().visionVerifiedAt().isEmpty());
            repo.purposes().save(AiPurposeType.APPROVAL, List.of());
            assertEquals(1, repo.listConfigured().size());
            assertEquals(1, repo.purposes().list(AiPurposeType.DEPLOYMENT).size());
        }
    }

    @Test
    void fourOrdersCanChangeIndependentlyAndDisabledVisionRetainsItsPlace() throws Exception {
        try (var db = PlatformStoreTestFixture.open(directory)) {
            var repo = db.aiProfiles();
            for (String id : List.of("a", "b", "c")) {
                PlatformStoreTestFixture.credential(db, "ai/" + id);
                repo.saveVerified(profile(id), id, Instant.now());
                repo.recordVerification(profile(id), 1, AiCapabilityType.VISION, Instant.now());
            }
            repo.purposes().save(AiPurposeType.DEPLOYMENT,
                List.of(new AiPurposeAssignment("a", true), new AiPurposeAssignment("b", true)));
            repo.purposes().save(AiPurposeType.APPROVAL,
                List.of(new AiPurposeAssignment("b", true), new AiPurposeAssignment("a", true)));
            repo.purposes().save(AiPurposeType.VISION, List.of(new AiPurposeAssignment("c", true),
                new AiPurposeAssignment("b", true), new AiPurposeAssignment("a", false)));
            repo.reorder(List.of("b", "a", "c"));
            repo.purposes().save(AiPurposeType.VISION, List.of(new AiPurposeAssignment("b", true),
                new AiPurposeAssignment("c", true), new AiPurposeAssignment("a", false)));
            assertEquals(List.of("b", "a", "c"), repo.listConfigured().stream().map(v -> v.profile().id()).toList());
            assertEquals(List.of("a", "b"), repo.purposes().list(AiPurposeType.DEPLOYMENT).stream()
                .map(AiPurposeAssignment::profileId).toList());
            assertEquals(List.of("b", "a"),
                repo.purposes().list(AiPurposeType.APPROVAL).stream().map(AiPurposeAssignment::profileId).toList());
            assertEquals("a", repo.purposes().list(AiPurposeType.VISION).getLast().profileId());
            assertEquals(List.of("b", "c"),
                repo.configuredFor(AiPurposeType.VISION).stream().map(v -> v.profile().id()).toList());
            repo.purposes().save(AiPurposeType.VISION, List.of(new AiPurposeAssignment("a", false)));
            assertEquals(1, repo.purposes().list(AiPurposeType.VISION).size());
            assertEquals(2, repo.purposes().list(AiPurposeType.DEPLOYMENT).size());
        }
    }
}
