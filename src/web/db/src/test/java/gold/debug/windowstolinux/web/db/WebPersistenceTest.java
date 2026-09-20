package gold.debug.windowstolinux.web.db;

import gold.debug.windowstolinux.web.db.entity.*;
import gold.debug.windowstolinux.web.db.runtime.WebStorageLocation;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class WebPersistenceTest {
    @TempDir Path root;
    private WebDatabaseTestContext database;
    private final ResourceScope scope = WebPersistence.INTERNAL_SCOPE;
    @BeforeEach void open() throws Exception { database = new WebDatabaseTestContext(root); }
    @AfterEach void close() { database.close(); }

    @Test void schemaKeepsReservedRelationsAndEveryConnectionEnforcesForeignKeys() throws Exception {
        var tables = database.jdbc().queryForList("SELECT name FROM sqlite_master WHERE type='table'", String.class);
        assertEquals(26, tables.size());
        assertTrue(tables.containsAll(Set.of("roles", "permissions", "role_permissions", "member_roles", "user_credentials",
                "auth_sessions", "audit_events", "release_channels", "releases", "release_artifacts")));
        assertEquals(0, database.jdbc().queryForObject("SELECT count(*) FROM user_credentials", Integer.class));
        for (int i = 0; i < 3; i++) {
            assertEquals(1, database.jdbc().queryForObject("PRAGMA foreign_keys", Integer.class));
            assertEquals(5000, database.jdbc().queryForObject("PRAGMA busy_timeout", Integer.class));
        }
    }

    @Test void compositeIdentityAndMembershipPreventCrossWorkspaceAccess() {
        otherWorkspace();
        var other = new ResourceScope("other", "other"); var resources = database.resources();
        resources.save(scope, ResourceType.SERVER, "one", "First", server(), "{}", 0);
        assertTrue(resources.find(other, ResourceType.SERVER, "one").isEmpty());
        assertTrue(resources.list(other, ResourceType.SERVER).isEmpty());
        assertThrows(DataIntegrityViolationException.class, () -> resources.save(other, ResourceType.APPLICATION, "app", "App",
                Map.of("server_id", "one", "kind", "MANAGED", "remote_identity", "app"), "{}", 0));
        assertThrows(SecurityException.class, () -> resources.list(new ResourceScope("internal", "other"), ResourceType.SERVER));
        resources.save(other, ResourceType.SERVER, "one", "Other", server(), "{}", 0);
        assertEquals("First", resources.find(scope, ResourceType.SERVER, "one").orElseThrow().name());
        assertThrows(OptimisticLockingFailureException.class, () -> resources.delete(other, ResourceType.SERVER, "one", 2));
        assertEquals("Other", resources.find(other, ResourceType.SERVER, "one").orElseThrow().name());
    }

    @Test void compareAndSetSurvivesReopenAndNullUpdates() throws Exception {
        var resources = database.resources(); var first = server(); first.put("fingerprint", "SHA256:test");
        resources.save(scope, ResourceType.SERVER, "one", "First", first, "{}", 0);
        var second = resources.save(scope, ResourceType.SERVER, "one", "Second", server(), "{}", 1);
        assertEquals(2, second.version()); assertNull(second.attributes().get("fingerprint"));
        assertThrows(OptimisticLockingFailureException.class, () -> resources.save(scope, ResourceType.SERVER, "one", "Stale", server(), "{}", 1));
        database.close(); database = new WebDatabaseTestContext(root);
        assertEquals("Second", database.resources().find(scope, ResourceType.SERVER, "one").orElseThrow().name());
    }

    @Test void relationalValidationRejectsMalformedDocumentsPortsAndDuplicateEndpoints() {
        var resources = database.resources();
        assertThrows(DataIntegrityViolationException.class, () -> resources.save(scope, ResourceType.SERVER, "one", "First", server(), "no json", 0));
        var bad = server(); bad.put("port", 0);
        assertThrows(DataIntegrityViolationException.class, () -> resources.save(scope, ResourceType.SERVER, "one", "First", bad, "{}", 0));
        resources.save(scope, ResourceType.SERVER, "one", "First", server(), "{}", 0);
        assertThrows(DataIntegrityViolationException.class, () -> resources.save(scope, ResourceType.SERVER, "two", "Second", server(), "{}", 0));
    }

    @Test void preferencesRollbackAsAUnitAndRejectDisabledMembership() {
        var resources = database.resources(); resources.savePreferences(scope, Map.of("theme", "dark"));
        var changes = new LinkedHashMap<String,String>(); changes.put("theme", "light"); changes.put("invalid", "value");
        assertThrows(IllegalArgumentException.class, () -> resources.savePreferences(scope, changes));
        assertEquals("dark", resources.preferences(scope).get("theme"));
        database.jdbc().update("UPDATE workspace_members SET status='DISABLED'");
        assertThrows(SecurityException.class, () -> resources.preferences(scope));
    }

    @Test void taskTargetsAndInitialEventRollbackTogether() {
        assertThrows(DataIntegrityViolationException.class, () -> database.tasks().create(scope, "task", "PROBE", "{}",
                List.of("missing"), false, null, null, null));
        assertTrue(database.tasks().find(scope, "task").isEmpty());
        assertTrue(database.tasks().events(scope, "task", 0).isEmpty());
    }

    @Test void eventFailureRollsBackStateAndSuccessPublishesExactlyOneEvent() {
        var tasks = database.tasks(); tasks.create(scope, "task", "PROBE", "{}", List.of(), false, null, null, null);
        database.jdbc().execute("CREATE TRIGGER reject_event BEFORE INSERT ON task_events BEGIN SELECT RAISE(ABORT,'test'); END");
        assertThrows(RuntimeException.class, () -> tasks.transition(scope, "task", Set.of("QUEUED"), "RUNNING", null, null));
        assertEquals("QUEUED", tasks.find(scope, "task").orElseThrow().state());
        assertEquals(1, tasks.events(scope, "task", 0).size());
        database.jdbc().execute("DROP TRIGGER reject_event");
        assertTrue(tasks.transition(scope, "task", Set.of("QUEUED"), "RUNNING", null, null));
        assertFalse(tasks.transition(scope, "task", Set.of("QUEUED"), "RUNNING", null, null));
        assertEquals(2, tasks.events(scope, "task", 0).size());
    }

    @Test void activeTasksPinEndpointAndRecoveryRetainsScopedEvents() {
        var resources = database.resources(); var tasks = database.tasks();
        resources.save(scope, ResourceType.SERVER, "one", "First", server(), "{}", 0);
        tasks.create(scope, "task", "SERVER_PROBE", "{}", List.of("one"), false, null, null, null);
        var changed = server(); changed.put("host", "changed.invalid");
        assertThrows(OptimisticLockingFailureException.class, () -> resources.save(scope, ResourceType.SERVER, "one", "First", changed, "{}", 1));
        resources.save(scope, ResourceType.SERVER, "one", "First", server(), "{\"connected\":true}", 1);
        assertEquals(1, tasks.recoverInterrupted()); assertEquals(0, tasks.recoverInterrupted());
        assertEquals("REVALIDATION_REQUIRED", tasks.events(scope, "task", 1).getFirst().message());
        assertEquals("changed.invalid", resources.save(scope, ResourceType.SERVER, "one", "First", changed, "{}", 2).attributes().get("host"));
    }

    @Test void resourceAndConfigurationRevisionCommitOrRollbackTogether() {
        var resources = database.resources(); resources.save(scope, ResourceType.SERVER, "one", "First", server(), "{}", 0);
        var fields = Map.<String,Object>of("server_id", "one", "kind", "MANAGED", "remote_identity", "app");
        resources.save(scope, ResourceType.APPLICATION, "app", "App", fields, "{}", 0);
        database.jdbc().execute("CREATE TRIGGER reject_revision BEFORE INSERT ON configuration_revisions BEGIN SELECT RAISE(ABORT,'test'); END");
        String successful = "{\"deploymentState\":\"SUCCEEDED\",\"graph\":{\"value\":1}}";
        assertThrows(RuntimeException.class, () -> resources.save(scope, ResourceType.APPLICATION, "app", "Changed", fields, successful, 1));
        assertEquals(1, resources.find(scope, ResourceType.APPLICATION, "app").orElseThrow().version());
        database.jdbc().execute("DROP TRIGGER reject_revision");
        resources.save(scope, ResourceType.APPLICATION, "app", "Changed", fields, successful, 1);
        assertEquals(1, database.jdbc().queryForObject("SELECT count(*) FROM configuration_revisions", Integer.class));
    }

    @Test void schemaFailureRollsBackAndUnknownVersionIsPreserved() throws Exception {
        Path broken = root.resolve("broken"); java.nio.file.Files.createDirectory(broken);
        var source = new SQLiteDataSource(); source.setUrl("jdbc:sqlite:" + broken.resolve(WebStorageLocation.DATABASE_NAME));
        var jdbc = new JdbcTemplate(source); jdbc.execute("CREATE TABLE tasks (keep_value TEXT)");
        assertThrows(RuntimeException.class, () -> new WebDatabaseTestContext(broken));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM sqlite_master WHERE name='users'", Integer.class));
        jdbc.execute("PRAGMA user_version=999");
        assertThrows(RuntimeException.class, () -> new WebDatabaseTestContext(broken));
        assertEquals(999, jdbc.queryForObject("PRAGMA user_version", Integer.class));
    }

    private void otherWorkspace() {
        database.jdbc().update("INSERT INTO users VALUES ('other','Other','ACTIVE','now','now')");
        database.jdbc().update("INSERT INTO workspaces VALUES ('other','Other','other','ACTIVE','now','now')");
        database.jdbc().update("INSERT INTO workspace_members VALUES ('other','other','ACTIVE','now')");
    }
    private static Map<String,Object> server() {
        var values = new LinkedHashMap<String,Object>();
        values.put("host", "test.invalid"); values.put("port", 22); values.put("username", "deploy");
        values.put("fingerprint", null); values.put("secret_id", null); values.put("secret_version", null);
        return values;
    }
}
