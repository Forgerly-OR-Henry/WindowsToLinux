package gold.debug.windowstolinux.app.db;

import static gold.debug.windowstolinux.app.db.ModVersionDeletionLedgerTest.*;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.sql.SQLException;
import java.util.*;

import gold.debug.windowstolinux.app.db.connect.PlatformDatabase;
import gold.debug.windowstolinux.app.db.log.*;
import gold.debug.windowstolinux.app.db.log.LogRecordCodec;
import gold.debug.windowstolinux.app.db.log.LogStore;
import gold.debug.windowstolinux.app.db.mod.*;
import gold.debug.windowstolinux.app.db.mod.ModDeletionLedger;
import gold.debug.windowstolinux.app.db.schema.SchemaCoordinates;
import gold.debug.windowstolinux.app.db.setting.ModNavigationPreferences.PagePin;
import gold.debug.windowstolinux.app.db.setting.ModNavigationPreferences;
import gold.debug.windowstolinux.appapi.task.log.LogCategory;
import gold.debug.windowstolinux.appapi.task.log.LogLevel;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ModWholeDeletionLedgerTest {
    @TempDir
    Path root;

    final ModVersionDeletionLedgerTest fixture = new ModVersionDeletionLedgerTest();

    @BeforeEach
    void setup() {
        fixture.root = root;
    }

    void removePrograms(String... versions) throws Exception {
        for (String version : versions)
            fixture.removeProgram(version);
        Files.deleteIfExists(root.resolve("mod").resolve(OWNER));
    }

    void pins(PlatformDatabase db, PagePin... pins) throws Exception {
        try (var c = db.openConnection()) {
            c.setAutoCommit(false);
            ModNavigationPreferences.write(c, List.of(pins));
            c.commit();
        }
    }

    List<PagePin> pins(PlatformDatabase db) throws Exception {
        try (var c = db.openConnection()) {
            return ModNavigationPreferences.read(c);
        }
    }

    LogStore.Target log(PlatformDatabase db, String owner) throws Exception {
        var repo = new LogStore(db);
        var target = repo.register(owner, Optional.empty(), new LogCategory("runtime", "fixture", Optional.empty()));
        repo.store(target,
            List.of(new LogRecordCodec.Record(UUID.randomUUID(), 1, owner, "runtime", LogLevel.INFO, "FIXTURE",
                "retained record", null, owner.equals("app") ? OWNER : owner, "1.0.0", null, null, null,
                false)),
            100);
        return target;
    }

    @Test
    void allReleasesOrphanDataOwnedLogsAndPinsAreRemovedButSharedResourcesAndPlatformHistoryRemain() throws Exception {
        try (var db = fixture.open()) {
            fixture.owner(db);
            fixture.release(db, "1.0.0", "2");
            fixture.release(db, "2.0.0", "2");
            var orphan = fixture.schema(db, "1");
            var current = fixture.schema(db, "2");
            sql(db, "UPDATE mod_registry SET selected_version='2.0.0' WHERE mod_id=?", OWNER);
            log(db, OWNER);
            log(db, "other.owner");
            log(db, "app");
            pins(db, new PagePin("other.owner", "first"), new PagePin(OWNER, "main"),
                new PagePin("other.owner", "last"));
            sql(db, "INSERT INTO security_profile(security_profile_id,is_encryption_enabled,password_verifier,wrapped_data_key,key_wrap_algorithm,key_wrap_nonce,key_derivation_algorithm,key_derivation_salt,key_derivation_parameters,format_version) VALUES(1,1,'fixture',?,'AES256_GCM_V1',?,'ARGON2ID_V1',?,'fixture',1)",
                new byte[48], new byte[12], new byte[16]);
            sql(db, "INSERT INTO credential(credential_id,storage_format,payload,nonce) VALUES('shared','AES256_GCM_V1',?,?)",
                new byte[20], new byte[12]);
            sql(db, "INSERT INTO server_profile(server_profile_id,credential_id,host,ssh_port,username,display_name,display_order) VALUES('shared','shared','localhost',22,'fixture','fixture',0)");
            sql(db, "INSERT INTO mod_server_reference(reference_id,server_profile_id,mod_id,schema_version,schema_generation,business_key,state,updated_at) VALUES(?,'shared',?,'1',?,'record','UNKNOWN',1)",
                UUID.randomUUID(), OWNER, orphan.generation());
            sql(db, "INSERT INTO mod_credential_reference(reference_id,credential_id,mod_id,schema_version,schema_generation,business_key,purpose,state,updated_at) VALUES(?,'shared',?,'2',?,'record','fixture','UNKNOWN',1)",
                UUID.randomUUID(), OWNER, current.generation());
            sql(db, "CREATE TABLE app.unrelated_fixture(value_text VARCHAR)");
            sql(db, "INSERT INTO app.unrelated_fixture VALUES('keep')");
            var ledger = new ModDeletionLedger(db, root, DeletionFileEvidenceFixture.files());
            var review = ledger.review(OWNER);
            assertEquals(List.of("1.0.0", "2.0.0"), review.versions());
            assertEquals(List.of(orphan, current), review.removedSchemas());
            assertEquals(1, review.logTargets().size());
            var id = ledger.begin(review, "{}");
            assertEquals("DELETE_MOD", value(db, "SELECT operation_type FROM mod_operation WHERE operation_id=?", id));
            assertEquals("2", value(db, "SELECT COUNT(*) FROM mod_version WHERE state='DELETE_PENDING'"));
            assertEquals("2", value(db, "SELECT COUNT(*) FROM mod_schema WHERE state='DELETE_PENDING'"));
            removePrograms("1.0.0", "2.0.0");
            fixture.complete(ledger, id);
            assertEquals("0", value(db, "SELECT COUNT(*) FROM mod_registry WHERE mod_id=?", OWNER));
            assertEquals("0", value(db, "SELECT COUNT(*) FROM mod_schema WHERE mod_id=?", OWNER));
            assertEquals("0", value(db, "SELECT COUNT(*) FROM runtime_log WHERE owner_id=?", OWNER));
            assertEquals("0", value(db, "SELECT COUNT(*) FROM mod_credential_reference"));
            assertEquals("0", value(db, "SELECT COUNT(*) FROM mod_server_reference"));
            assertEquals("1", value(db, "SELECT COUNT(*) FROM credential"));
            assertEquals("1", value(db, "SELECT COUNT(*) FROM server_profile"));
            assertEquals("1", value(db, "SELECT COUNT(*) FROM runtime_log WHERE owner_id='other.owner'"));
            assertEquals("1", value(db,
                "SELECT COUNT(*) FROM runtime_log WHERE owner_id='app' AND event_code='FIXTURE' AND subject_mod_id=?",
                OWNER));
            assertEquals("1", value(db,
                "SELECT COUNT(*) FROM runtime_log WHERE owner_id='app' AND event_code='MOD_DELETED' AND operation_id=?",
                id));
            assertEquals("keep", value(db, "SELECT value_text FROM app.unrelated_fixture"));
            assertEquals(List.of(new PagePin("other.owner", "first"), new PagePin("other.owner", "last")), pins(db));
        }
    }

    @Test
    void ownedEmergencyAndAuxiliaryFilesBlockReferenceRemovalAndNoUnregisteredFileIsTouched() throws Exception {
        try (var db = fixture.open()) {
            fixture.owner(db);
            fixture.release(db, "1.0.0", "1");
            var schema = fixture.schema(db, "1");
            var target = log(db, OWNER);
            Path emergency = db.root().resolve("log/emergency");
            Files.createDirectories(emergency);
            Path original = emergency.resolve(target.key() + ".log"),
                pending = emergency.resolve(target.key() + ".pending");
            Files.writeString(original, "corrupt owned file");
            Files.writeString(pending, "interrupted owned file");
            Path foreign = emergency.resolve(UUID.randomUUID() + ".log");
            Files.writeString(foreign, "unregistered independent file");
            Path cache = db.root().resolve("mod").resolve(OWNER);
            Files.createDirectories(cache);
            Path item = Files.writeString(cache.resolve("cache"), "registered cleanup is required");
            var ledger = new ModDeletionLedger(db, root, DeletionFileEvidenceFixture.files());
            var id = ledger.begin(ledger.review(OWNER), "{}");
            removePrograms("1.0.0");
            ledger.programRemoved(id);
            ledger.removeData(id);
            assertThrows(SQLException.class, () -> ledger.removeReferences(id));
            assertEquals("UNKNOWN/DATA_REMOVED", phase(db, id));
            assertEquals("1", value(db, "SELECT COUNT(*) FROM mod_schema WHERE generation=?", schema.generation()));
            assertTrue(Files.exists(original));
            assertTrue(Files.exists(pending));
            Files.delete(item);
            Files.delete(cache);
            Files.delete(original);
            ledger.resume(id);
            assertThrows(SQLException.class, () -> ledger.removeReferences(id));
            Files.delete(pending);
            ledger.resume(id);
            ledger.removeReferences(id);
            ledger.removeRegistry(id);
            ledger.cleanup(id);
            ledger.finish(id);
            assertEquals("unregistered independent file", Files.readString(foreign));
        }
    }

    @Test
    void changedOwnedCatalogAndPinsRejectAdmissionButOtherOwnersNavigationCanChange() throws Exception {
        try (var db = fixture.open()) {
            fixture.owner(db);
            fixture.release(db, "1.0.0", null);
            pins(db, new PagePin(OWNER, "main"));
            var ledger = new ModDeletionLedger(db, root, DeletionFileEvidenceFixture.files());
            var dataReview = ledger.review(OWNER);
            fixture.schema(db, "1");
            assertThrows(SQLException.class, () -> ledger.begin(dataReview, "{}"));
            var logsReview = ledger.review(OWNER);
            log(db, OWNER);
            assertThrows(SQLException.class, () -> ledger.begin(logsReview, "{}"));
            var pinsReview = ledger.review(OWNER);
            pins(db, new PagePin(OWNER, "settings"));
            assertThrows(SQLException.class, () -> ledger.begin(pinsReview, "{}"));
            var allowed = ledger.review(OWNER);
            pins(db, new PagePin(OWNER, "settings"), new PagePin("other.owner", "new"));
            var id = ledger.begin(allowed, "{}");
            removePrograms("1.0.0");
            fixture.complete(ledger, id);
            assertEquals(List.of(new PagePin("other.owner", "new")), pins(db));
        }
    }

    @Test
    void referenceCheckpointRollbackRestoresLogRecordsCategoriesAndPinsTogether() throws Exception {
        try (var db = fixture.open()) {
            fixture.owner(db);
            fixture.release(db, "1.0.0", null);
            log(db, OWNER);
            pins(db, new PagePin(OWNER, "main"));
            var ledger = new ModDeletionLedger(db, root, DeletionFileEvidenceFixture.files());
            var id = ledger.begin(ledger.review(OWNER), "{}");
            removePrograms("1.0.0");
            ledger.programRemoved(id);
            ledger.removeData(id);
            sql(db, "ALTER TABLE mod_operation ADD CONSTRAINT fixture_checkpoint CHECK(phase<>'REFERENCES_REMOVED')");
            assertThrows(SQLException.class, () -> ledger.removeReferences(id));
            assertEquals("1", value(db, "SELECT COUNT(*) FROM runtime_log WHERE owner_id=?", OWNER));
            assertEquals("1", value(db, "SELECT COUNT(*) FROM log_category WHERE owner_id=?", OWNER));
            assertEquals(List.of(new PagePin(OWNER, "main")), pins(db));
            sql(db, "ALTER TABLE mod_operation DROP CONSTRAINT fixture_checkpoint");
            ledger.resume(id);
            ledger.removeReferences(id);
            ledger.removeRegistry(id);
            ledger.cleanup(id);
            ledger.finish(id);
        }
    }

    @Test
    void deletedRegistrySurvivesArchiveFailureAndRebootWithOriginalScopeAndReceiptIdentity() throws Exception {
        UUID id;
        String archive;
        try (var db = fixture.open()) {
            fixture.owner(db);
            fixture.release(db, "1.0.0", "1");
            fixture.schema(db, "1");
            var ledger = new ModDeletionLedger(db, root, DeletionFileEvidenceFixture.files());
            id = ledger.begin(ledger.review(OWNER), "{\"physical\":\"original\"}");
            archive = value(db, "SELECT archive_log_id FROM mod_operation WHERE operation_id=?", id);
            removePrograms("1.0.0");
            ledger.programRemoved(id);
            ledger.removeData(id);
            ledger.removeReferences(id);
            ledger.removeRegistry(id);
            ledger.cleanup(id);
            sql(db, "ALTER TABLE runtime_log ADD CONSTRAINT fixture_archive CHECK(event_code<>'MOD_DELETED')");
            assertThrows(SQLException.class, () -> ledger.finish(id));
            assertEquals("UNKNOWN/CLEANUP", phase(db, id));
            assertEquals("0", value(db, "SELECT COUNT(*) FROM mod_registry"));
            sql(db, "ALTER TABLE runtime_log DROP CONSTRAINT fixture_archive");
        }
        try (var db = fixture.open()) {
            var ledger = new ModDeletionLedger(db, root, DeletionFileEvidenceFixture.files());
            assertEquals("{\"physical\":\"original\"}", ledger.execution(id).files());
            assertEquals("CLEANUP", ledger.resume(id));
            assertTrue(ledger.finish(id));
            assertEquals(archive, value(db, "SELECT log_id FROM runtime_log WHERE operation_id=?", id));
            fixture.owner(db);
            fixture.release(db, "1.0.0", null);
            assertFalse(ledger.finish(id));
            assertThrows(SQLException.class, () -> ledger.resume(id));
            assertTrue(Files.exists(fixture.program("1.0.0")));
        }
    }

    @Test
    void wholeOwnerRootMustDisappearAndAReplacementOwnerCannotBeAdoptedDuringRecovery() throws Exception {
        try (var db = fixture.open()) {
            fixture.owner(db);
            fixture.release(db, "1.0.0", null);
            var ledger = new ModDeletionLedger(db, root, DeletionFileEvidenceFixture.files());
            var id = ledger.begin(ledger.review(OWNER), "{}");
            fixture.removeProgram("1.0.0");
            Path foreign = Files.writeString(root.resolve("mod").resolve(OWNER).resolve("unknown-file"), "keep");
            assertThrows(SQLException.class, () -> ledger.programRemoved(id));
            assertEquals("keep", Files.readString(foreign));
            Files.delete(foreign);
            Files.delete(foreign.getParent());
            ledger.resume(id);
            ledger.programRemoved(id);
            ledger.removeData(id);
            ledger.removeReferences(id);
            ledger.removeRegistry(id);
            fixture.owner(db);
            assertThrows(SQLException.class, () -> ledger.cleanup(id));
            assertEquals("UNKNOWN/REGISTRY_REMOVED", phase(db, id));
            assertThrows(SQLException.class, () -> ledger.resume(id));
        }
    }

    @Test
    void emptyOwnerCanBeRemovedButAnyClassReleaseOrMalformedPinsPreventsWholeDeletion() throws Exception {
        try (var db = fixture.open()) {
            fixture.owner(db);
            fixture.release(db, "1.0.0", null);
            fixture.release(db, "2.0.0", null);
            var ledger = new ModDeletionLedger(db, root, DeletionFileEvidenceFixture.files());
            sql(db, "UPDATE mod_version SET origin_kind='CLASS' WHERE version='2.0.0'");
            assertThrows(SQLException.class, () -> ledger.review(OWNER));
            sql(db, "UPDATE mod_version SET origin_kind='LOCAL'");
            sql(db, "INSERT INTO desktop_preference(preference_key,preference_value) VALUES('mod_navigation_pins','{\"formatVersion\":1,\"pages\":[],\"unexpected\":true}')");
            assertThrows(SQLException.class, () -> ledger.review(OWNER));
            assertEquals("0", value(db, "SELECT COUNT(*) FROM mod_operation"));
            sql(db, "DELETE FROM desktop_preference");
            sql(db, "DELETE FROM mod_version");
            removePrograms("1.0.0", "2.0.0");
            var id = ledger.begin(ledger.review(OWNER), "{}");
            fixture.complete(ledger, id);
            assertEquals("0", value(db, "SELECT COUNT(*) FROM mod_registry"));
            assertNull(value(db, "SELECT mod_version FROM runtime_log WHERE operation_id=?", id));
        }
    }

    @Test
    void partialPhysicalDataCleanupRetainsAllOriginalGenerationsUntilExplicitRecovery() throws Exception {
        try (var db = fixture.open()) {
            fixture.owner(db);
            fixture.release(db, "1.0.0", "1");
            var first = fixture.schema(db, "1");
            var second = fixture.schema(db, "2");
            var ledger = new ModDeletionLedger(db, root, DeletionFileEvidenceFixture.files());
            var id = ledger.begin(ledger.review(OWNER), "{}");
            removePrograms("1.0.0");
            ledger.programRemoved(id);
            sql(db, "CREATE VIEW app.external_keep AS SELECT * FROM " + new SchemaCoordinates(second).physicalName()
                + ".items");
            assertThrows(SQLException.class, () -> ledger.removeData(id));
            assertEquals("UNKNOWN/PROGRAM_REMOVED", phase(db, id));
            assertEquals("0", value(db, "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=?",
                new SchemaCoordinates(first).physicalName()));
            assertEquals("retained data", value(db, "SELECT value_text FROM app.external_keep"));
            assertEquals("2", value(db, "SELECT COUNT(*) FROM mod_schema WHERE state='DELETE_PENDING'"));
            sql(db, "DROP VIEW app.external_keep");
            ledger.resume(id);
            ledger.removeData(id);
            ledger.removeReferences(id);
            ledger.removeRegistry(id);
            ledger.cleanup(id);
            ledger.finish(id);
        }
    }

    @Test
    void corruptedWholeScopeCannotMasqueradeAsVersionDeletionOrDeleteForeignDiagnostics() throws Exception {
        try (var db = fixture.open()) {
            fixture.owner(db);
            fixture.release(db, "1.0.0", null);
            log(db, OWNER);
            var ledger = new ModDeletionLedger(db, root, DeletionFileEvidenceFixture.files());
            var id = ledger.begin(ledger.review(OWNER), "{}");
            String original = value(db, "SELECT request_json FROM mod_operation WHERE operation_id=?", id);
            sql(db, "UPDATE mod_operation SET request_json=? WHERE operation_id=?",
                original.replace("\"owner\":\"" + OWNER + "\"", "\"owner\":\"other.owner\""), id);
            assertThrows(SQLException.class, () -> ledger.programRemoved(id));
            assertEquals("1", value(db, "SELECT COUNT(*) FROM runtime_log WHERE owner_id=?", OWNER));
            sql(db, "UPDATE mod_operation SET request_json=?,operation_type='DELETE_VERSION' WHERE operation_id=?",
                original, id);
            assertThrows(SQLException.class, () -> ledger.resume(id));
            assertTrue(Files.exists(fixture.program("1.0.0")));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"last", "shared", "stateless"})
    void originalVersionOnlyEvidenceResumesWithoutRewritingOrExpandingTheAcceptedScope(String kind) throws Exception {
        UUID id;
        String original;
        try (var db = fixture.open()) {
            fixture.owner(db);
            fixture.release(db, "1.0.0", kind.equals("stateless") ? null : "1");
            if (!kind.equals("stateless"))
                fixture.schema(db, "1");
            if (kind.equals("shared"))
                fixture.release(db, "2.0.0", "1");
            var ledger = new ModDeletionLedger(db, root, DeletionFileEvidenceFixture.files());
            id = ledger.begin(ledger.review(OWNER, "1.0.0"), "{\"scope\":\"original physical evidence\"}");
            var json = new com.fasterxml.jackson.databind.ObjectMapper();
            var node = (com.fasterxml.jackson.databind.node.ObjectNode) json
                .readTree(value(db, "SELECT request_json FROM mod_operation WHERE operation_id=?", id));
            var data = node.remove("schemas");
            if (data.isEmpty())
                node.putNull("schema");
            else {
                var oldSchema = (com.fasterxml.jackson.databind.node.ObjectNode) data.get(0);
                oldSchema.remove("version");
                node.set("schema", oldSchema);
            }
            node.put("format", 1);
            node.put("removeSchema", kind.equals("last"));
            node.remove("targets");
            node.remove("pins");
            node.remove("secrets");
            original = node.toString();
            sql(db, "UPDATE mod_operation SET request_json=? WHERE operation_id=?", original, id);
        }
        try (var db = fixture.open()) {
            var ledger = new ModDeletionLedger(db, root, DeletionFileEvidenceFixture.files());
            assertEquals("1.0.0", ledger.execution(id).version());
            assertEquals("QUIESCENT", ledger.resume(id));
            assertEquals(original, value(db, "SELECT request_json FROM mod_operation WHERE operation_id=?", id));
            assertEquals("{\"scope\":\"original physical evidence\"}", ledger.execution(id).files());
            fixture.removeProgram("1.0.0");
            fixture.complete(ledger, id);
            assertEquals("TRUE", value(db, "SELECT selection_initialized FROM mod_registry WHERE mod_id=?", OWNER));
            assertEquals(kind.equals("shared") ? "1" : "0",
                value(db, "SELECT COUNT(*) FROM mod_schema WHERE mod_id=?", OWNER));
            assertEquals("MOD_VERSION_DELETED",
                value(db, "SELECT event_code FROM runtime_log WHERE operation_id=?", id));
        }
    }

    @Test
    void onlyOwnedUnsharedCredentialsAreRemovedAndNewSharedConsumersInvalidateTheReview() throws Exception {
        try (var db = fixture.open()) {
            fixture.owner(db);
            fixture.release(db, "1.0.0", null);
            sql(db, "INSERT INTO security_profile(security_profile_id,is_encryption_enabled,password_verifier,wrapped_data_key,key_wrap_algorithm,key_wrap_nonce,key_derivation_algorithm,key_derivation_salt,key_derivation_parameters,format_version) VALUES(1,1,'fixture',?,'AES256_GCM_V1',?,'ARGON2ID_V1',?,'fixture',1)",
                new byte[48], new byte[12], new byte[16]);
            String owned = gold.debug.windowstolinux.app.db.security.SecretRefStore
                .newSecret(OWNER, "fixture").id();
            String shared = gold.debug.windowstolinux.app.db.security.SecretRefStore
                .newSecret(OWNER, "server").id();
            String other = gold.debug.windowstolinux.app.db.security.SecretRefStore
                .newSecret("other.owner", "fixture").id();
            for (String id : List.of(owned, shared, other))
                sql(db, "INSERT INTO credential(credential_id,storage_format,payload) VALUES(?,'PLAIN_V1',?)", id,
                    new byte[]{1, 2, 3});
            var ledger = new ModDeletionLedger(db, root, DeletionFileEvidenceFixture.files());
            var stale = ledger.review(OWNER);
            sql(db, "INSERT INTO server_profile(server_profile_id,credential_id,host,ssh_port,username,display_name,display_order) VALUES('shared',?,'localhost',22,'fixture','fixture',0)",
                shared);
            assertThrows(SQLException.class, () -> ledger.begin(stale, "{}"));
            var review = ledger.review(OWNER);
            assertEquals(List.of(owned), review.removedSecrets());
            assertEquals(List.of(shared), review.retainedSecrets());
            var id = ledger.begin(review, "{}");
            removePrograms("1.0.0");
            ledger.programRemoved(id);
            ledger.removeData(id);
            sql(db, "ALTER TABLE mod_operation ADD CONSTRAINT fixture_checkpoint CHECK(phase<>'REFERENCES_REMOVED')");
            assertThrows(SQLException.class, () -> ledger.removeReferences(id));
            assertEquals("3", value(db, "SELECT COUNT(*) FROM credential"));
            sql(db, "ALTER TABLE mod_operation DROP CONSTRAINT fixture_checkpoint");
            ledger.resume(id);
            ledger.removeReferences(id);
            ledger.removeRegistry(id);
            ledger.cleanup(id);
            ledger.finish(id);
            assertEquals("0", value(db, "SELECT COUNT(*) FROM credential WHERE credential_id=?", owned));
            assertEquals("1", value(db, "SELECT COUNT(*) FROM credential WHERE credential_id=?", shared));
            assertEquals("1", value(db, "SELECT COUNT(*) FROM credential WHERE credential_id=?", other));
            assertEquals("1", value(db, "SELECT COUNT(*) FROM server_profile"));
        }
    }
}
