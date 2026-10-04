package gold.debug.windowstolinux.app.db;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.sql.*;
import java.util.*;

import gold.debug.windowstolinux.app.db.connect.PlatformDatabase;
import gold.debug.windowstolinux.app.db.mod.ModActivationLedger;
import gold.debug.windowstolinux.app.db.schema.SchemaCoordinates;
import gold.debug.windowstolinux.app.db.task.TaskLedger;
import gold.debug.windowstolinux.app.db.task.TaskResource;
import gold.debug.windowstolinux.appapi.db.schema.SchemaIdentity;
import gold.debug.windowstolinux.appapi.task.model.TaskRequest;
import gold.debug.windowstolinux.appapi.task.result.Problem;
import gold.debug.windowstolinux.appapi.task.result.ProblemCode;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ModActivationLedgerTest {
    @TempDir
    Path root;

    static final String OWNER = "example.activation";

    static final String VERSION = "1.0.0";

    static void sql(PlatformDatabase db, String statement, Object... values) throws SQLException {
        try (var c = db.openConnection(); var s = c.prepareStatement(statement)) {
            for (int i = 0; i < values.length; i++)
                s.setObject(i + 1, values[i]);
            s.executeUpdate();
        }
    }

    static Object value(PlatformDatabase db, String query) throws SQLException {
        try (var c = db.openConnection(); var s = c.createStatement(); var r = s.executeQuery(query)) {
            assertTrue(r.next());
            return r.getObject(1);
        }
    }

    static void register(PlatformDatabase db, String schema) throws SQLException {
        sql(db, "INSERT INTO mod_registry(mod_id,updated_at) VALUES(?,1)", OWNER);
        for (var version : List.of(VERSION, "2.0.0"))
            sql(db, "INSERT INTO mod_version(mod_id,version,install_path,content_sha256,schema_version,manifest_json,origin_kind,installed_at,state) VALUES(?,?,'fixture',?,?,'{}','CLASS',1,'READY')",
                OWNER, version, "0".repeat(64), schema);
        sql(db, "UPDATE mod_registry SET enabled=TRUE,selected_version=?,selection_initialized=TRUE,revision=4,disable_ack_revision=2 WHERE mod_id=?",
            VERSION, OWNER);
    }

    static ModActivationLedger ledger(PlatformDatabase db) {
        return new ModActivationLedger(db, OWNER, VERSION, Optional.empty());
    }

    static void state(PlatformDatabase db, String state, String actual) throws SQLException {
        assertEquals(state, value(db, "SELECT last_runtime_state FROM mod_registry"));
        assertEquals(actual, value(db, "SELECT actual_version FROM mod_registry"));
    }

    static Problem failure() {
        return new Problem(ProblemCode.IO_FAILURE, "MOD_START_FAILED", "Safe startup failure", Map.of(),
            Optional.of(UUID.randomUUID()));
    }

    @Test
    void actualLifecyclePreservesNewIntentAndCannotBeReplayedInOneBoot() throws Exception {
        try (var db = PlatformDatabase.open(root, PlatformDatabaseTest.TEST_PROTECTION)) {
            register(db, null);
            var journal = ledger(db);
            journal.starting();
            state(db, "STARTING", VERSION);
            assertEquals(db.bootId(), value(db, "SELECT last_boot_id FROM mod_registry"));
            sql(db, "UPDATE mod_registry SET enabled=FALSE,selected_version='2.0.0',revision=5");
            journal.running();
            state(db, "RUNNING", VERSION);
            assertThrows(SQLException.class, ledger(db)::running);
            var rejected = ledger(db);
            assertThrows(SQLException.class, rejected::starting);
            rejected.closed(Optional.of(failure()));
            state(db, "RUNNING", VERSION);
            journal.stopping();
            journal.closed(Optional.empty());
            journal.closed(Optional.empty());
            state(db, "STOPPED", null);
            assertEquals(false, value(db, "SELECT enabled FROM mod_registry"));
            assertEquals("2.0.0", value(db, "SELECT selected_version FROM mod_registry"));
            assertEquals(5L, value(db, "SELECT revision FROM mod_registry"));
            assertEquals(2L, value(db, "SELECT disable_ack_revision FROM mod_registry"));
            sql(db, "UPDATE mod_registry SET enabled=TRUE,selected_version=?", VERSION);
            assertThrows(SQLException.class, ledger(db)::starting);
            assertThrows(SQLException.class, journal::starting);
        }
        try (var db = PlatformDatabase.open(root, PlatformDatabaseTest.TEST_PROTECTION)) {
            var journal = ledger(db);
            journal.starting();
            journal.running();
            journal.stopping();
            journal.closed(Optional.empty());
        }
    }

    @Test
    void failureEvidenceSurvivesCloseWithoutChangingEnabledOrAcknowledgingDisable() throws Exception {
        var problem = failure();
        try (var db = PlatformDatabase.open(root, PlatformDatabaseTest.TEST_PROTECTION)) {
            register(db, null);
            var journal = ledger(db);
            journal.starting();
            journal.retained(problem, false);
            state(db, "UNKNOWN", VERSION);
            journal.stopping();
            journal.closed(Optional.of(problem));
            state(db, "FAILED", null);
            assertEquals(true, value(db, "SELECT enabled FROM mod_registry"));
            assertEquals(4L, value(db, "SELECT revision FROM mod_registry"));
            assertEquals(2L, value(db, "SELECT disable_ack_revision FROM mod_registry"));
            assertEquals(problem.eventCode(), value(db, "SELECT failure_code FROM mod_registry"));
            try (var c = db.openConnection();
                 var s = c.createStatement();
                 var r = s.executeQuery("SELECT failure_detail FROM mod_registry")) {
                assertTrue(r.next());
                assertTrue(r.getString(1).contains(problem.diagnosticId().orElseThrow().toString()));
            }
        }
    }

    @Test
    void schemaUseAndStartingCommitTogetherAndRejectStaleGeneration() throws Exception {
        try (var db = PlatformDatabase.open(root, PlatformDatabaseTest.TEST_PROTECTION)) {
            register(db, "1");
            var identity = new SchemaIdentity(OWNER, "1", UUID.randomUUID());
            var names = new SchemaCoordinates(identity);
            sql(db, "INSERT INTO mod_schema(mod_id,schema_version,generation,physical_name,database_user,state,created_at,validated_at) VALUES(?,'1',?,?,?,'READY',1,1)",
                OWNER, identity.generation(), names.physicalName(), names.databaseUser());
            var stale = new ModActivationLedger(db, OWNER, VERSION,
                Optional.of(new SchemaIdentity(OWNER, "1", UUID.randomUUID())));
            assertThrows(SQLException.class, stale::starting);
            stale.closed(Optional.of(failure()));
            assertEquals(false, value(db, "SELECT ever_used FROM mod_schema"));
            sql(db, "ALTER TABLE mod_registry ADD CONSTRAINT fixture_start CHECK(last_runtime_state<>'STARTING')");
            var failedWrite = new ModActivationLedger(db, OWNER, VERSION, Optional.of(identity));
            assertThrows(SQLException.class, failedWrite::starting);
            failedWrite.closed(Optional.of(failure()));
            assertEquals(false, value(db, "SELECT ever_used FROM mod_schema"));
            state(db, "STOPPED", null);
            sql(db, "ALTER TABLE mod_registry DROP CONSTRAINT fixture_start");
            var journal = new ModActivationLedger(db, OWNER, VERSION, Optional.of(identity));
            journal.starting();
            assertEquals(true, value(db, "SELECT ever_used FROM mod_schema"));
            journal.closed(Optional.of(failure()));
            assertEquals(true, value(db, "SELECT ever_used FROM mod_schema"));
        }
    }

    @Test
    void unfinishedMaintenanceIncludingNullTargetAndPendingArchiveBlocksActivation() throws Exception {
        try (var db = PlatformDatabase.open(root, PlatformDatabaseTest.TEST_PROTECTION)) {
            register(db, null);
            for (var row : List.of(new String[]{"ENABLE", "RUNNING", "APPLYING", null, null},
                new String[]{"SWITCH", "RUNNING", "APPLYING", "2.0.0", null},
                new String[]{"ENABLE", "WAITING", "WAIT_RESTART", VERSION, null},
                new String[]{"MIGRATE", "RUNNING", "APPLYING", VERSION, null},
                new String[]{"ENABLE", "FINISHING", "VERIFIED", VERSION, "SUCCEEDED"},
                new String[]{"ENABLE", "RUNNING", "APPLYING", VERSION, "FAILED"})) {
                sql(db, "INSERT INTO mod_operation(operation_id,mod_id,operation_type,state,phase,target_version,outcome,request_json,recovery_json,archive_log_id,created_at,updated_at) VALUES(?,?,?,?,?,?,?,'{}','{}',?,1,1)",
                    UUID.randomUUID(), OWNER, row[0], row[1], row[2], row[3], row[4], UUID.randomUUID().toString());
                var rejected = ledger(db);
                assertThrows(SQLException.class, rejected::starting, Arrays.toString(row));
                rejected.closed(Optional.of(failure()));
                state(db, "STOPPED", null);
                sql(db, "DELETE FROM mod_operation");
            }
            sql(db, "INSERT INTO mod_operation(operation_id,mod_id,operation_type,state,phase,target_version,request_json,recovery_json,archive_log_id,created_at,updated_at) VALUES(?,?,'ENABLE','RUNNING','APPLYING',?,'{}','{}',?,1,1)",
                UUID.randomUUID(), OWNER, VERSION, UUID.randomUUID().toString());
            var journal = ledger(db);
            sql(db, "UPDATE mod_operation SET accepted_revision=3");
            assertThrows(SQLException.class, ledger(db)::starting);
            sql(db, "UPDATE mod_operation SET accepted_revision=4");
            assertThrows(SQLException.class, ledger(db)::starting);
            sql(db, "UPDATE mod_operation SET recovery_json=?",
                "{\"formatVersion\":1,\"applicationBootId\":\"" + UUID.randomUUID() + "\"}");
            assertThrows(SQLException.class, ledger(db)::starting);
            sql(db, "UPDATE mod_operation SET recovery_json=?",
                "{\"formatVersion\":1,\"applicationBootId\":\"" + db.bootId() + "\"}");
            journal.starting();
            journal.closed(Optional.empty());
            assertEquals("RUNNING", value(db, "SELECT state FROM mod_operation"));
        }
    }

    @Test
    void crossModResourcesAndQueuedOwnerWorkMustDrainBeforeActivationOrFinalClose() throws Exception {
        try (var db = PlatformDatabase.open(root, PlatformDatabaseTest.TEST_PROTECTION)) {
            register(db, null);
            var tasks = new TaskLedger(db);
            UUID caller = tasks.enqueue(UUID.randomUUID(), "other.mod", VERSION,
                new TaskRequest("fixture", "Cross Mod", List.of(), true),
                List.of(new TaskResource("MOD", "other.mod", "other.mod", false),
                    new TaskResource("MOD", OWNER, "other.mod", false)));
            assertTrue(tasks.tryStart(caller));
            assertThrows(SQLException.class, ledger(db)::starting);
            assertTrue(tasks.complete(caller, TaskResult.SUCCEEDED, "{}", false));
            var journal = ledger(db);
            journal.starting();
            journal.running();
            UUID queued = new TaskLedgerTest().queue(tasks, OWNER);
            journal.stopping();
            assertThrows(SQLException.class, () -> journal.closed(Optional.empty()));
            state(db, "STOPPING", VERSION);
            assertTrue(tasks.cancel(queued));
            journal.closed(Optional.empty());
            state(db, "STOPPED", null);
        }
    }

    @Test
    void priorRuntimeResidueCannotBeOverwrittenByAnUnrequestedFreshActivation() throws Exception {
        try (var db = PlatformDatabase.open(root, PlatformDatabaseTest.TEST_PROTECTION)) {
            register(db, null);
            for (var state : List.of("STARTING", "RUNNING", "STOPPING", "UNKNOWN")) {
                sql(db, "UPDATE mod_registry SET last_boot_id=?,last_runtime_state=?,actual_version=?",
                    UUID.randomUUID(), state, VERSION);
                assertThrows(SQLException.class, ledger(db)::starting);
                assertEquals(state, value(db, "SELECT last_runtime_state FROM mod_registry"));
                assertEquals(VERSION, value(db, "SELECT actual_version FROM mod_registry"));
            }
            sql(db, "UPDATE mod_registry SET last_runtime_state='STOPPED',actual_version=NULL");
            var accepted = ledger(db);
            accepted.starting();
            accepted.closed(Optional.empty());
        }
    }

    @Test
    void rejectionBeforeAuthorDeliveryDoesNotMarkReadyDataUsedOrChangeIntent() throws Exception {
        try (var db = PlatformDatabase.open(root, PlatformDatabaseTest.TEST_PROTECTION)) {
            register(db, "1");
            var identity = new SchemaIdentity(OWNER, "1", UUID.randomUUID());
            var names = new SchemaCoordinates(identity);
            sql(db, "INSERT INTO mod_schema(mod_id,schema_version,generation,physical_name,database_user,state,created_at,validated_at) VALUES(?,'1',?,?,?,'READY',1,1)",
                OWNER, identity.generation(), names.physicalName(), names.databaseUser());
            var journal = new ModActivationLedger(db, OWNER, VERSION, Optional.empty());
            journal.rejectedBeforeStart(failure());
            state(db, "FAILED", null);
            assertEquals(false, value(db, "SELECT ever_used FROM mod_schema"));
            assertEquals(true, value(db, "SELECT enabled FROM mod_registry"));
            assertEquals(4L, value(db, "SELECT revision FROM mod_registry"));
            assertThrows(SQLException.class, journal::starting);
        }
    }
}
