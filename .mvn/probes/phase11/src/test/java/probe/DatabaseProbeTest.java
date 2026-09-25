package probe;

import java.nio.file.Path;
import java.nio.file.Files;
import java.io.StringReader;
import java.sql.*;
import org.h2.tools.RunScript;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class DatabaseProbeTest {
    @TempDir Path root;
    String url() { return "jdbc:h2:" + root.resolve("platform").toAbsolutePath() + ";DATABASE_TO_LOWER=TRUE;DB_CLOSE_ON_EXIT=FALSE;AUTO_SERVER=FALSE;LOCK_TIMEOUT=5000;WRITE_DELAY=0"; }
    Connection admin() throws Exception { return DriverManager.getConnection(url(), "app_internal", "probe-only"); }
    Connection owner(String user) throws Exception { return DriverManager.getConnection("jdbc:h2:" + root.resolve("platform").toAbsolutePath() + ";DATABASE_TO_LOWER=TRUE;IFEXISTS=TRUE;LOCK_TIMEOUT=5000", user, "probe-only"); }
    void sql(Connection c, String sql) throws Exception { try (var s = c.createStatement()) { s.execute(sql); } }
    long count(Connection c, String sql) throws Exception { try (var s = c.createStatement(); var r = s.executeQuery(sql)) { r.next(); return r.getLong(1); } }
    void setup(Connection c) throws Exception {
        sql(c, "CREATE USER mod_a PASSWORD 'probe-only'");
        sql(c, "CREATE USER mod_b PASSWORD 'probe-only'");
        sql(c, "CREATE SCHEMA app AUTHORIZATION app_internal");
        sql(c, "CREATE SCHEMA a AUTHORIZATION mod_a");
        sql(c, "CREATE SCHEMA b AUTHORIZATION mod_b");
    }
    void script(Connection c, String name) throws Exception {
        try (var reader = new java.io.InputStreamReader(getClass().getResourceAsStream("/" + name), java.nio.charset.StandardCharsets.UTF_8)) { RunScript.execute(c, reader); }
    }
    @Test void allFortyFourTablesInitializeAndReopen() throws Exception {
        try (var c = admin()) {
            setup(c);
            sql(c, "SET SCHEMA app"); script(c,"app.sql");
            sql(c, "SET SCHEMA a"); script(c,"deployment.sql");
            sql(c, "SET SCHEMA b"); script(c,"backup.sql");
            assertEquals(44, count(c, "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema IN ('app','a','b') AND table_type='BASE TABLE'"));
        }
        try (var c = admin()) {
            assertEquals(44, count(c, "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema IN ('app','a','b') AND table_type='BASE TABLE'"));
        }
    }
    @Test void privateTablesForeignKeysAndAdministrativeCommandsAreDenied() throws Exception {
        try (var c = admin()) { setup(c); sql(c,"CREATE TABLE app.private_data(id INT PRIMARY KEY)"); sql(c,"CREATE TABLE b.private_data(id INT PRIMARY KEY)"); }
        try (var c = owner("mod_a")) {
            sql(c,"SET SCHEMA a"); sql(c,"CREATE TABLE own(id INT PRIMARY KEY)");
            for (var denied : new String[]{"SELECT * FROM app.private_data", "SELECT * FROM b.private_data", "INSERT INTO b.private_data VALUES(1)", "CREATE TABLE a.bad(id INT REFERENCES b.private_data(id))", "CREATE USER intruder PASSWORD 'x'", "CREATE TABLE public.bad(id INT)", "CREATE ALIAS a.escape FOR 'java.lang.System.getProperty'", "SET WRITE_DELAY 100"}) {
                assertThrows(SQLException.class, () -> sql(c,denied), denied);
            }
            assertEquals(0, count(c,"SELECT COUNT(*) FROM a.own"));
        }
    }
    @Test void batchesSavepointsLobsAndCloseRollback() throws Exception {
        try(var c=admin()) { setup(c); }
        try(var c=owner("mod_a")) {
            sql(c,"SET SCHEMA a"); sql(c,"CREATE TABLE items(id INT PRIMARY KEY, value_blob BLOB, value_text CLOB)");
            sql(c,"CREATE TABLE tags(item_id INT REFERENCES items(id), label VARCHAR)");
            assertTrue(c.getAutoCommit()); assertEquals(Connection.TRANSACTION_READ_COMMITTED,c.getTransactionIsolation());
            c.setAutoCommit(false);
            try(var p=c.prepareStatement("INSERT INTO items VALUES(?,?,?)")) {
                for(int i=0;i<3;i++) { p.setInt(1,i); p.setBytes(2,new byte[]{(byte)i}); p.setCharacterStream(3,new StringReader("中文"+i)); p.addBatch(); }
                assertEquals(3,p.executeBatch().length);
            }
            var save=c.setSavepoint(); sql(c,"INSERT INTO tags VALUES(1,'discard')"); c.rollback(save);
            sql(c,"INSERT INTO tags VALUES(2,'keep')"); c.commit();
            assertEquals(1,count(c,"SELECT COUNT(*) FROM items i JOIN tags t ON i.id=t.item_id"));
            try(var s=c.createStatement(); var r=s.executeQuery("SELECT value_blob,value_text FROM items WHERE id=2")) {
                assertTrue(r.next()); assertArrayEquals(new byte[]{2},r.getBytes(1)); assertEquals("中文2",r.getString(2));
            }
            sql(c,"INSERT INTO items VALUES(99,NULL,NULL)");
        }
        try(var c=owner("mod_a")) { assertEquals(3,count(c,"SELECT COUNT(*) FROM a.items")); }
    }
    @Test void migrationReadIdentityCannotMutateSourceAndDdlCannotBeRolledBack() throws Exception {
        try(var c=admin()) {
            setup(c); sql(c,"CREATE TABLE a.source(id INT PRIMARY KEY)"); sql(c,"INSERT INTO a.source VALUES(7)");
            sql(c,"CREATE USER migration_reader PASSWORD 'probe-only'"); sql(c,"GRANT SELECT ON a.source TO migration_reader");
        }
        try(var source=owner("migration_reader");var target=owner("mod_b")) {
            assertEquals(7,count(source,"SELECT id FROM a.source"));
            for(var denied:new String[]{"INSERT INTO a.source VALUES(8)","DELETE FROM a.source", "ALTER TABLE a.source ADD bad INT", "DROP TABLE a.source", "SELECT * FROM b.copy"}) assertThrows(SQLException.class,()->sql(source,denied));
            target.setAutoCommit(false); sql(target,"CREATE TABLE b.copy(id INT PRIMARY KEY)");
            sql(target,"INSERT INTO b.copy VALUES(7)"); target.rollback();
            assertEquals(0,count(target,"SELECT COUNT(*) FROM b.copy"));
            assertEquals(1,count(source,"SELECT COUNT(*) FROM a.source"));
        }
        try(var c=admin()) { assertEquals(0,count(c,"SELECT COUNT(*) FROM b.copy")); assertEquals(7,count(c,"SELECT id FROM a.source")); }
    }
    @Test void missingDatabaseNeverSilentlyCreatesAndOldSqliteIsUntouched() throws Exception {
        var old=root.resolve("windowstolinux.db"); Files.writeString(old,"old sqlite fixture bytes");
        assertThrows(SQLException.class,()->DriverManager.getConnection(url()+";IFEXISTS=TRUE","app_internal","probe-only"));
        assertFalse(Files.exists(root.resolve("platform.mv.db")));
        try(var c=admin()) { sql(c,"CREATE TABLE sentinel(id INT PRIMARY KEY)");sql(c,"INSERT INTO sentinel VALUES(1)"); }
        assertEquals("old sqlite fixture bytes",Files.readString(old));
        assertThrows(SQLException.class,()->DriverManager.getConnection(url()+";IFEXISTS=TRUE","app_internal","incorrect"));
        try(var c=admin()) { assertEquals(1,count(c,"SELECT COUNT(*) FROM sentinel")); }
    }
    Process crashProcess(String mode)throws Exception {
        return new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java.exe").toString(),"-cp",System.getProperty("java.class.path"),DatabaseCrashFixture.class.getName(),url(),mode).redirectErrorStream(true).start();
    }
    @Test void secondProcessCannotOpenTheLiveEmbeddedDatabase()throws Exception {
        try(var c=admin()) {
            setup(c);var process=crashProcess("open");
            assertTrue(process.waitFor(10,java.util.concurrent.TimeUnit.SECONDS));assertEquals(9,process.exitValue());
            assertTrue(new String(process.getInputStream().readAllBytes()).contains("90020"));process.getInputStream().close();process.getOutputStream().close();
        }
    }
    @Test void abruptExitReconcilesActualTargetWithoutChangingSource()throws Exception {
        try(var c=admin()){setup(c);}
        var process=crashProcess("complete");assertTrue(process.waitFor(10,java.util.concurrent.TimeUnit.SECONDS));assertEquals(23,process.exitValue());process.getInputStream().close();process.getOutputStream().close();
        try(var c=admin()) {
            assertEquals(17,count(c,"SELECT id FROM a.original"));assertEquals(17,count(c,"SELECT id FROM b.migrated"));
            assertEquals(1,count(c,"SELECT COUNT(*) FROM app.operation WHERE state='PREPARING'"));
            sql(c,"UPDATE app.operation SET state='READY' WHERE id=1");
            assertEquals(1,count(c,"SELECT COUNT(*) FROM app.operation WHERE state='READY'"));
        }
    }
    @Test void incompleteDdlTargetIsNotMistakenForReadyData()throws Exception {
        try(var c=admin()){setup(c);}
        var process=crashProcess("incomplete");assertTrue(process.waitFor(10,java.util.concurrent.TimeUnit.SECONDS));assertEquals(23,process.exitValue());process.getInputStream().close();process.getOutputStream().close();
        try(var c=admin()) {
            assertEquals(17,count(c,"SELECT id FROM a.original"));assertEquals(0,count(c,"SELECT COUNT(*) FROM b.migrated"));
            sql(c,"UPDATE app.operation SET state='UNKNOWN' WHERE id=1");assertEquals(1,count(c,"SELECT COUNT(*) FROM app.operation WHERE state='UNKNOWN'"));
        }
    }
}
