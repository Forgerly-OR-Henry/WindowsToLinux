package gold.debug.windowstolinux.app.db;

import static gold.debug.windowstolinux.app.db.ModActivationLedgerTest.*;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.*;

import gold.debug.windowstolinux.app.db.mod.ModRecoveryLedger;
import gold.debug.windowstolinux.app.db.persistence.connection.PlatformDatabase;
import gold.debug.windowstolinux.app.db.schema.SchemaCoordinates;
import gold.debug.windowstolinux.app.db.task.*;
import gold.debug.windowstolinux.appapi.db.SchemaIdentity;
import gold.debug.windowstolinux.appapi.task.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ModRecoveryLedgerTest {
    @TempDir
    Path root;

    static final String PROVIDER = "example.provider";

    PlatformDatabase open() throws Exception {
        return PlatformDatabase.open(root, PlatformDatabaseTest.TEST_PROTECTION);
    }

    TaskRecoveryGraph graph(PlatformDatabase db) throws Exception {
        register(db, null);
        sql(db, "INSERT INTO mod_registry(mod_id,updated_at) VALUES(?,1)", PROVIDER);
        sql(db, "INSERT INTO mod_version(mod_id,version,install_path,content_sha256,manifest_json,origin_kind,installed_at,state) VALUES(?,?,'fixture',?,'{}','CLASS',1,'READY')",
                PROVIDER, VERSION, "1".repeat(64));
        sql(db, "UPDATE mod_registry SET last_runtime_state='UNKNOWN',actual_version=?,last_boot_id=?", VERSION,
                UUID.randomUUID());
        sql(db, "UPDATE mod_registry SET enabled=FALSE,selected_version='2.0.0' WHERE mod_id=?", OWNER);
        return new TaskRecoveryGraph(OWNER,
                List.of(new TaskRecoveryGraph.Release(OWNER, VERSION, "0".repeat(64), Optional.empty()),
                        new TaskRecoveryGraph.Release(PROVIDER, VERSION, "1".repeat(64), Optional.empty())));
    }

    long count(PlatformDatabase db, String table) throws Exception {
        return ((Number) value(db, "SELECT COUNT(*) FROM " + table)).longValue();
    }

    @Test
    void retainsAllMembersAndPreservesSelectionUntilActualClosure() throws Exception {
        try (var db = open()) {
            var graph = graph(db);
            var ledger = new ModRecoveryLedger(db);
            var retained = ledger.retain(graph);
            assertEquals(2, count(db, "mod_operation"));
            assertEquals(List.of(retained), ledger.pending());
            assertEquals("UNKNOWN",
                    value(db, "SELECT last_runtime_state FROM mod_registry WHERE mod_id='" + OWNER + "'"));
            assertThrows(SQLException.class, () -> ledger.retain(graph));
            assertThrows(SQLException.class, () -> ledger.resume(retained));
            assertThrows(SQLException.class, () -> ledger.archive(retained));
            var released = ledger.released(retained);
            assertEquals(released, ledger.released(retained));
            ledger.archive(released);
            ledger.archive(released);
            assertTrue(ledger.pending().isEmpty());
            assertEquals(2, count(db, "runtime_log WHERE event_code='MOD_RECOVERY_FINISHED'"));
            assertEquals(2, count(db, "mod_registry WHERE actual_version IS NULL AND last_runtime_state='STOPPED'"));
            assertEquals(false, value(db, "SELECT enabled FROM mod_registry WHERE mod_id='" + OWNER + "'"));
            assertEquals("2.0.0", value(db, "SELECT selected_version FROM mod_registry WHERE mod_id='" + OWNER + "'"));
            assertEquals(4L, value(db, "SELECT revision FROM mod_registry WHERE mod_id='" + OWNER + "'"));
            assertEquals(2L, value(db, "SELECT disable_ack_revision FROM mod_registry WHERE mod_id='" + OWNER + "'"));
        }
    }

    @Test
    void laterBootResumesSameGraphAndSummaryIdentitiesWithoutOriginalTask() throws Exception {
        ModRecoveryLedger.Recovery retained;
        try (var db = open()) {
            retained = new ModRecoveryLedger(db).retain(graph(db));
        }
        try (var db = open()) {
            var ledger = new ModRecoveryLedger(db);
            assertEquals(retained, ledger.pending().getFirst());
            assertEquals(2, count(db, "mod_operation WHERE state='UNKNOWN'"));
            var resumed = ledger.resume(retained);
            assertEquals(retained.id(), resumed.id());
            assertEquals(retained.graph(), resumed.graph());
            assertEquals(retained.rows().stream().map(ModRecoveryLedger.Row::operation).toList(),
                    resumed.rows().stream().map(ModRecoveryLedger.Row::operation).toList());
            assertEquals(retained.rows().stream().map(ModRecoveryLedger.Row::archive).toList(),
                    resumed.rows().stream().map(ModRecoveryLedger.Row::archive).toList());
            assertNotEquals(retained, resumed);
            assertThrows(SQLException.class, () -> ledger.released(retained));
            ledger.archive(ledger.released(resumed));
        }
    }

    @Test
    void admissionRollbackDoesNotLeaveHalfAGraphAndRejectsCurrentBoot() throws Exception {
        try (var db = open()) {
            var graph = graph(db);
            var ledger = new ModRecoveryLedger(db);
            sql(db, "UPDATE mod_version SET content_sha256=? WHERE mod_id=?", "2".repeat(64), PROVIDER);
            assertThrows(SQLException.class, () -> ledger.retain(graph));
            assertEquals(0, count(db, "mod_operation"));
            sql(db, "UPDATE mod_version SET content_sha256=? WHERE mod_id=?", "1".repeat(64), PROVIDER);
            sql(db, "UPDATE mod_registry SET last_boot_id=? WHERE mod_id=?", db.bootId(), PROVIDER);
            assertThrows(SQLException.class, () -> ledger.retain(graph));
            assertEquals(0, count(db, "mod_operation"));
        }
    }

    @Test
    void exactSchemaGenerationIsRequiredBeforeRetainingOrResuming() throws Exception {
        ModRecoveryLedger.Recovery retained;
        var schema = new SchemaIdentity(PROVIDER, "1", UUID.randomUUID());
        var coordinates = new SchemaCoordinates(schema);
        try (var db = open()) {
            var initial = graph(db);
            var graph = new TaskRecoveryGraph(OWNER, List.of(initial.release(OWNER),
                    new TaskRecoveryGraph.Release(PROVIDER, VERSION, "1".repeat(64), Optional.of(schema))));
            sql(db, "UPDATE mod_version SET schema_version='1' WHERE mod_id=?", PROVIDER);
            var ledger = new ModRecoveryLedger(db);
            assertThrows(SQLException.class, () -> ledger.retain(graph));
            assertEquals(0, count(db, "mod_operation"));
            sql(db, "INSERT INTO mod_schema(mod_id,schema_version,generation,physical_name,database_user,state,created_at,validated_at) VALUES(?,'1',?,?,?,'READY',1,1)",
                    PROVIDER, schema.generation(), coordinates.physicalName(), coordinates.databaseUser());
            retained = ledger.retain(graph);
        }
        try (var db = open()) {
            var ledger = new ModRecoveryLedger(db);
            sql(db, "UPDATE mod_schema SET generation=?", UUID.randomUUID());
            assertThrows(SQLException.class, () -> ledger.resume(retained));
            assertEquals(retained, ledger.pending().getFirst());
            assertEquals(2, count(db, "mod_operation WHERE state='UNKNOWN'"));
        }
    }

    @Test
    void retainedTaskOrDependencyLeasePreventsClosureReceipt() throws Exception {
        try (var db = open()) {
            var ledger = new ModRecoveryLedger(db);
            var retained = ledger.retain(graph(db));
            var tasks = new TaskLedger(db);
            UUID task = UUID.randomUUID();
            tasks.enqueue(task, OWNER, VERSION, new TaskRequest("fixture", "Fixture", List.of(), false),
                    List.of(new TaskResource("MOD", OWNER, OWNER, false)));
            assertThrows(SQLException.class, () -> ledger.released(retained));
            assertTrue(tasks.tryStart(task));
            var dependency = tasks.retain(task, new TaskResource("MOD", PROVIDER, PROVIDER, false));
            assertThrows(SQLException.class, () -> ledger.released(retained));
            tasks.release(task, dependency);
            tasks.completeAndArchive(task, Outcome.SUCCEEDED, "fixture", false, () -> {
            });
            ledger.archive(ledger.released(retained));
            assertEquals(0, count(db, "mod_operation"));
        }
    }

    @Test
    void failedArchiveRetainsProofAndReopensForMetadataOnlyCompletion() throws Exception {
        ModRecoveryLedger.Recovery verified;
        try (var db = open()) {
            var ledger = new ModRecoveryLedger(db);
            verified = ledger.released(ledger.retain(graph(db)));
            sql(db, "ALTER TABLE runtime_log ADD CONSTRAINT fixture_archive CHECK(category<>'mod_operation')");
            assertThrows(SQLException.class, () -> ledger.archive(verified));
            assertEquals(0, count(db, "runtime_log"));
            assertEquals(2, count(db, "mod_operation WHERE phase='VERIFIED' AND outcome='SUCCEEDED'"));
            assertEquals(2, count(db, "mod_registry WHERE last_runtime_state='UNKNOWN'"));
        }
        try (var db = open()) {
            var ledger = new ModRecoveryLedger(db);
            assertEquals(verified, ledger.pending().getFirst());
            assertThrows(SQLException.class, () -> ledger.resume(verified));
            sql(db, "ALTER TABLE runtime_log DROP CONSTRAINT fixture_archive");
            ledger.archive(verified);
            assertEquals(2, count(db, "runtime_log WHERE event_code='MOD_RECOVERY_FINISHED'"));
        }
    }

    @Test
    void changedRegistryOrAnotherOperationCannotBeOverwrittenByOldReceipt() throws Exception {
        try (var db = open()) {
            var ledger = new ModRecoveryLedger(db);
            var retained = ledger.retain(graph(db));
            sql(db, "UPDATE mod_registry SET revision=revision+1 WHERE mod_id=?", PROVIDER);
            assertThrows(SQLException.class, () -> ledger.released(retained));
            assertEquals(2, count(db, "mod_operation WHERE phase='RECOVERING'"));
            sql(db, "UPDATE mod_registry SET revision=revision-1 WHERE mod_id=?", PROVIDER);
            var verified = ledger.released(retained);
            sql(db, "UPDATE mod_registry SET actual_version='unrelated' WHERE mod_id=?", PROVIDER);
            assertThrows(SQLException.class, () -> ledger.archive(verified));
            assertEquals(0, count(db, "runtime_log"));
            assertEquals(2, count(db, "mod_operation"));
            assertEquals("unrelated",
                    value(db, "SELECT actual_version FROM mod_registry WHERE mod_id='" + PROVIDER + "'"));
        }
    }

    @Test
    void incompleteOrMalformedGroupsAreNeverAdopted() throws Exception {
        try (var db = open()) {
            var ledger = new ModRecoveryLedger(db);
            var retained = ledger.retain(graph(db));
            var row = retained.rows().getFirst();
            for (var malformed : List.of("{}", row.request() + " {}",
                    row.request().replace("\"formatVersion\":1", "\"formatVersion\":1,\"formatVersion\":1"),
                    row.request().replace("MOD_SESSION", "SCHEMA"))) {
                sql(db, "UPDATE mod_operation SET request_json=? WHERE operation_id=?", malformed, row.operation());
                assertThrows(SQLException.class, ledger::pending);
                assertThrows(SQLException.class, () -> ledger.released(retained));
            }
            sql(db, "UPDATE mod_operation SET request_json=? WHERE operation_id=?", row.request(), row.operation());
            sql(db, "DELETE FROM mod_operation WHERE operation_id=?", row.operation());
            assertThrows(SQLException.class, ledger::pending);
            assertEquals(1, count(db, "mod_operation"));
        }
    }
}
