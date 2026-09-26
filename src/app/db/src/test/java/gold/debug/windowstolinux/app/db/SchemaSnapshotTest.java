package gold.debug.windowstolinux.app.db;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import gold.debug.windowstolinux.app.db.schema.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SchemaSnapshotTest {
    @TempDir
    Path root;

    @Test
    void stalePhysicalOwnerCannotReceivePreparationAccessOrSourceProof() throws Exception {
        try (var db = PlatformDatabase.open(root, PlatformDatabaseTest.TEST_PROTECTION)) {
            var identity = new SchemaAccessScopeTest().prepare(db, "snapshot.owner", "1");
            var coordinates = new SchemaCoordinates(identity);
            try (var c = db.openConnection(); var s = c.createStatement()) {
                s.execute("ALTER USER \"" + coordinates.databaseUser() + "\" ADMIN TRUE");
            }
            assertThrows(java.sql.SQLException.class, () -> SchemaSnapshot.digest(db, coordinates));
            assertThrows(java.sql.SQLException.class,
                    () -> new SchemaAccessScope(db, identity, UUID.randomUUID(), true));
            assertEquals(0, db.openConnections());
        }
    }

    @Test
    void nativeSnapshotIncludesLobsNullArraysTypesAndSequenceButNotTemporaryPrivileges() throws Exception {
        try (var db = PlatformDatabase.open(root, PlatformDatabaseTest.TEST_PROTECTION)) {
            var helper = new SchemaAccessScopeTest();
            var identity = helper.prepare(db, "snapshot.original", "1");
            var coordinates = new SchemaCoordinates(identity);
            try (var access = new SchemaAccessScope(db, identity, UUID.randomUUID(), false);
                    var c = access.openConnection();
                    var s = c.createStatement()) {
                s.execute(
                        "CREATE TABLE \"精确 数据\"(id INT PRIMARY KEY,c CLOB,b BLOB,a INT ARRAY,u UUID,j JSON,t TIMESTAMP WITH TIME ZONE,n DECIMAL(30,10),z VARCHAR)");
                try (var p = c.prepareStatement(
                        "INSERT INTO \"精确 数据\" VALUES(?,?,?,ARRAY[1,NULL,3],UUID '00000000-0000-0000-0000-000000000001',JSON '{\"key\":2}',TIMESTAMP WITH TIME ZONE '2026-09-27 00:00:00+08',123456789.1234567890,NULL)")) {
                    p.setInt(1, 1);
                    p.setString(2, "秘密不落盘-\ud83d\ude80".repeat(1200));
                    p.setBytes(3, new byte[19000]);
                    p.executeUpdate();
                }
                s.execute("CREATE SEQUENCE next_id START WITH 8");
            }
            String original = SchemaSnapshot.digest(db, coordinates);
            assertTrue(original.matches("[0-9a-f]{64}"));
            try (var reader = new SchemaAccessScope(db, identity, UUID.randomUUID(), true)) {
                assertEquals(original, SchemaSnapshot.digest(db, coordinates));
                helper.prepare(db, "snapshot.unrelated", "7");
                assertEquals(original, SchemaSnapshot.digest(db, coordinates));
            }
            assertEquals(original, SchemaSnapshot.digest(db, coordinates));
            try (var access = new SchemaAccessScope(db, identity, UUID.randomUUID(), false);
                    var c = access.openConnection();
                    var s = c.createStatement()) {
                s.execute("UPDATE \"精确 数据\" SET b=X'010203'");
                assertNotEquals(original, SchemaSnapshot.digest(db, coordinates));
                try (var p = c.prepareStatement("UPDATE \"精确 数据\" SET b=?")) {
                    p.setBytes(1, new byte[19000]);
                    p.executeUpdate();
                }
                assertEquals(original, SchemaSnapshot.digest(db, coordinates));
                s.execute("UPDATE \"精确 数据\" SET z=''");
                assertNotEquals(original, SchemaSnapshot.digest(db, coordinates));
                s.execute("UPDATE \"精确 数据\" SET z=NULL");
                assertEquals(original, SchemaSnapshot.digest(db, coordinates));
                try (var r = s.executeQuery("SELECT NEXT VALUE FOR next_id")) {
                    assertTrue(r.next());
                }
                assertNotEquals(original, SchemaSnapshot.digest(db, coordinates));
            }
            assertEquals(0, db.openConnections());
        }
    }

    @Test
    void engineOwnedTemporaryResultsAreReleasedWithoutProducingAnExportFile() throws Exception {
        Path temporary = Path.of(System.getProperty("java.io.tmpdir"));
        Set<Path> before;
        try (var files = Files.list(temporary)) {
            before = files.filter(p -> p.getFileName().toString().endsWith(".temp.db"))
                    .collect(java.util.stream.Collectors.toSet());
        }
        try (var db = PlatformDatabase.open(root, PlatformDatabaseTest.TEST_PROTECTION)) {
            var identity = new SchemaAccessScopeTest().prepare(db, "snapshot.spill", "1");
            try (var c = db.openConnection(); var s = c.createStatement()) {
                s.execute("SET MAX_MEMORY_ROWS 1");
            }
            try (var writer = new SchemaAccessScope(db, identity, UUID.randomUUID(), false);
                    var c = writer.openConnection();
                    var s = c.createStatement()) {
                s.execute("CREATE TABLE item(id INT PRIMARY KEY,value_text VARCHAR)");
                s.execute("INSERT INTO item SELECT n,SPACE(100) FROM SYSTEM_RANGE(1,1000) r(n)");
            }
            assertTrue(SchemaSnapshot.digest(db, new SchemaCoordinates(identity)).matches("[a-f0-9]{64}"));
            assertEquals(0, db.openConnections());
            try (var files = Files.walk(root)) {
                assertFalse(files.anyMatch(p -> p.getFileName().toString().endsWith(".temp.db")));
            }
        }
        try (var files = Files.list(temporary)) {
            var remaining = files.filter(p -> p.getFileName().toString().endsWith(".temp.db"))
                    .collect(java.util.stream.Collectors.toSet());
            remaining.removeAll(before);
            assertTrue(remaining.isEmpty(), remaining.toString());
        }
        try (var files = Files.walk(root)) {
            assertFalse(files.anyMatch(p -> p.getFileName().toString().endsWith(".sql")));
        }
    }
}
