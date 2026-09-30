package gold.debug.windowstolinux.app.db;

import static gold.debug.windowstolinux.app.db.ModVersionDeletionLedgerTest.*;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.sql.SQLException;
import java.util.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import gold.debug.windowstolinux.app.db.mod.*;
import gold.debug.windowstolinux.app.db.persistence.connection.PlatformDatabase;
import gold.debug.windowstolinux.app.db.execution.schema.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class ModReinstallationLedgerTest {
    @TempDir
    Path root;

    final ModVersionDeletionLedgerTest fixture = new ModVersionDeletionLedgerTest();

    static final String VERSION = "1.0.0", CONTENT = "c".repeat(64), ARCHIVE = "b".repeat(64);

    static final String SCOPE = "{\"format\":1,\"fixture\":\"original host replacement scope\"}";

    static final String CHECKPOINT = "{\"format\":1,\"fixture\":\"verified host observation\"}";

    @BeforeEach
    void setup() {
        fixture.root = root;
    }

    PlatformDatabase open() throws Exception {
        return fixture.open();
    }

    ModReinstallationLedger installed(PlatformDatabase db) throws Exception {
        fixture.owner(db);
        fixture.release(db, VERSION, null);
        sql(db, "UPDATE mod_registry SET selected_version=?,failure_code='OLD_FAILURE',failure_detail='preserved' WHERE mod_id=?",
                VERSION, OWNER);
        return new ModReinstallationLedger(db, root);
    }

    ModReinstallationLedger.Review review(ModReinstallationLedger ledger) throws Exception {
        return ledger.review(OWNER, VERSION, CONTENT, ARCHIVE);
    }

    UUID begin(ModReinstallationLedger ledger) throws Exception {
        return ledger.begin(review(ledger), SCOPE, CHECKPOINT);
    }

    void published(ModReinstallationLedger ledger, UUID id) throws Exception {
        ledger.advance(id, "VALIDATED", "STAGED", CHECKPOINT);
        ledger.advance(id, "STAGED", "OLD_SAVED", CHECKPOINT);
        ledger.advance(id, "OLD_SAVED", "FILES_PUBLISHED", CHECKPOINT);
    }

    String originalChoice(PlatformDatabase db) throws Exception {
        return value(db,
                "SELECT enabled||'/'||selected_version||'/'||selection_initialized||'/'||revision||'/'||disable_ack_revision||'/'||last_boot_id||'/'||last_runtime_state||'/'||failure_code||'/'||failure_detail FROM mod_registry WHERE mod_id=?",
                OWNER);
    }

    @Test
    void replacementCommitsOnlyProgramReadinessAndArchiveBytesWhileKeepingDataChoicesAndOtherReleases()
            throws Exception {
        try (var db = open()) {
            var ledger = installed(db);
            fixture.release(db, "2.0.0", "7");
            var data = fixture.schema(db, "7");
            sql(db, "UPDATE mod_version SET schema_version='7',manifest_json=? WHERE version=?", "{\"id\":\"" + OWNER
                    + "\",\"version\":\"" + VERSION + "\",\"schemaVersion\":\"7\",\"dependencies\":[]}", VERSION);
            sql(db, "CREATE VIEW app.repair_shared_view AS SELECT * FROM " + new SchemaCoordinates(data).physicalName()
                    + ".items");
            String choice = originalChoice(db);
            String schema = value(db,
                    "SELECT state||'/'||generation||'/'||ever_used||'/'||validation_digest FROM mod_schema WHERE mod_id=?",
                    OWNER);
            var id = begin(ledger);
            assertEquals("RUNNING/VALIDATED", phase(db, id));
            assertEquals("REINSTALLING", value(db, "SELECT state FROM mod_version WHERE version=?", VERSION));
            assertEquals(SCOPE, ledger.execution(id).files());
            assertEquals(CHECKPOINT, ledger.execution(id).checkpoint());
            assertThrows(SQLException.class, () -> review(ledger));
            assertThrows(SQLException.class, () -> new ModDeletionLedger(db, root).review(OWNER));
            published(ledger, id);
            ledger.registered(id, CHECKPOINT);
            assertEquals("READY", value(db, "SELECT state FROM mod_version WHERE version=?", VERSION));
            assertEquals(ARCHIVE, value(db, "SELECT archive_sha256 FROM mod_version WHERE version=?", VERSION));
            assertEquals("1", value(db, "SELECT installed_at FROM mod_version WHERE version=?", VERSION));
            ledger.advance(id, "REGISTERED", "CLEANUP", CHECKPOINT);
            assertTrue(ledger.finish(id));
            assertFalse(ledger.finish(id));
            assertEquals(choice, originalChoice(db));
            assertEquals(schema, value(db,
                    "SELECT state||'/'||generation||'/'||ever_used||'/'||validation_digest FROM mod_schema WHERE mod_id=?",
                    OWNER));
            assertEquals("retained data", value(db, "SELECT value_text FROM app.repair_shared_view"));
            assertEquals("2", value(db, "SELECT COUNT(*) FROM mod_version"));
            assertTrue(Files.exists(fixture.program("2.0.0").resolve("main.jar")));
            assertEquals("1", value(db,
                    "SELECT COUNT(*) FROM runtime_log WHERE event_code='MOD_REINSTALLED' AND outcome='SUCCEEDED' AND operation_id=?",
                    id));
        }
    }

    @Test
    void disabledDraftDoesNotReplaceActualAcknowledgedStopAndSameVersionContentCannotChange() throws Exception {
        try (var db = open()) {
            var ledger = installed(db);
            for (String change : List.of("enabled=TRUE", "disable_ack_revision=NULL", "disable_ack_revision=6",
                    "last_runtime_state='UNKNOWN'", "last_runtime_state='RUNNING',actual_version='1.0.0'",
                    "last_boot_id=NULL")) {
                sql(db, "UPDATE mod_registry SET " + change + " WHERE mod_id=?", OWNER);
                assertThrows(SQLException.class, () -> review(ledger), change);
                sql(db, "UPDATE mod_registry SET enabled=FALSE,disable_ack_revision=7,last_runtime_state='STOPPED',actual_version=NULL,last_boot_id=? WHERE mod_id=?",
                        db.bootId(), OWNER);
            }
            assertThrows(SQLException.class, () -> ledger.review(OWNER, VERSION, "d".repeat(64), ARCHIVE));
            assertThrows(SQLException.class, () -> ledger.review(OWNER, "9.0.0", CONTENT, ARCHIVE));
            assertEquals("0", value(db, "SELECT COUNT(*) FROM mod_operation"));
            assertEquals("READY", value(db, "SELECT state FROM mod_version"));
        }
    }

    @Test
    void staleForeignAndRepeatedReviewsCannotReapplyAcceptance() throws Exception {
        try (var db = open()) {
            var ledger = installed(db);
            var stale = review(ledger);
            sql(db, "UPDATE mod_registry SET selected_version=NULL WHERE mod_id=?", OWNER);
            assertThrows(SQLException.class, () -> ledger.begin(stale, SCOPE, CHECKPOINT));
            assertThrows(SQLException.class, () -> ledger.begin(stale, SCOPE, CHECKPOINT));
            var other = new ModReinstallationLedger(db, root);
            assertThrows(SQLException.class, () -> ledger.begin(review(other), SCOPE, CHECKPOINT));
            var fresh = review(ledger);
            ledger.begin(fresh, SCOPE, CHECKPOINT);
            assertThrows(SQLException.class, () -> ledger.begin(fresh, SCOPE, CHECKPOINT));
            assertEquals("1", value(db, "SELECT COUNT(*) FROM mod_operation"));
        }
    }

    @Test
    void classSourcesAndOutsidePathsDoNotBecomeRepairTargets() throws Exception {
        try (var db = open()) {
            var ledger = installed(db);
            sql(db, "UPDATE mod_version SET schema_version='1'");
            assertThrows(SQLException.class, () -> review(ledger));
            sql(db, "UPDATE mod_version SET schema_version=NULL");
            sql(db, "UPDATE mod_version SET origin_kind='CLASS'");
            assertThrows(SQLException.class, () -> review(ledger));
            sql(db, "UPDATE mod_version SET origin_kind='LOCAL',install_path=?",
                    root.resolve("source-output").toString());
            assertThrows(SQLException.class, () -> review(ledger));
            assertEquals("0", value(db, "SELECT COUNT(*) FROM mod_operation"));
            assertTrue(Files.exists(fixture.program(VERSION).resolve("main.jar")));
        }
    }

    @Test
    void ownedTasksCrossOwnerReferencesAndCompetingMaintenanceBlockRepair() throws Exception {
        try (var db = open()) {
            var ledger = installed(db);
            UUID task = UUID.randomUUID();
            sql(db, "INSERT INTO platform_task(task_id,owner_id,owner_version,task_type,state,cancellable,boot_id,request_json,recovery_json,archive_log_id,progress_text,started_at) VALUES(?,?,'1.0.0','fixture','UNKNOWN',FALSE,?,'{}','{}',?,'',1)",
                    task, OWNER, db.bootId(), UUID.randomUUID().toString());
            assertThrows(SQLException.class, () -> review(ledger));
            sql(db, "UPDATE platform_task SET owner_id='other.owner' WHERE task_id=?", task);
            UUID resource = UUID.randomUUID();
            sql(db, "INSERT INTO platform_task_resource VALUES(?,?,'MOD',?,'other.owner','SHARED',1,NULL,'{}')",
                    resource, task, OWNER);
            assertThrows(SQLException.class, () -> review(ledger));
            sql(db, "DELETE FROM platform_task_resource WHERE resource_id=?", OWNER);
            UUID competing = UUID.randomUUID();
            sql(db, "INSERT INTO mod_operation(operation_id,mod_id,operation_type,state,phase,request_json,recovery_json,archive_log_id,created_at,updated_at) VALUES(?,?,'INSTALL','UNKNOWN','STAGED','{}','{}',?,1,1)",
                    competing, OWNER, UUID.randomUUID().toString());
            assertThrows(SQLException.class, () -> review(ledger));
            sql(db, "DELETE FROM mod_operation WHERE operation_id=?", competing);
            assertNotNull(review(ledger));
        }
    }

    @Test
    void actualOwnerAndTemporaryReaderConnectionsBlockRepairWithoutDroppingDataOrGrants() throws Exception {
        try (var db = open()) {
            var ledger = installed(db);
            var data = fixture.schema(db, "1");
            try (var scope = new SchemaAccessScope(db, data, UUID.randomUUID(), false);
                    var c = scope.openConnection()) {
                assertThrows(SQLException.class, () -> review(ledger));
            }
            try (var scope = new SchemaAccessScope(db, data, UUID.randomUUID(), true); var c = scope.openConnection()) {
                assertThrows(SQLException.class, () -> review(ledger));
            }
            sql(db, "UPDATE mod_schema SET state='FAILED',ever_used=FALSE,validated_at=NULL,validation_digest=NULL WHERE generation=?",
                    data.generation());
            assertNotNull(review(ledger));
            assertEquals("FAILED", value(db, "SELECT state FROM mod_schema WHERE generation=?", data.generation()));
            assertEquals("retained data",
                    value(db, "SELECT value_text FROM " + new SchemaCoordinates(data).physicalName() + ".items"));
        }
    }

    @Test
    void failedAcceptanceAndRegistrationRollBackBothWorkingStateAndCatalogMutations() throws Exception {
        try (var db = open()) {
            var ledger = installed(db);
            sql(db, "ALTER TABLE mod_version ADD CONSTRAINT reject_repair CHECK(state<>'REINSTALLING')");
            assertThrows(SQLException.class, () -> begin(ledger));
            assertEquals("0", value(db, "SELECT COUNT(*) FROM mod_operation"));
            assertEquals("READY", value(db, "SELECT state FROM mod_version"));
            sql(db, "ALTER TABLE mod_version DROP CONSTRAINT reject_repair");
            var id = begin(ledger);
            published(ledger, id);
            sql(db, "ALTER TABLE mod_operation ADD CONSTRAINT reject_registered CHECK(phase<>'REGISTERED')");
            assertThrows(SQLException.class, () -> ledger.registered(id, CHECKPOINT));
            assertEquals("UNKNOWN/FILES_PUBLISHED", phase(db, id));
            assertEquals("REINSTALLING", value(db, "SELECT state FROM mod_version"));
            assertNull(value(db, "SELECT archive_sha256 FROM mod_version"));
            sql(db, "ALTER TABLE mod_operation DROP CONSTRAINT reject_registered");
            assertEquals("FILES_PUBLISHED", ledger.resume(id));
            ledger.registered(id, CHECKPOINT);
            assertEquals("READY", value(db, "SELECT state FROM mod_version"));
        }
    }

    @Test
    void originalScopeAndCheckpointSurviveReopenWithoutAutomaticPublicationOrRollback() throws Exception {
        UUID id;
        String original;
        String archive;
        UUID oldBoot;
        try (var db = open()) {
            var ledger = installed(db);
            id = begin(ledger);
            oldBoot = db.bootId();
            ledger.checkpoint(id, "VALIDATED", "{\"originalRoot\":\"fixture native identity\"}");
            ledger.advance(id, "VALIDATED", "STAGED", CHECKPOINT);
            original = value(db, "SELECT request_json FROM mod_operation WHERE operation_id=?", id);
            archive = value(db, "SELECT archive_log_id FROM mod_operation WHERE operation_id=?", id);
        }
        try (var db = open()) {
            assertNotEquals(oldBoot, db.bootId());
            var ledger = new ModReinstallationLedger(db, root);
            assertEquals("UNKNOWN/STAGED", phase(db, id));
            assertEquals(original, value(db, "SELECT request_json FROM mod_operation WHERE operation_id=?", id));
            assertEquals(CHECKPOINT, ledger.execution(id).checkpoint());
            assertThrows(SQLException.class, () -> ledger.advance(id, "STAGED", "OLD_SAVED", CHECKPOINT));
            assertEquals("STAGED", ledger.resume(id));
            ledger.advance(id, "STAGED", "OLD_SAVED", CHECKPOINT);
            ledger.advance(id, "OLD_SAVED", "FILES_PUBLISHED", CHECKPOINT);
            ledger.registered(id, CHECKPOINT);
            ledger.advance(id, "REGISTERED", "CLEANUP", CHECKPOINT);
            ledger.finish(id);
            assertEquals(archive, value(db, "SELECT log_id FROM runtime_log WHERE operation_id=?", id));
        }
    }

    @Test
    void explicitRestorationKeepsOriginalArchiveMetadataAndReportsFailedRepairInsteadOfSuccess() throws Exception {
        try (var db = open()) {
            var ledger = installed(db);
            var id = begin(ledger);
            published(ledger, id);
            String choice = originalChoice(db);
            ledger.beginRestore(id, "FILES_PUBLISHED", CHECKPOINT);
            assertEquals("RUNNING/RESTORING", phase(db, id));
            assertEquals("REINSTALLING", value(db, "SELECT state FROM mod_version"));
            ledger.unconfirmed(id);
            assertEquals("RESTORING", ledger.resume(id));
            ledger.restored(id, CHECKPOINT);
            ledger.finish(id);
            assertEquals("READY", value(db, "SELECT state FROM mod_version"));
            assertNull(value(db, "SELECT archive_sha256 FROM mod_version"));
            assertEquals(choice, originalChoice(db));
            assertEquals("FAILED", value(db,
                    "SELECT outcome FROM runtime_log WHERE event_code='MOD_REINSTALL_RESTORED' AND operation_id=?",
                    id));
            assertEquals("0", value(db, "SELECT COUNT(*) FROM runtime_log WHERE event_code='MOD_REINSTALLED'"));
        }
    }

    @Test
    void successfulRegistrationCannotBeRolledBackAndStagingMustDisappearBeforeArchive() throws Exception {
        try (var db = open()) {
            var ledger = installed(db);
            var id = begin(ledger);
            published(ledger, id);
            ledger.registered(id, CHECKPOINT);
            assertThrows(SQLException.class, () -> ledger.beginRestore(id, "REGISTERED", CHECKPOINT));
            Path stage = root.resolve(".mod-staging").resolve(id.toString());
            Files.createDirectories(stage);
            Path retained = Files.writeString(stage.resolve("old-program-fixture"), "retained old bytes");
            ledger.advance(id, "REGISTERED", "CLEANUP", CHECKPOINT);
            assertThrows(SQLException.class, () -> ledger.finish(id));
            assertEquals("UNKNOWN/CLEANUP", phase(db, id));
            assertEquals("retained old bytes", Files.readString(retained));
            Files.delete(retained);
            Files.delete(stage);
            ledger.resume(id);
            assertTrue(ledger.finish(id));
        }
    }

    @Test
    void catalogDriftMalformedEvidenceAndPhaseSkippingNeverAuthorizeFurtherEffects() throws Exception {
        try (var db = open()) {
            var ledger = installed(db);
            var id = begin(ledger);
            assertThrows(SQLException.class, () -> ledger.advance(id, "VALIDATED", "FILES_PUBLISHED", CHECKPOINT));
            assertEquals("RUNNING/VALIDATED", phase(db, id));
            ledger.unconfirmed(id);
            String original = value(db, "SELECT request_json FROM mod_operation WHERE operation_id=?", id);
            var mapper = new ObjectMapper();
            var request = (ObjectNode) mapper.readTree(original);
            ((ObjectNode) request.get("release")).put("path", root.resolve("unrelated").toString());
            sql(db, "UPDATE mod_operation SET request_json=? WHERE operation_id=?", request.toString(), id);
            assertThrows(SQLException.class, () -> ledger.resume(id));
            sql(db, "UPDATE mod_operation SET request_json=? WHERE operation_id=?", original, id);
            sql(db, "UPDATE mod_registry SET revision=8,disable_ack_revision=8 WHERE mod_id=?", OWNER);
            assertThrows(SQLException.class, () -> ledger.resume(id));
            assertEquals("UNKNOWN/VALIDATED", phase(db, id));
            assertTrue(Files.exists(fixture.program(VERSION).resolve("main.jar")));
        }
    }

    @Test
    void failedArchiveKeepsOriginalReceiptAndLaterFinishDoesNotReinstallAgain() throws Exception {
        try (var db = open()) {
            var ledger = installed(db);
            var id = begin(ledger);
            published(ledger, id);
            ledger.registered(id, CHECKPOINT);
            ledger.advance(id, "REGISTERED", "CLEANUP", CHECKPOINT);
            String archive = value(db, "SELECT archive_log_id FROM mod_operation WHERE operation_id=?", id);
            sql(db, "ALTER TABLE runtime_log ADD CONSTRAINT reject_repair_archive CHECK(event_code<>'MOD_REINSTALLED')");
            assertThrows(SQLException.class, () -> ledger.finish(id));
            assertEquals("UNKNOWN/CLEANUP", phase(db, id));
            assertEquals("0", value(db, "SELECT COUNT(*) FROM runtime_log WHERE operation_id=?", id));
            sql(db, "ALTER TABLE runtime_log DROP CONSTRAINT reject_repair_archive");
            ledger.resume(id);
            assertTrue(ledger.finish(id));
            assertEquals(archive, value(db, "SELECT log_id FROM runtime_log WHERE operation_id=?", id));
            Files.writeString(fixture.program(VERSION).resolve("main.jar"), "later content");
            assertFalse(ledger.finish(id));
            assertThrows(SQLException.class, () -> ledger.resume(id));
            assertEquals("later content", Files.readString(fixture.program(VERSION).resolve("main.jar")));
        }
    }
}
