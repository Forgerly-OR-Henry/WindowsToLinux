package gold.debug.windowstolinux.app.db;

import gold.debug.windowstolinux.app.db.task.TaskLedger;
import gold.debug.windowstolinux.app.db.task.TaskRecoveryGraph;
import gold.debug.windowstolinux.app.db.task.TaskResource;
import gold.debug.windowstolinux.appapi.task.Outcome;
import gold.debug.windowstolinux.appapi.task.TaskRequest;
import gold.debug.windowstolinux.appapi.task.TaskState;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.sql.*;
import java.util.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import gold.debug.windowstolinux.app.db.connect.PlatformDatabase;

import gold.debug.windowstolinux.appapi.db.SchemaIdentity;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TaskRecoveryGraphTest {
    @TempDir
    Path root;

    static final String OWNER = "example.original";

    static final String VERSION = "1.0.0";

    static final TaskRequest REQUEST = new TaskRequest("fixture", "original graph", List.of(), true);

    static TaskRecoveryGraph graph() {
        return new TaskRecoveryGraph(OWNER,
            List.of(new TaskRecoveryGraph.Release(OWNER, VERSION, "a".repeat(64),
                    Optional.of(new SchemaIdentity(OWNER, "1", UUID.randomUUID()))),
                new TaskRecoveryGraph.Release("example.provider", "2.0.0", "b".repeat(64), Optional.empty())));
    }

    static List<TaskResource> claims(TaskRecoveryGraph graph) {
        return List.of(new TaskResource("MOD", OWNER, OWNER, false), new TaskResource("SCHEMA",
            graph.release(OWNER).schema().orElseThrow().generation().toString(), OWNER, false));
    }

    static UUID enqueue(TaskLedger ledger, TaskRecoveryGraph graph) throws Exception {
        return ledger.enqueue(UUID.randomUUID(), OWNER, VERSION, REQUEST, new TaskLedger.InitialClaims() {
            @Override
            public List<TaskResource> resolve(Connection c) {
                return claims(graph);
            }

            @Override
            public Optional<TaskRecoveryGraph> recoveryGraph(Connection c) throws SQLException {
                assertFalse(c.getAutoCommit());
                return Optional.of(graph);
            }
        });
    }

    static String metadata(PlatformDatabase db, UUID id) throws Exception {
        try (var c = db.openConnection();
             var p = c.prepareStatement("SELECT recovery_json FROM platform_task WHERE task_id=?")) {
            p.setObject(1, id);
            try (var r = p.executeQuery()) {
                assertTrue(r.next());
                return r.getString(1);
            }
        }
    }

    static void metadata(PlatformDatabase db, UUID id, String value) throws Exception {
        try (var c = db.openConnection();
             var p = c.prepareStatement("UPDATE platform_task SET recovery_json=? WHERE task_id=?")) {
            p.setString(1, value);
            p.setObject(2, id);
            assertEquals(1, p.executeUpdate());
        }
    }

    @Test
    void originalGraphSurvivesDatabaseReopenWithoutConsultingCurrentModRegistry() throws Exception {
        var graph = graph();
        UUID id;
        try (var db = PlatformDatabase.open(root, PlatformDatabaseTest.TEST_PROTECTION)) {
            var ledger = new TaskLedger(db);
            id = enqueue(ledger, graph);
            assertEquals(Optional.of(graph), ledger.queries().recoveryGraph(id));
            assertEquals(2, new ObjectMapper().readTree(metadata(db, id)).path("formatVersion").intValue());
            assertTrue(ledger.tryStart(id));
            assertEquals(2, new TaskLedgerTest().active(db, id));
        }
        try (var db = PlatformDatabase.open(root, PlatformDatabaseTest.TEST_PROTECTION)) {
            var ledger = new TaskLedger(db);
            assertEquals(Optional.of(graph), ledger.queries().recoveryGraph(id));
            assertThrows(SQLException.class, () -> ledger.tryStart(id));
            ledger.uncertain(id);
            assertEquals(Optional.of(graph), ledger.queries().recoveryGraph(id));
            assertTrue(ledger.complete(id, Outcome.FAILED, "{}", true));
        }
    }

    @Test
    void malformedOrMismatchedCapturedGraphNeverStartsOrAllocatesResources() throws Exception {
        try (var db = PlatformDatabase.open(root, PlatformDatabaseTest.TEST_PROTECTION)) {
            var ledger = new TaskLedger(db);
            var id = enqueue(ledger, graph());
            String original = metadata(db, id);
            var json = new ObjectMapper();
            var mutations = new ArrayList<String>();
            ObjectNode document = (ObjectNode) json.readTree(original);
            document.remove("graph");
            mutations.add(document.toString());
            document = (ObjectNode) json.readTree(original);
            document.put("formatVersion", 3);
            mutations.add(document.toString());
            document = (ObjectNode) json.readTree(original);
            ((com.fasterxml.jackson.databind.node.ArrayNode) document.path("initialClaims")).remove(0);
            mutations.add(document.toString());
            document = (ObjectNode) json.readTree(original);
            ((ObjectNode) document.path("graph")).put("owner", "example.provider");
            mutations.add(document.toString());
            document = (ObjectNode) json.readTree(original);
            ((ObjectNode) document.path("graph").path("releases").get(0)).put("version", "9.0.0");
            mutations.add(document.toString());
            document = (ObjectNode) json.readTree(original);
            ((ObjectNode) document.path("graph").path("releases").get(0)).put("schemaGeneration",
                UUID.randomUUID().toString());
            mutations.add(document.toString());
            document = (ObjectNode) json.readTree(original);
            ((ObjectNode) document.path("graph").path("releases").get(1)).put("schemaGeneration",
                UUID.randomUUID().toString());
            mutations.add(document.toString());
            document = (ObjectNode) json.readTree(original);
            ((com.fasterxml.jackson.databind.node.ArrayNode) document.path("graph").path("releases"))
                .add(document.path("graph").path("releases").get(0).deepCopy());
            mutations.add(document.toString());
            mutations.add(original.replaceFirst("\\{", "{\"formatVersion\":2,"));
            mutations.add(original + " {}");
            mutations.add(" ".repeat(1024 * 1024) + original);
            for (String value : mutations) {
                metadata(db, id, value);
                assertThrows(SQLException.class, () -> ledger.queries().recoveryGraph(id));
                assertThrows(SQLException.class, () -> ledger.tryStart(id));
                assertEquals(TaskState.QUEUED, ledger.queries().snapshot(id).state());
                assertEquals(0, new TaskLedgerTest().active(db, id));
            }
            metadata(db, id, original);
            assertTrue(ledger.tryStart(id));
        }
    }

    @Test
    void failedCaptureAndMismatchedInitialSchemaRollBackBeforeTaskInsertion() throws Exception {
        try (var db = PlatformDatabase.open(root, PlatformDatabaseTest.TEST_PROTECTION)) {
            var ledger = new TaskLedger(db);
            var graph = graph();
            for (boolean fail : List.of(false, true)) {
                var id = UUID.randomUUID();
                assertThrows(SQLException.class,
                    () -> ledger.enqueue(id, OWNER, VERSION, REQUEST, new TaskLedger.InitialClaims() {
                        @Override
                        public List<TaskResource> resolve(Connection c) {
                            return List.of(new TaskResource("MOD", OWNER, OWNER, false));
                        }

                        @Override
                        public Optional<TaskRecoveryGraph> recoveryGraph(Connection c) throws SQLException {
                            if (fail)
                                throw new SQLException("fixture original identity unavailable");
                            return Optional.of(graph);
                        }
                    }));
                assertThrows(SQLException.class, () -> ledger.queries().snapshot(id));
                assertEquals(0, new TaskLedgerTest().active(db, id));
            }
        }
    }

    @Test
    void hostAndEarlierAdmissionRecordsRemainExplicitlyWithoutInventedModGraph() throws Exception {
        try (var db = PlatformDatabase.open(root, PlatformDatabaseTest.TEST_PROTECTION)) {
            var ledger = new TaskLedger(db);
            var id = ledger.enqueue(UUID.randomUUID(), OWNER, VERSION, REQUEST,
                List.of(new TaskResource("MOD", OWNER, OWNER, false)));
            assertTrue(ledger.queries().recoveryGraph(id).isEmpty());
            assertTrue(ledger.tryStart(id));
            assertEquals(1, new ObjectMapper().readTree(metadata(db, id)).path("formatVersion").intValue());
        }
    }
}
