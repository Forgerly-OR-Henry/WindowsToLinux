package gold.debug.windowstolinux.app.db.persistence.repository;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.sql.*;
import java.time.Instant;
import java.util.*;

import gold.debug.windowstolinux.app.db.DesktopPersistence;
import gold.debug.windowstolinux.app.db.entity.*;
import gold.debug.windowstolinux.shared.model.ai.AiModelGroupType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BrowserRecoveryRepositoryTest {
    @TempDir
    Path directory;
    private StoredAiProviderProfile profile(String id) {
        return new StoredAiProviderProfile(id, "https://example.test/v1/chat/completions", id, "immutable/key/" + id);
    }

    private Connection open() throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("windowstolinux.db"));
    }

    @Test
    void restartInterruptsWithoutReplayingAndJournalHasOnlyDigests() throws Exception {
        try (var db = DesktopPersistence.open(directory)) {
            gold.debug.windowstolinux.app.db.DatabaseTestFixture.server(db);
            db.recovery().save("session", "server", "operation", "EXECUTING", "a".repeat(64), "submitted");
            assertThrows(IllegalArgumentException.class,
                    () -> db.recovery().save("s", "server", "op", "PAUSED", "password", "paused"));
        }
        try (var db = DesktopPersistence.open(directory); var c = open(); var s = c.createStatement()) {
            try (var row = s.executeQuery("SELECT * FROM browser_recovery_task")) {
                assertTrue(row.next());
                assertEquals("INTERRUPTED", row.getString("task_state"));
                assertEquals("app-restarted", row.getString("last_event_code"));
                assertEquals("a".repeat(64), row.getString("last_action_digest"));
            }
            try (var row = s.executeQuery("SELECT count(*) FROM browser_recovery_event")) {
                assertTrue(row.next());
                assertEquals(1, row.getInt(1));
            }
        }
    }
}
