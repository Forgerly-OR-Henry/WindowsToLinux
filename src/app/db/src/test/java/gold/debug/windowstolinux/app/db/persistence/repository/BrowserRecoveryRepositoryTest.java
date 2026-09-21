package gold.debug.windowstolinux.app.db.persistence.repository;

import gold.debug.windowstolinux.app.db.DesktopPersistence;
import gold.debug.windowstolinux.app.db.entity.*;
import gold.debug.windowstolinux.shared.model.ai.AiModelGroupType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class BrowserRecoveryRepositoryTest {
    @TempDir Path directory;
    private StoredAiProviderProfile profile(String id) {
        return new StoredAiProviderProfile(id, "https://example.test/v1/chat/completions", id, "immutable/key/" + id, "MASTER_PASSWORD");
    }
    private Connection open() throws SQLException { return DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("windowstolinux.db")); }
    @Test void migratesVersion16WithoutChangingCredentialsBindingsOrderOrEnablement() throws Exception {
        try (var db = DesktopPersistence.open(directory)) {
            db.aiProfiles().saveNamed(profile("one")); db.aiProfiles().saveNamed(profile("two"));
            db.aiProfiles().saveRoleAssignment(new StoredAiRoleAssignment("PROJECT_ANALYSIS", "one"));
        }
        try (var c = open(); var s = c.createStatement()) {
            s.execute("DROP TABLE deployment_agent_event");s.execute("DROP TABLE deployment_agent_task");
            s.execute("DROP TABLE ai_model_purpose"); s.execute("DROP TABLE ai_model_verification"); s.execute("DROP TABLE ai_model_inventory");
            s.execute("CREATE TABLE ai_provider_control(profile_id TEXT PRIMARY KEY,display_name TEXT,enabled INTEGER,priority INTEGER,verified_at TEXT)");
            s.execute("INSERT INTO ai_provider_control VALUES('two','Two',1,0,'2026-09-20T00:00:00Z'),('one','One',0,1,NULL)");
            s.execute("DROP TABLE browser_recovery_event"); s.execute("DROP TABLE browser_recovery"); s.execute("PRAGMA user_version=16");
        }
        try (var db = DesktopPersistence.open(directory)) {
            var records=db.aiProfiles().listConfigured();
            assertEquals(List.of("two","one"),records.stream().map(v->v.profile().id()).toList());
            assertFalse(db.aiProfiles().purposes().list(gold.debug.windowstolinux.shared.model.ai.AiPurposeType.DEPLOYMENT).get(1).enabled());
            assertEquals("immutable/key/one",records.get(1).profile().credentialKey());
            assertEquals("one",db.aiProfiles().listRoleAssignments().getFirst().profileId());
        }
    }
    @Test void restartInterruptsWithoutReplayingAndJournalHasOnlyDigests() throws Exception {
        try (var db = DesktopPersistence.open(directory)) {
            db.recovery().save("session", "server", "operation", "EXECUTING", "a".repeat(64), "submitted");
            assertThrows(IllegalArgumentException.class, () -> db.recovery().save("s", "server", "op", "PAUSED", "password", "paused"));
        }
        try (var db = DesktopPersistence.open(directory); var c = open(); var s = c.createStatement()) {
            try (var row = s.executeQuery("SELECT * FROM browser_recovery")) {
                assertTrue(row.next()); assertEquals("INTERRUPTED", row.getString("state"));
                assertEquals("app-restarted", row.getString("event_code")); assertEquals("a".repeat(64), row.getString("action_digest"));
            }
            try (var row = s.executeQuery("SELECT count(*) FROM browser_recovery_event")) { assertTrue(row.next()); assertEquals(1, row.getInt(1)); }
        }
    }
}
