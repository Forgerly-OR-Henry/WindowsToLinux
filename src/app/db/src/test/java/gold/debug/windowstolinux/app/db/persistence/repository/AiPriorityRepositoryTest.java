package gold.debug.windowstolinux.app.db.persistence.repository;
import gold.debug.windowstolinux.app.db.DesktopPersistence;
import gold.debug.windowstolinux.app.db.entity.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.sql.*;
import java.time.Instant;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
class AiPriorityRepositoryTest {
    @TempDir Path directory;
    private StoredAiProviderProfile profile(String id) { return new StoredAiProviderProfile(id, "https://example.test/v1/chat/completions", "model", "ai/"+id, "MASTER_PASSWORD"); }
    @Test void orderAndEnablementSurviveRestartAndRejectIncompleteTransactions() throws Exception {
        try (var db = DesktopPersistence.open(directory)) {
            var repo = db.aiProfiles(); repo.saveNamed(profile("a")); repo.saveNamed(profile("b")); repo.saveNamed(profile("c"));
            repo.reorder(List.of("c","a","b")); repo.setEnabled("a", false);
            assertThrows(SQLException.class, () -> repo.reorder(List.of("b","b","c")));
            assertThrows(SQLException.class, () -> repo.reorder(List.of("a","c")));
            repo.saveVerified(profile("a"), "Renamed", Instant.parse("2026-09-16T00:00:00Z"));
        }
        try (var db = DesktopPersistence.open(directory)) {
            var values = db.aiProfiles().listConfigured(); assertEquals(List.of("c","a","b"), values.stream().map(v -> v.profile().id()).toList());
            assertFalse(values.get(1).enabled()); assertEquals("Renamed", values.get(1).name()); assertTrue(values.get(1).verifiedAt().isPresent());
            db.aiProfiles().saveNamed(profile("d")); assertEquals("d", db.aiProfiles().listConfigured().getLast().profile().id()); assertTrue(db.aiProfiles().listConfigured().getLast().enabled());
        }
    }
    @Test void migratesLegacyRoleOrderWithoutInventingVerificationOrDroppingSecrets() throws Exception {
        try (var db = DesktopPersistence.open(directory)) {
            var repo = db.aiProfiles(); repo.saveNamed(profile("unbound")); repo.saveNamed(profile("review")); repo.saveNamed(profile("analysis"));
            repo.saveDefault(new StoredAiProfile("https://legacy.test/v1/chat/completions", "legacy", "old/exact/key", "MASTER_PASSWORD"));
            repo.saveRoleAssignment(new StoredAiRoleAssignment("PROJECT_ANALYSIS", "analysis"));
            repo.saveRoleAssignment(new StoredAiRoleAssignment("DEPLOYMENT_RISK_REVIEW", "review"));
        }
        try (var connection = DriverManager.getConnection("jdbc:sqlite:"+directory.resolve("windowstolinux.db")); var sql = connection.createStatement()) {
            sql.execute("DROP TABLE ai_provider_control"); sql.execute("PRAGMA user_version=14");
        }
        try (var db = DesktopPersistence.open(directory)) {
            var values = db.aiProfiles().listConfigured(); assertEquals(List.of("analysis","review","default","unbound"), values.stream().map(v -> v.profile().id()).toList());
            assertEquals(List.of(true,true,false,false), values.stream().map(StoredAiProviderConfiguration::enabled).toList());
            assertTrue(values.stream().allMatch(v -> v.verifiedAt().isEmpty())); assertEquals("old/exact/key", values.get(2).profile().credentialKey());
            assertEquals(2, db.aiProfiles().listRoleAssignments().size());
        }
    }
}
