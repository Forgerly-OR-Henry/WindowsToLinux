package gold.debug.windowstolinux.app.db;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.sql.*;
import java.util.*;

import gold.debug.windowstolinux.app.db.mod.ModVersionDeletionLedger;
import gold.debug.windowstolinux.app.db.schema.*;
import gold.debug.windowstolinux.appapi.db.SchemaIdentity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ModVersionDeletionLedgerTest {
    @TempDir
    Path root;

    static final String OWNER = "sample.delete";

    PlatformDatabase open() throws Exception {
        var db = PlatformDatabase.open(root.resolve("db"), PlatformDatabaseTest.TEST_PROTECTION);
        db.started();
        return db;
    }

    void owner(PlatformDatabase db) throws Exception {
        sql(db, "INSERT INTO mod_registry(mod_id,selection_initialized,revision,disable_ack_revision,last_boot_id,last_runtime_state,updated_at) VALUES(?,TRUE,7,7,?,'STOPPED',1)",
                OWNER, db.bootId());
    }

    Path program(String version) {
        return root.resolve("mod").resolve(OWNER).resolve(version);
    }

    void release(PlatformDatabase db, String version, String schema) throws Exception {
        Path target = program(version);
        Files.createDirectories(target);
        Files.writeString(target.resolve("main.jar"), "independent program fixture");
        sql(db, "INSERT INTO mod_version(mod_id,version,install_path,content_sha256,schema_version,manifest_json,origin_kind,installed_at,state) VALUES(?,?,?,?,?,?,'LOCAL',1,'READY')",
                OWNER, version, target.toString(), "c".repeat(64), schema,
                "{\"id\":\"" + OWNER + "\",\"version\":\"" + version + "\",\"dependencies\":[]}");
    }

    SchemaIdentity schema(PlatformDatabase db, String version) throws Exception {
        var identity = new SchemaIdentity(OWNER, version, UUID.randomUUID());
        var coordinates = new SchemaCoordinates(identity);
        sql(db, "INSERT INTO mod_schema(mod_id,schema_version,generation,physical_name,database_user,state,created_at) VALUES(?,?,?,?,?,'PREPARING',1)",
                OWNER, version, identity.generation(), coordinates.physicalName(), coordinates.databaseUser());
        new SchemaProvisioner(db).create(identity);
        sql(db, "CREATE TABLE " + coordinates.physicalName() + ".items(id INT PRIMARY KEY, value_text VARCHAR)");
        sql(db, "INSERT INTO " + coordinates.physicalName() + ".items VALUES(1,'retained data')");
        sql(db, "UPDATE mod_schema SET state='READY',ever_used=TRUE,validated_at=1,validation_digest=? WHERE generation=?",
                "a".repeat(64), identity.generation());
        return identity;
    }

    void removeProgram(String version) throws Exception {
        Files.delete(program(version).resolve("main.jar"));
        Files.delete(program(version));
    }

    void complete(ModVersionDeletionLedger ledger, UUID id) throws Exception {
        ledger.programRemoved(id);
        ledger.removeData(id);
        ledger.removeReferences(id);
        ledger.removeRegistry(id);
        ledger.cleanup(id);
        assertTrue(ledger.finish(id));
    }

    static void sql(PlatformDatabase db, String sql, Object... args) throws Exception {
        try (var c = db.openConnection(); var p = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++)
                p.setObject(i + 1, args[i]);
            p.execute();
        }
    }

    static String value(PlatformDatabase db, String sql, Object... args) throws Exception {
        try (var c = db.openConnection(); var p = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++)
                p.setObject(i + 1, args[i]);
            try (var r = p.executeQuery()) {
                assertTrue(r.next());
                return r.getString(1);
            }
        }
    }

    static String phase(PlatformDatabase db, UUID id) throws Exception {
        return value(db, "SELECT state || '/' || phase FROM mod_operation WHERE operation_id=?", id);
    }

    @Test
    void unselectedRetainedReleaseProtectsSharedSchemaAndLastDeletionClearsSelectionHistorySafely() throws Exception {
        try (var db = open()) {
            owner(db);
            release(db, "1.0.0", "2");
            release(db, "1.1.0", "2");
            var data = schema(db, "2");
            sql(db, "UPDATE mod_registry SET selected_version='1.0.0' WHERE mod_id=?", OWNER);
            var ledger = new ModVersionDeletionLedger(db, root);
            var review = ledger.review(OWNER, "1.0.0");
            assertEquals(List.of("1.1.0"), review.retainedSchemaVersions());
            assertTrue(review.removedSchema().isEmpty());
            var id = ledger.begin(review, "{}");
            removeProgram("1.0.0");
            complete(ledger, id);
            assertEquals("retained data",
                    value(db, "SELECT value_text FROM " + new SchemaCoordinates(data).physicalName() + ".items"));
            assertEquals("TRUE", value(db, "SELECT selection_initialized FROM mod_registry WHERE mod_id=?", OWNER));
            assertNull(value(db, "SELECT selected_version FROM mod_registry WHERE mod_id=?", OWNER));
            var last = ledger.review(OWNER, "1.1.0");
            assertEquals(Optional.of(data), last.removedSchema());
            id = ledger.begin(last, "{}");
            removeProgram("1.1.0");
            complete(ledger, id);
            assertEquals("0", value(db, "SELECT COUNT(*) FROM mod_schema WHERE mod_id=?", OWNER));
            assertEquals("0", value(db, "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=?",
                    new SchemaCoordinates(data).physicalName()));
            assertEquals("0", value(db, "SELECT COUNT(*) FROM information_schema.users WHERE user_name=?",
                    new SchemaCoordinates(data).databaseUser()));
            assertEquals("2", value(db,
                    "SELECT COUNT(*) FROM runtime_log WHERE event_code='MOD_VERSION_DELETED' AND subject_mod_id=?",
                    OWNER));
            assertFalse(ledger.finish(id));
            assertEquals("FALSE", value(db, "SELECT enabled FROM mod_registry WHERE mod_id=?", OWNER));
        }
    }

    @Test
    void refusesEnabledPendingDisabledOrUnacknowledgedOwnerEvenWhenDeletingOldVersion() throws Exception {
        try (var db = open()) {
            owner(db);
            release(db, "1.0.0", null);
            release(db, "2.0.0", null);
            sql(db, "UPDATE mod_registry SET selected_version='2.0.0' WHERE mod_id=?", OWNER);
            var ledger = new ModVersionDeletionLedger(db, root);
            for (String change : List.of("enabled=TRUE", "disable_ack_revision=NULL", "disable_ack_revision=6",
                    "last_runtime_state='RUNNING',actual_version='2.0.0'", "last_runtime_state='UNKNOWN'",
                    "last_boot_id=NULL")) {
                sql(db, "UPDATE mod_registry SET " + change + " WHERE mod_id=?", OWNER);
                assertThrows(SQLException.class, () -> ledger.review(OWNER, "1.0.0"), change);
                sql(db, "UPDATE mod_registry SET enabled=FALSE,disable_ack_revision=7,last_runtime_state='STOPPED',actual_version=NULL,last_boot_id=? WHERE mod_id=?",
                        db.bootId(), OWNER);
            }
            assertEquals("0", value(db, "SELECT COUNT(*) FROM mod_operation"));
            assertTrue(Files.exists(program("1.0.0").resolve("main.jar")));
        }
    }

    @Test
    void changedReviewsAreRejectedWithoutPendingStateAndCannotBeReplayed() throws Exception {
        try (var db = open()) {
            owner(db);
            release(db, "1.0.0", "1");
            schema(db, "1");
            var ledger = new ModVersionDeletionLedger(db, root);
            var review = ledger.review(OWNER, "1.0.0");
            release(db, "1.0.1", "1");
            assertThrows(SQLException.class, () -> ledger.begin(review, "{}"));
            assertThrows(SQLException.class, () -> ledger.begin(review, "{}"));
            var foreign = new ModVersionDeletionLedger(db, root).review(OWNER, "1.0.0");
            assertThrows(SQLException.class, () -> ledger.begin(foreign, "{}"));
            assertEquals("0", value(db, "SELECT COUNT(*) FROM mod_operation"));
            assertEquals("READY", value(db, "SELECT state FROM mod_version WHERE version='1.0.0'"));
        }
    }

    @Test
    void physicalProgramAbsenceIsRequiredAndNoPhaseCanSkipIt() throws Exception {
        try (var db = open()) {
            owner(db);
            release(db, "1.0.0", "1");
            var data = schema(db, "1");
            var ledger = new ModVersionDeletionLedger(db, root);
            var id = ledger.begin(ledger.review(OWNER, "1.0.0"), "{}");
            assertThrows(SQLException.class, () -> ledger.programRemoved(id));
            assertEquals("UNKNOWN/QUIESCENT", phase(db, id));
            assertEquals("QUIESCENT", ledger.resume(id));
            assertThrows(SQLException.class, () -> ledger.removeData(id));
            assertEquals("UNKNOWN/QUIESCENT", phase(db, id));
            assertEquals("retained data",
                    value(db, "SELECT value_text FROM " + new SchemaCoordinates(data).physicalName() + ".items"));
            assertEquals("1", value(db, "SELECT COUNT(*) FROM mod_schema WHERE state='DELETE_PENDING'"));
        }
    }

    @Test
    void ddlCompletionSurvivesCheckpointFailureAndExplicitRebootResumeWithoutReplayingProgramWork() throws Exception {
        UUID id;
        SchemaIdentity data;
        UUID firstBoot;
        try (var db = open()) {
            owner(db);
            release(db, "1.0.0", "1");
            data = schema(db, "1");
            firstBoot = db.bootId();
            var ledger = new ModVersionDeletionLedger(db, root);
            id = ledger.begin(ledger.review(OWNER, "1.0.0"), "{}");
            removeProgram("1.0.0");
            ledger.programRemoved(id);
            sql(db, "ALTER TABLE mod_operation ADD CONSTRAINT fixture_checkpoint CHECK(phase<>'DATA_REMOVED')");
            assertThrows(SQLException.class, () -> ledger.removeData(id));
            assertEquals("UNKNOWN/PROGRAM_REMOVED", phase(db, id));
            assertEquals("0", value(db, "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=?",
                    new SchemaCoordinates(data).physicalName()));
            assertEquals("1", value(db, "SELECT COUNT(*) FROM mod_schema WHERE generation=? AND state='DELETE_PENDING'",
                    data.generation()));
            sql(db, "ALTER TABLE mod_operation DROP CONSTRAINT fixture_checkpoint");
            ledger.resume(id); // Simulate interruption of an explicitly resumed attempt.
        }
        try (var db = open()) {
            assertNotEquals(firstBoot, db.bootId());
            var ledger = new ModVersionDeletionLedger(db, root);
            assertEquals("UNKNOWN/PROGRAM_REMOVED", phase(db, id));
            assertThrows(SQLException.class, () -> ledger.removeData(id));
            assertEquals("PROGRAM_REMOVED", ledger.resume(id));
            ledger.removeData(id);
            ledger.removeReferences(id);
            ledger.removeRegistry(id);
            ledger.cleanup(id);
            assertTrue(ledger.finish(id));
            assertEquals("0", value(db, "SELECT COUNT(*) FROM mod_operation"));
        }
    }

    @Test
    void archiveFailureRetainsOriginalIdentityAndNeverDeletesAReplacementAfterSuccess() throws Exception {
        try (var db = open()) {
            owner(db);
            release(db, "1.0.0", null);
            var ledger = new ModVersionDeletionLedger(db, root);
            var id = ledger.begin(ledger.review(OWNER, "1.0.0"), "{}");
            String archive = value(db, "SELECT archive_log_id FROM mod_operation WHERE operation_id=?", id);
            removeProgram("1.0.0");
            ledger.programRemoved(id);
            ledger.removeData(id);
            ledger.removeReferences(id);
            ledger.removeRegistry(id);
            ledger.cleanup(id);
            sql(db, "ALTER TABLE runtime_log ADD CONSTRAINT fixture_archive CHECK(event_code<>'MOD_VERSION_DELETED')");
            assertThrows(SQLException.class, () -> ledger.finish(id));
            assertEquals("UNKNOWN/CLEANUP", phase(db, id));
            assertEquals(archive, value(db, "SELECT archive_log_id FROM mod_operation WHERE operation_id=?", id));
            assertEquals("0", value(db, "SELECT COUNT(*) FROM runtime_log WHERE operation_id=?", id));
            sql(db, "ALTER TABLE runtime_log DROP CONSTRAINT fixture_archive");
            assertEquals("CLEANUP", ledger.resume(id));
            assertTrue(ledger.finish(id));
            assertEquals(archive, value(db, "SELECT log_id FROM runtime_log WHERE operation_id=?", id));
            release(db, "1.0.0", null);
            assertFalse(ledger.finish(id));
            assertThrows(SQLException.class, () -> ledger.resume(id));
            assertTrue(Files.exists(program("1.0.0").resolve("main.jar")));
            assertEquals("1", value(db, "SELECT COUNT(*) FROM runtime_log WHERE operation_id=?", id));
        }
    }

    @Test
    void invalidOriginalEvidenceCannotReachDDLAndReappearingProgramBlocksLaterCleanup() throws Exception {
        try (var db = open()) {
            owner(db);
            release(db, "1.0.0", "1");
            var data = schema(db, "1");
            var ledger = new ModVersionDeletionLedger(db, root);
            var id = ledger.begin(ledger.review(OWNER, "1.0.0"), "{}");
            removeProgram("1.0.0");
            ledger.programRemoved(id);
            String request = value(db, "SELECT request_json FROM mod_operation WHERE operation_id=?", id);
            sql(db, "UPDATE mod_operation SET request_json=? WHERE operation_id=?",
                    request.replace("\"removeSchema\":true", "\"removeSchema\":false"), id);
            assertThrows(SQLException.class, () -> ledger.removeData(id));
            assertEquals("retained data",
                    value(db, "SELECT value_text FROM " + new SchemaCoordinates(data).physicalName() + ".items"));
            sql(db, "UPDATE mod_operation SET request_json=? WHERE operation_id=?", request, id);
            ledger.resume(id);
            ledger.removeData(id);
            Files.createDirectories(program("1.0.0"));
            Files.writeString(program("1.0.0").resolve("new-file"), "do not touch");
            assertThrows(SQLException.class, () -> ledger.removeReferences(id));
            assertEquals("UNKNOWN/DATA_REMOVED", phase(db, id));
            assertEquals("do not touch", Files.readString(program("1.0.0").resolve("new-file")));
            assertEquals("1", value(db, "SELECT COUNT(*) FROM mod_version"));
        }
    }

    @Test
    void classSourcesAndPathsOutsideInstalledRootAreNotAdmitted() throws Exception {
        try (var db = open()) {
            owner(db);
            release(db, "1.0.0", null);
            var ledger = new ModVersionDeletionLedger(db, root);
            sql(db, "UPDATE mod_version SET origin_kind='CLASS'");
            assertThrows(SQLException.class, () -> ledger.review(OWNER, "1.0.0"));
            sql(db, "UPDATE mod_version SET origin_kind='LOCAL',install_path=?", root.resolve("source").toString());
            assertThrows(SQLException.class, () -> ledger.review(OWNER, "1.0.0"));
            assertEquals("0", value(db, "SELECT COUNT(*) FROM mod_operation"));
            assertTrue(Files.exists(program("1.0.0").resolve("main.jar")));
        }
    }

    @Test
    void activeConnectionsReaderGrantsAndCrossSchemaForeignKeysProtectOtherData() throws Exception {
        try (var db = open()) {
            owner(db);
            release(db, "1.0.0", "1");
            var data = schema(db, "1");
            var names = new SchemaCoordinates(data);
            var ledger = new ModVersionDeletionLedger(db, root);
            try (var scope = new SchemaAccessScope(db, data, UUID.randomUUID(), false);
                    var c = scope.openConnection()) {
                assertThrows(SQLException.class, () -> ledger.review(OWNER, "1.0.0"));
            }
            try (var scope = new SchemaAccessScope(db, data, UUID.randomUUID(), true)) {
                try (var c = scope.openConnection()) {
                    assertThrows(SQLException.class, () -> ledger.review(OWNER, "1.0.0"));
                }
                assertThrows(SQLException.class, () -> ledger.review(OWNER, "1.0.0"));
            }
            sql(db, "CREATE TABLE app.fixture_dependent(id INT PRIMARY KEY, FOREIGN KEY(id) REFERENCES "
                    + names.physicalName() + ".items(id))");
            sql(db, "INSERT INTO app.fixture_dependent VALUES(1)");
            assertThrows(SQLException.class, () -> ledger.review(OWNER, "1.0.0"));
            assertEquals("1", value(db, "SELECT COUNT(*) FROM app.fixture_dependent"));
            sql(db, "DROP TABLE app.fixture_dependent");
            assertNotNull(ledger.review(OWNER, "1.0.0"));
        }
    }

    @Test
    void ownerPrivilegesAndExternalOwnershipAreRejectedBeforeAnyDeletion() throws Exception {
        try (var db = open()) {
            owner(db);
            release(db, "1.0.0", "1");
            var data = schema(db, "1");
            var names = new SchemaCoordinates(data);
            var ledger = new ModVersionDeletionLedger(db, root);
            sql(db, "ALTER USER " + names.databaseUser() + " ADMIN TRUE");
            assertThrows(SQLException.class, () -> ledger.review(OWNER, "1.0.0"));
            sql(db, "ALTER USER " + names.databaseUser() + " ADMIN FALSE");
            sql(db, "CREATE SCHEMA external_fixture AUTHORIZATION " + names.databaseUser());
            assertThrows(SQLException.class, () -> ledger.review(OWNER, "1.0.0"));
            sql(db, "DROP SCHEMA external_fixture");
            sql(db, "GRANT SELECT ON app.mod_registry TO " + names.databaseUser());
            assertThrows(SQLException.class, () -> ledger.review(OWNER, "1.0.0"));
            assertEquals("retained data", value(db, "SELECT value_text FROM " + names.physicalName() + ".items"));
        }
    }

    @Test
    void externalViewsCannotBeCascadedAwayAndInternalViewGraphCanBeRemovedTogether() throws Exception {
        try (var db = open()) {
            owner(db);
            release(db, "1.0.0", "1");
            var data = schema(db, "1");
            String schema = new SchemaCoordinates(data).physicalName();
            sql(db, "CREATE VIEW " + schema + ".own_view AS SELECT * FROM " + schema + ".items");
            sql(db, "CREATE VIEW app.external_view AS SELECT * FROM " + schema + ".own_view");
            var ledger = new ModVersionDeletionLedger(db, root);
            var id = ledger.begin(ledger.review(OWNER, "1.0.0"), "{}");
            removeProgram("1.0.0");
            ledger.programRemoved(id);
            assertThrows(SQLException.class, () -> ledger.removeData(id));
            assertEquals("UNKNOWN/PROGRAM_REMOVED", phase(db, id));
            assertEquals("retained data", value(db, "SELECT value_text FROM app.external_view"));
            assertEquals("1", value(db, "SELECT COUNT(*) FROM " + schema + ".items"));
            sql(db, "DROP VIEW app.external_view");
            ledger.resume(id);
            ledger.removeData(id);
            ledger.removeReferences(id);
            ledger.removeRegistry(id);
            ledger.cleanup(id);
            ledger.finish(id);
            assertEquals("0", value(db, "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=?", schema));
        }
    }

    @Test
    void externalSynonymsAndDerivedDomainsRemainProtected() throws Exception {
        try (var db = open()) {
            owner(db);
            release(db, "1.0.0", "1");
            var data = schema(db, "1");
            String schema = new SchemaCoordinates(data).physicalName();
            var ledger = new ModVersionDeletionLedger(db, root);
            sql(db, "CREATE SYNONYM app.external_name FOR " + schema + ".items");
            assertThrows(SQLException.class, () -> ledger.review(OWNER, "1.0.0"));
            assertEquals("retained data", value(db, "SELECT value_text FROM app.external_name"));
            sql(db, "DROP SYNONYM app.external_name");
            sql(db, "CREATE DOMAIN " + schema + ".positive AS INTEGER CHECK(VALUE>0)");
            sql(db, "CREATE DOMAIN app.derived_positive AS " + schema + ".positive");
            assertThrows(SQLException.class, () -> ledger.review(OWNER, "1.0.0"));
            sql(db, "DROP DOMAIN app.derived_positive");
            sql(db, "CREATE TABLE app.domain_consumer(item_value " + schema + ".positive)");
            assertThrows(SQLException.class, () -> ledger.review(OWNER, "1.0.0"));
            assertThrows(SQLException.class, () -> sql(db, "INSERT INTO app.domain_consumer VALUES(0)"));
        }
    }

    @Test
    void preparedButUnusedAndNeverPreparedSchemaStatesAreDeletedWithoutInventingData() throws Exception {
        try (var db = open()) {
            owner(db);
            release(db, "1.0.0", "1");
            var data = schema(db, "1");
            sql(db, "UPDATE mod_schema SET state='FAILED',ever_used=FALSE,validated_at=NULL,validation_digest=NULL");
            var ledger = new ModVersionDeletionLedger(db, root);
            var review = ledger.review(OWNER, "1.0.0");
            assertEquals(Optional.of(data), review.removedSchema());
            var id = ledger.begin(review, "{}");
            removeProgram("1.0.0");
            complete(ledger, id);
            release(db, "2.0.0", "2");
            review = ledger.review(OWNER, "2.0.0");
            assertTrue(review.removedSchema().isEmpty());
            id = ledger.begin(review, "{}");
            removeProgram("2.0.0");
            complete(ledger, id);
            assertEquals("0", value(db, "SELECT COUNT(*) FROM mod_schema"));
        }
    }

    @Test
    void newlyAddedRetainedReferenceAfterAdmissionBlocksCleanupWithoutDeletingAnyData() throws Exception {
        try (var db = open()) {
            owner(db);
            release(db, "1.0.0", "1");
            var data = schema(db, "1");
            var ledger = new ModVersionDeletionLedger(db, root);
            var id = ledger.begin(ledger.review(OWNER, "1.0.0"), "{}");
            removeProgram("1.0.0");
            ledger.programRemoved(id);
            release(db, "2.0.0", "1");
            assertThrows(SQLException.class, () -> ledger.removeData(id));
            assertEquals("UNKNOWN/PROGRAM_REMOVED", phase(db, id));
            assertEquals("retained data",
                    value(db, "SELECT value_text FROM " + new SchemaCoordinates(data).physicalName() + ".items"));
        }
    }

    @Test
    void retainedTasksCrossOwnerSchemaResourcesMaintenanceAndEnabledDependentsBlockAdmission() throws Exception {
        try (var db = open()) {
            owner(db);
            release(db, "1.0.0", "1");
            var data = schema(db, "1");
            var ledger = new ModVersionDeletionLedger(db, root);
            UUID task = UUID.randomUUID();
            sql(db, "INSERT INTO platform_task(task_id,owner_id,owner_version,task_type,state,cancellable,boot_id,request_json,recovery_json,archive_log_id,progress_text,started_at) VALUES(?,?,'1.0.0','fixture','UNKNOWN',FALSE,?,'{}','{}',?,'',1)",
                    task, OWNER, db.bootId(), UUID.randomUUID().toString());
            assertThrows(SQLException.class, () -> ledger.review(OWNER, "1.0.0"));
            sql(db, "UPDATE platform_task SET owner_id='other.owner' WHERE task_id=?", task);
            sql(db, "INSERT INTO platform_task_resource VALUES(?,?,'SCHEMA',?,'other.owner','SHARED',1,NULL,'{}')",
                    UUID.randomUUID(), task, data.generation().toString());
            assertThrows(SQLException.class, () -> ledger.review(OWNER, "1.0.0"));
            sql(db, "DELETE FROM platform_task_resource");
            sql(db, "DELETE FROM platform_task");
            UUID operation = UUID.randomUUID();
            sql(db, "INSERT INTO mod_operation(operation_id,mod_id,operation_type,state,phase,request_json,recovery_json,archive_log_id,created_at,updated_at) VALUES(?,?,'INSTALL','UNKNOWN','STAGED','{}','{}',?,1,1)",
                    operation, OWNER, UUID.randomUUID().toString());
            assertThrows(SQLException.class, () -> ledger.review(OWNER, "1.0.0"));
            sql(db, "DELETE FROM mod_operation WHERE operation_id=?", operation);
            sql(db, "INSERT INTO mod_registry(mod_id,updated_at) VALUES('consumer',1)");
            sql(db, "INSERT INTO mod_version(mod_id,version,install_path,content_sha256,manifest_json,origin_kind,installed_at,state) VALUES('consumer','1.0.0','fixture',?,?,'LOCAL',1,'READY')",
                    "0".repeat(64), "{\"dependencies\":[{\"id\":\"" + OWNER + "\",\"versionRange\":\"*\"}]}");
            sql(db, "UPDATE mod_registry SET enabled=TRUE,selected_version='1.0.0' WHERE mod_id='consumer'");
            assertThrows(SQLException.class, () -> ledger.review(OWNER, "1.0.0"));
            assertEquals("TRUE", value(db, "SELECT enabled FROM mod_registry WHERE mod_id='consumer'"));
            sql(db, "UPDATE mod_registry SET enabled=FALSE WHERE mod_id='consumer'");
            assertNotNull(ledger.review(OWNER, "1.0.0"));
            sql(db, "UPDATE mod_version SET manifest_json='{}' WHERE mod_id='consumer'");
            sql(db, "UPDATE mod_registry SET enabled=TRUE WHERE mod_id='consumer'");
            assertNotNull(ledger.review(OWNER, "1.0.0"));
        }
    }

    @Test
    void referenceRemovalIsAtomicAndPreservesSharedServerCredentialAndPlatformHistory() throws Exception {
        try (var db = open()) {
            owner(db);
            release(db, "1.0.0", "1");
            var data = schema(db, "1");
            sql(db, "INSERT INTO security_profile VALUES(1,1,'fixture',?,'AES256_GCM_V1',?,'ARGON2ID_V1',?,'fixture',1)",
                    new byte[48], new byte[12], new byte[16]);
            sql(db, "INSERT INTO credential(credential_id,storage_format,payload,nonce) VALUES('shared','AES256_GCM_V1',?,?)",
                    new byte[20], new byte[12]);
            sql(db, "INSERT INTO server_profile(server_profile_id,credential_id,host,ssh_port,username,display_name) VALUES('shared','shared','localhost',22,'fixture','fixture')");
            sql(db, "INSERT INTO mod_server_reference VALUES(?,'shared',?,'1',?,'record','UNKNOWN',1)",
                    UUID.randomUUID(), OWNER, data.generation());
            sql(db, "INSERT INTO mod_credential_reference VALUES(?,'shared',?,'1',?,'record','fixture','UNKNOWN',1)",
                    UUID.randomUUID(), OWNER, data.generation());
            sql(db, "INSERT INTO log_category VALUES(?,?,?, ?,?,'fixture','fixture_log','fixture',1)",
                    UUID.randomUUID(), OWNER, "v1", data.generation(), new SchemaCoordinates(data).physicalName());
            var ledger = new ModVersionDeletionLedger(db, root);
            var id = ledger.begin(ledger.review(OWNER, "1.0.0"), "{}");
            removeProgram("1.0.0");
            ledger.programRemoved(id);
            ledger.removeData(id);
            assertEquals("1", value(db, "SELECT COUNT(*) FROM mod_credential_reference"));
            sql(db, "ALTER TABLE mod_operation ADD CONSTRAINT fixture_checkpoint CHECK(phase<>'REFERENCES_REMOVED')");
            assertThrows(SQLException.class, () -> ledger.removeReferences(id));
            assertEquals("1", value(db, "SELECT COUNT(*) FROM mod_server_reference"));
            assertEquals("1", value(db, "SELECT COUNT(*) FROM mod_credential_reference"));
            sql(db, "ALTER TABLE mod_operation DROP CONSTRAINT fixture_checkpoint");
            ledger.resume(id);
            ledger.removeReferences(id);
            ledger.removeRegistry(id);
            ledger.cleanup(id);
            ledger.finish(id);
            assertEquals("0", value(db, "SELECT COUNT(*) FROM mod_credential_reference"));
            assertEquals("0", value(db, "SELECT COUNT(*) FROM mod_server_reference"));
            assertEquals("0", value(db, "SELECT COUNT(*) FROM log_category WHERE owner_id=?", OWNER));
            assertEquals("1", value(db, "SELECT COUNT(*) FROM server_profile"));
            assertEquals("1", value(db, "SELECT COUNT(*) FROM credential"));
            assertEquals("1",
                    value(db, "SELECT COUNT(*) FROM runtime_log WHERE owner_id='app' AND operation_id=?", id));
        }
    }
}
