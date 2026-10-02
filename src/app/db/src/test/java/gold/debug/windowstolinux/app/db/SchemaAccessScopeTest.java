package gold.debug.windowstolinux.app.db;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.sql.*;
import java.util.*;

import gold.debug.windowstolinux.app.db.connect.PlatformDatabase;
import gold.debug.windowstolinux.app.db.schema.SchemaAccessScope;
import gold.debug.windowstolinux.app.db.schema.SchemaCoordinates;
import gold.debug.windowstolinux.app.db.schema.SchemaProvisioner;
import gold.debug.windowstolinux.appapi.db.SchemaIdentity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SchemaAccessScopeTest {
    @TempDir
    Path root;

    @Test
    void recordedReaderCleanupChecksActualH2SessionEvenOutsideTheOriginalScopeTag() throws Exception {
        try (var db = PlatformDatabase.open(root, PlatformDatabaseTest.TEST_PROTECTION)) {
            var identity = prepare(db, "example.RecoveredReader", "1");
            var target = new SchemaCoordinates(identity);
            UUID scope = UUID.randomUUID();
            String user = SchemaAccessScope.readerUser(scope);
            try (var c = db.openConnection()) {
                sql(c, "CREATE TABLE \"" + target.physicalName() + "\".items(id INT PRIMARY KEY)");
                sql(c, "INSERT INTO \"" + target.physicalName() + "\".items VALUES(7)");
                sql(c, "CREATE USER \"" + user + "\" PASSWORD 'fixture'");
                sql(c, "GRANT SELECT ON \"" + target.physicalName() + "\".items TO \"" + user + "\"");
            }
            try (var original = db.openInternalConnection(target.physicalName(), user, "fixture",
                "fixture-other-scope")) {
                assertEquals(0, db.openConnections(scope.toString()));
                assertThrows(SQLException.class, () -> new SchemaProvisioner(db).releaseRecordedReader(scope, target));
                assertEquals(7, count(original, "SELECT id FROM items"));
            }
            new SchemaProvisioner(db).releaseRecordedReader(scope, target);
            try (var c = db.openConnection()) {
                assertEquals(0,
                    count(c, "SELECT COUNT(*) FROM information_schema.users WHERE user_name='" + user + "'"));
                assertEquals(7, count(c, "SELECT id FROM \"" + target.physicalName() + "\".items"));
            }
        }
    }

    SchemaIdentity prepare(PlatformDatabase database, String owner, String version) throws Exception {
        var identity = new SchemaIdentity(owner, version, UUID.randomUUID());
        var names = new SchemaCoordinates(identity);
        try (var c = database.openConnection()) {
            try (var p = c.prepareStatement("INSERT INTO mod_registry(mod_id,updated_at) VALUES(?,1)")) {
                p.setString(1, owner);
                p.executeUpdate();
            }
            try (var p = c.prepareStatement(
                "INSERT INTO mod_schema(mod_id,schema_version,generation,physical_name,database_user,state,created_at) VALUES(?,?,?,?,?,'PREPARING',1)")) {
                p.setString(1, owner);
                p.setString(2, version);
                p.setObject(3, identity.generation());
                p.setString(4, names.physicalName());
                p.setString(5, names.databaseUser());
                p.executeUpdate();
            }
        }
        new SchemaProvisioner(database).create(identity);
        return identity;
    }

    void sql(Connection c, String statement) throws SQLException {
        try (var s = c.createStatement()) {
            s.execute(statement);
        }
    }

    long count(Connection c, String statement) throws SQLException {
        try (var s = c.createStatement(); var r = s.executeQuery(statement)) {
            assertTrue(r.next());
            return r.getLong(1);
        }
    }

    @Test
    void ownersAreNonAdminAndCannotReachPlatformOrOtherMods() throws Exception {
        try (var db = PlatformDatabase.open(root, PlatformDatabaseTest.TEST_PROTECTION)) {
            var a = prepare(db, "example.Alpha", "1");
            var b = prepare(db, "example.Beta", "1");
            try (var access = new SchemaAccessScope(db, a, UUID.randomUUID(), false); var c = access.openConnection()) {
                sql(c, "CREATE TABLE items(id INT PRIMARY KEY, value_text VARCHAR NOT NULL)");
                sql(c, "INSERT INTO items VALUES(1,'retained')");
                assertEquals(1, count(c, "SELECT COUNT(*) FROM items"));
                assertEquals(0, count(c, "SELECT COUNT(*) FROM information_schema.users WHERE is_admin"));
                for (var denied : List.of("SELECT * FROM app.mod_registry",
                    "CREATE TABLE " + new SchemaCoordinates(b).physicalName() + ".bad(id INT)",
                    "CREATE USER intruder PASSWORD 'x'", "CREATE TABLE public.bad(id INT)",
                    "CREATE ALIAS escape FOR 'java.lang.System.getProperty'", "SET WRITE_DELAY 100"))
                    assertThrows(SQLException.class, () -> sql(c, denied), denied);
                assertThrows(SQLException.class, () -> c.unwrap(org.h2.jdbc.JdbcConnection.class));
                assertThrows(SQLException.class, () -> c.getMetaData().getURL());
            }
        }
    }

    @Test
    void readersHaveSelectOnlyAndRetainOccupancyUntilActualClose() throws Exception {
        try (var db = PlatformDatabase.open(root, PlatformDatabaseTest.TEST_PROTECTION)) {
            var identity = prepare(db, "example.Reader", "1");
            try (var write = new SchemaAccessScope(db, identity, UUID.randomUUID(), false);
                 var c = write.openConnection()) {
                sql(c, "CREATE TABLE items(id INT PRIMARY KEY)");
                sql(c, "INSERT INTO items VALUES(7)");
                sql(c, "CREATE VIEW item_view AS SELECT * FROM items");
            }
            UUID scopeId = UUID.randomUUID();
            try (var reader = new SchemaAccessScope(db, identity, scopeId, true)) {
                var c = reader.openConnection();
                try {
                    assertEquals(7, count(c, "SELECT id FROM item_view"));
                    for (var denied : List.of("INSERT INTO items VALUES(8)", "UPDATE items SET id=8",
                        "DELETE FROM items", "DROP TABLE items", "CREATE TABLE bad(id INT)",
                        "ALTER TABLE items ADD bad INT", "SELECT * FROM app.app_state"))
                        assertThrows(SQLException.class, () -> sql(c, denied), denied);
                    assertEquals(1, reader.openConnections());
                    assertThrows(SQLException.class, reader::close);
                    assertThrows(SQLException.class, db::close);
                } finally {
                    c.close();
                }
                assertEquals(0, reader.openConnections());
            }
            try (var c = db.openConnection()) {
                assertEquals(0, count(c, "SELECT COUNT(*) FROM information_schema.users WHERE user_name='"
                    + SchemaAccessScope.readerUser(scopeId) + "'"));
            }
            assertEquals(0, db.openConnections());
        }
    }

    @Test
    void staleGenerationAndUnavailableStateRejectNewConnections() throws Exception {
        try (var db = PlatformDatabase.open(root, PlatformDatabaseTest.TEST_PROTECTION)) {
            var identity = prepare(db, "example.Generation", "1");
            try (var access = new SchemaAccessScope(db, identity, UUID.randomUUID(), false)) {
                try (var c = db.openConnection();
                     var p = c.prepareStatement("UPDATE mod_schema SET generation=? WHERE mod_id=?")) {
                    p.setObject(1, UUID.randomUUID());
                    p.setString(2, identity.ownerId());
                    p.executeUpdate();
                }
                assertThrows(SQLException.class, access::openConnection);
            }
            assertThrows(SQLException.class, () -> new SchemaAccessScope(db, identity, UUID.randomUUID(), true));
        }
    }

    @Test
    void provisioningNeverAdoptsOrOverwritesExistingSchemaAndOwner() throws Exception {
        try (var db = PlatformDatabase.open(root, PlatformDatabaseTest.TEST_PROTECTION)) {
            var identity = prepare(db, "example.Existing", "1");
            try (var access = new SchemaAccessScope(db, identity, UUID.randomUUID(), false);
                 var c = access.openConnection()) {
                sql(c, "CREATE TABLE retained(id INT)");
                sql(c, "INSERT INTO retained VALUES(42)");
                assertThrows(SQLException.class, () -> new SchemaProvisioner(db).create(identity));
                assertEquals(42, count(c, "SELECT id FROM retained"));
            }
            assertThrows(SQLException.class,
                () -> new SchemaProvisioner(db).create(new SchemaIdentity("unregistered", "1", UUID.randomUUID())));
        }
    }

    @Test
    void exactOwnerIdentityAndLargeVersionsSurviveEngineRestart() throws Exception {
        SchemaIdentity identity;
        try (var db = PlatformDatabase.open(root, PlatformDatabaseTest.TEST_PROTECTION)) {
            identity = prepare(db, "example.大版本", "9".repeat(64));
            var names = new SchemaCoordinates(identity);
            assertEquals(132, names.physicalName().length());
            assertEquals(66, names.databaseUser().length());
            assertNotEquals(names.physicalName(),
                new SchemaCoordinates(
                    new SchemaIdentity("Example.大版本", identity.schemaVersion(), UUID.randomUUID()))
                    .physicalName());
            try (var scope = new SchemaAccessScope(db, identity, UUID.randomUUID(), false);
                 var c = scope.openConnection()) {
                sql(c, "CREATE TABLE retained(id INT)");
                sql(c, "INSERT INTO retained VALUES(123)");
            }
        }
        try (var db = PlatformDatabase.open(root, PlatformDatabaseTest.TEST_PROTECTION);
             var scope = new SchemaAccessScope(db, identity, UUID.randomUUID(), false);
             var c = scope.openConnection()) {
            assertEquals(123, count(c, "SELECT id FROM retained"));
        }
    }
}
