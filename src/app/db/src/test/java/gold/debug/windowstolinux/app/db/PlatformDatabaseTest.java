package gold.debug.windowstolinux.app.db;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.sql.*;
import java.util.*;

import gold.debug.windowstolinux.app.db.bootstrap.DatabaseProtection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlatformDatabaseTest {
    @TempDir
    Path root;

    static final DatabaseProtection TEST_PROTECTION = new DatabaseProtection() {
        public byte[] protect(byte[] clear) {
            var encrypted = clear.clone();
            for (int i = 0; i < encrypted.length; i++)
                encrypted[i] ^= 0x35;
            return encrypted;
        }

        public byte[] unprotect(byte[] encrypted) {
            return protect(encrypted);
        }
    };
    long count(PlatformDatabase database, String sql) throws Exception {
        try (var c = database.openConnection(); var s = c.createStatement(); var r = s.executeQuery(sql)) {
            assertTrue(r.next());
            return r.getLong(1);
        }
    }

    @Test
    void initializesOnlyPlatformTablesAndReusesTheSameProtectedGeneration() throws Exception {
        var sqlite = root.resolve("windowstolinux.db");
        Files.writeString(sqlite, "unmodified legacy data");
        UUID generation, firstBoot;
        try (var database = PlatformDatabase.open(root, TEST_PROTECTION)) {
            generation = database.generation();
            firstBoot = database.bootId();
            database.started();
            assertEquals(20, count(database,
                    "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='app' AND table_type='BASE TABLE'"));
            try (var c = database.openConnection(); var s = c.createStatement()) {
                s.execute("INSERT INTO desktop_preference VALUES('test','retained')");
            }
            assertEquals(0, database.openConnections());
        }
        try (var database = PlatformDatabase.open(root, TEST_PROTECTION)) {
            assertEquals(generation, database.generation());
            assertNotEquals(firstBoot, database.bootId());
            assertEquals(1, count(database,
                    "SELECT COUNT(*) FROM desktop_preference WHERE preference_key='test' AND preference_value='retained'"));
        }
        assertEquals("unmodified legacy data", Files.readString(sqlite));
    }

    @Test
    void missingOrMixedBootstrapPairFailsWithoutReplacingEitherFile() throws Exception {
        try (var database = PlatformDatabase.open(root, TEST_PROTECTION)) {
        }
        var database = root.resolve("platform.mv.db");
        var before = Files.readAllBytes(database);
        Files.delete(root.resolve("db-access.bin"));
        assertThrows(Exception.class, () -> PlatformDatabase.open(root, TEST_PROTECTION));
        assertArrayEquals(before, Files.readAllBytes(database));
        assertFalse(Files.exists(root.resolve("db-access.bin")));
        var other = root.resolve("other");
        Files.createDirectory(other);
        Files.writeString(other.resolve("db-access.bin"), "orphan");
        assertThrows(Exception.class, () -> PlatformDatabase.open(other, TEST_PROTECTION));
        assertFalse(Files.exists(other.resolve("platform.mv.db")));
    }

    @Test
    void simultaneousInstanceAndLiveConnectionsBlockShutdown() throws Exception {
        try (var database = PlatformDatabase.open(root, TEST_PROTECTION)) {
            assertThrows(Exception.class, () -> PlatformDatabase.open(root, TEST_PROTECTION));
            var connection = database.openConnection();
            assertEquals(1, database.openConnections());
            assertThrows(SQLException.class, database::close);
            assertFalse(connection.isClosed());
            connection.close();
            assertEquals(0, database.openConnections());
            database.close();
            database.close();
        }
    }

    @Test
    void corruptedProtectionAndWrongDatabaseIdentityAreNotReinitialized() throws Exception {
        try (var database = PlatformDatabase.open(root, TEST_PROTECTION)) {
        }
        var access = root.resolve("db-access.bin");
        var bytes = Files.readAllBytes(access);
        bytes[0] ^= 1;
        Files.write(access, bytes);
        assertThrows(Exception.class, () -> PlatformDatabase.open(root, TEST_PROTECTION));
        assertArrayEquals(bytes, Files.readAllBytes(access));
        bytes[0] ^= 1;
        Files.write(access, bytes);
        try (var database = PlatformDatabase.open(root, TEST_PROTECTION);
                var c = database.openConnection();
                var p = c.prepareStatement("UPDATE app_state SET database_generation=? WHERE singleton_id=1")) {
            p.setObject(1, UUID.randomUUID());
            p.executeUpdate();
        }
        assertThrows(SQLException.class, () -> PlatformDatabase.open(root, TEST_PROTECTION));
    }

    @Test
    void bootstrapFailureBeforePersistenceLeavesNoDatabase() throws Exception {
        var protection = new DatabaseProtection() {
            public byte[] protect(byte[] value) throws Exception {
                throw new Exception("injected protector failure");
            }

            public byte[] unprotect(byte[] value) {
                throw new AssertionError();
            }
        };
        assertThrows(Exception.class, () -> PlatformDatabase.open(root, protection));
        assertFalse(Files.exists(root.resolve("db-access.bin")));
        assertFalse(Files.exists(root.resolve("platform.mv.db")));
    }

    @Test
    void priorUnfinishedTasksRemainUnknownAfterRestart() throws Exception {
        try (var database = PlatformDatabase.open(root, TEST_PROTECTION);
                var c = database.openConnection();
                var p = c.prepareStatement("INSERT INTO platform_task VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
            p.setObject(1, UUID.randomUUID());
            p.setString(2, "example.mod");
            p.setString(3, "1.0.0");
            p.setString(4, "test");
            p.setString(5, "RUNNING");
            p.setNull(6, Types.VARCHAR);
            p.setBoolean(7, true);
            p.setObject(8, database.bootId());
            p.setString(9, "{\"formatVersion\":1}");
            p.setNull(10, Types.CLOB);
            p.setString(11, "{\"formatVersion\":1,\"operationId\":\"probe\"}");
            p.setString(12, UUID.randomUUID().toString());
            p.setString(13, "running");
            p.setLong(14, System.currentTimeMillis());
            p.setNull(15, Types.BIGINT);
            assertEquals(1, p.executeUpdate());
        }
        try (var database = PlatformDatabase.open(root, TEST_PROTECTION);
                var c = database.openConnection();
                var s = c.createStatement();
                var r = s.executeQuery("SELECT state,outcome,ended_at,recovery_json,boot_id FROM platform_task")) {
            assertTrue(r.next());
            assertEquals("UNKNOWN", r.getString(1));
            assertNull(r.getString(2));
            assertNull(r.getObject(3));
            assertEquals("{\"formatVersion\":1,\"operationId\":\"probe\"}", r.getString(4));
            assertNotEquals(database.bootId(), r.getObject(5, UUID.class));
            assertFalse(r.next());
        }
    }
}
