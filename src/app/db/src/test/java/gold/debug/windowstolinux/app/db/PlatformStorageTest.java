package gold.debug.windowstolinux.app.db;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;

import gold.debug.windowstolinux.app.db.ai.StoredAiModelProfile;
import gold.debug.windowstolinux.app.db.security.StoredSecret;
import gold.debug.windowstolinux.app.db.security.StoredSecurityProfile;
import gold.debug.windowstolinux.app.db.server.StoredServerProfile;
import gold.debug.windowstolinux.app.db.connect.PlatformDatabase;
import gold.debug.windowstolinux.app.ai.model.AiCapabilityType;
import gold.debug.windowstolinux.app.ai.model.AiPurposeAssignment;
import gold.debug.windowstolinux.app.ai.model.AiPurposeType;
import gold.debug.windowstolinux.app.db.server.StoredServerIdentity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlatformStorageTest {
    @TempDir
    Path directory;

    PlatformStorage initialized() throws Exception {
        var persistence = new PlatformStorage(
            PlatformDatabase.open(directory, PlatformDatabaseTest.TEST_PROTECTION));
        persistence.security().initialize(new StoredSecurityProfile(true, "fixture", new byte[48], "AES256_GCM_V1",
            new byte[12], "ARGON2ID_V1", new byte[16], "fixture", 1));
        try (var credential = new StoredSecret("credential", "PLAIN_V1", new byte[]{1}, null)) {
            persistence.security().save(credential, true);
        }
        return persistence;
    }

    @Test
    void platformConfigurationUpdatesAndReopenNeedNoBusinessTables() throws Exception {
        try (var p = initialized()) {
            p.preferences().saveAll(Map.of("ui_locale", "zh-CN", "ui_theme", "light"));
            p.preferences().save("ui_theme", "dark");
            var server = new StoredServerProfile("server", "host.example", 22, "alice", "credential", "Server");
            p.servers().saveServerProfile(server);
            p.servers().recordObservation(server, true, "Linux");
            p.servers().saveServer(new StoredServerIdentity("server", "host.example", 22, "SHA256:fixture"));
            assertTrue(p.servers().observation("server").checkedAt().isPresent());
            p.servers().saveServerProfile(
                new StoredServerProfile("server", "new.example", 2222, "alice", "credential", "Renamed"));
            assertTrue(p.servers().findServer("server").isEmpty());
            assertTrue(p.servers().observation("server").checkedAt().isEmpty());
            p.recovery().save("rescue", "server", "operation", "CONNECTING", "", "started");
            p.recovery().save("rescue", "server", "operation", "WAITING", "", "waiting");
            assertThrows(SQLException.class,
                () -> p.recovery().save("rescue", "other", "operation", "WAITING", "", "wrong-owner"));
            p.recovery().interruptUnfinished();
            try (var c = p.database().openConnection();
                 var s = c.createStatement();
                 var r = s.executeQuery(
                     "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='app' AND table_type='BASE TABLE'")) {
                assertTrue(r.next());
                assertEquals(23, r.getInt(1));
            }
        }
        try (var p = new PlatformStorage(PlatformDatabase.open(directory, PlatformDatabaseTest.TEST_PROTECTION))) {
            assertEquals("dark", p.preferences().find("ui_theme").orElseThrow());
            assertEquals("new.example", p.servers().findServerProfile("server").orElseThrow().host());
            try (var c = p.database().openConnection();
                 var s = c.createStatement();
                 var r = s.executeQuery(
                     "SELECT task_state,(SELECT COUNT(*) FROM browser_recovery_event) FROM browser_recovery_task")) {
                assertTrue(r.next());
                assertEquals("INTERRUPTED", r.getString(1));
                assertEquals(2, r.getInt(2));
            }
        }
    }

    @Test
    void modelVerificationAndIndependentPurposeOrderingRemainRevisionBound() throws Exception {
        try (var p = initialized()) {
            var first = new StoredAiModelProfile("first", "https://api.example/v1", "model", "credential");
            var second = new StoredAiModelProfile("second", "https://api.example/v1", "model", "credential");
            p.aiProfiles().saveNamed(first);
            p.aiProfiles().saveVerified(second, "Second", Instant.now());
            assertThrows(SQLException.class, () -> p.aiProfiles().purposes().save(AiPurposeType.DEPLOYMENT,
                List.of(new AiPurposeAssignment("first", true))));
            p.aiProfiles().saveVerified(first, "First", Instant.now());
            p.aiProfiles().purposes().save(AiPurposeType.DEPLOYMENT,
                List.of(new AiPurposeAssignment("first", true), new AiPurposeAssignment("second", true)));
            p.aiProfiles().purposes().save(AiPurposeType.APPROVAL, List.of(new AiPurposeAssignment("second", true)));
            p.aiProfiles().reorder(List.of("second", "first"));
            assertEquals(List.of("first", "second"), p.aiProfiles().configuredFor(AiPurposeType.DEPLOYMENT).stream()
                .map(v -> v.profile().id()).toList());
            var changed = new StoredAiModelProfile("first", "https://api.example/v1", "different", "credential");
            p.aiProfiles().saveNamed(changed);
            assertEquals(List.of("second"), p.aiProfiles().configuredFor(AiPurposeType.DEPLOYMENT).stream()
                .map(v -> v.profile().id()).toList());
            assertThrows(SQLException.class,
                () -> p.aiProfiles().recordVerification(first, 1, AiCapabilityType.TEXT, Instant.now()));
            assertEquals(List.of("second"),
                p.aiProfiles().configuredFor(AiPurposeType.APPROVAL).stream().map(v -> v.profile().id()).toList());
        }
    }

    @Test
    void relatedCredentialAndServerChangesRollBackTogether() throws Exception {
        try (var p = initialized()) {
            assertThrows(SQLException.class, () -> p.transaction(() -> {
                try (var credential = new StoredSecret("temporary", "PLAIN_V1", new byte[]{2}, null)) {
                    p.security().save(credential, true);
                }
                p.servers().saveServerProfile(
                    new StoredServerProfile("temporary", "host.example", 22, "user", "temporary", "temporary"));
                throw new SQLException("injected failure after both writes");
            }));
            assertTrue(p.security().find("temporary").isEmpty());
            assertTrue(p.servers().findServerProfile("temporary").isEmpty());
            assertEquals(0, p.database().openConnections());
        }
    }

    @Test
    void disabledModReservationsProtectServerIdentityAndDeletion() throws Exception {
        try (var p = initialized()) {
            var server = new StoredServerProfile("server", "host.example", 22, "user", "credential", "Server");
            p.servers().saveServerProfile(server);
            try (var c = p.database().openConnection(); var s = c.createStatement()) {
                var generation = UUID.randomUUID();
                s.execute("INSERT INTO mod_registry(mod_id,updated_at) VALUES('fixture.mod',0)");
                try (var q = c.prepareStatement(
                    "INSERT INTO mod_schema(mod_id,schema_version,generation,physical_name,database_user,state,created_at,validated_at) VALUES('fixture.mod','1',?,'fixture_schema','fixture_user','READY',0,0)")) {
                    q.setObject(1, generation);
                    q.executeUpdate();
                }
                try (var q = c.prepareStatement(
                    "INSERT INTO mod_server_reference VALUES(?,'server','fixture.mod','1',?,'record','RESERVED',0)")) {
                    q.setObject(1, UUID.randomUUID());
                    q.setObject(2, generation);
                    q.executeUpdate();
                }
                for (var state : List.of("RESERVED", "ACTIVE", "UNKNOWN")) {
                    s.executeUpdate("UPDATE mod_server_reference SET state='" + state + "'");
                    assertThrows(SQLException.class, () -> p.servers().saveServerProfile(
                        new StoredServerProfile("server", "changed.example", 22, "user", "credential", "Server")));
                    assertThrows(SQLException.class,
                        () -> s.executeUpdate("DELETE FROM server_profile WHERE server_profile_id='server'"));
                }
                p.servers().saveServerProfile(
                    new StoredServerProfile("server", "host.example", 22, "user", "credential", "Renamed"));
                assertEquals("Renamed", p.servers().findServerProfile("server").orElseThrow().displayName());
            }
        }
    }
}
