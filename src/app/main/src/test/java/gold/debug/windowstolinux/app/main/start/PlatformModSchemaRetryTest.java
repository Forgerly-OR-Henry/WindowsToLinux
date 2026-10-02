package gold.debug.windowstolinux.app.main.start;

import gold.debug.windowstolinux.app.db.task.TaskLedger;
import gold.debug.windowstolinux.app.db.task.TaskResource;
import gold.debug.windowstolinux.appapi.db.SchemaAccess;
import gold.debug.windowstolinux.appapi.db.SchemaIdentity;
import gold.debug.windowstolinux.appapi.task.ApiException;
import gold.debug.windowstolinux.appapi.task.Outcome;

import static gold.debug.windowstolinux.app.main.start.PlatformModIntentsTest.*;
import static gold.debug.windowstolinux.app.main.start.PlatformTaskAdmissionIntegrationTest.*;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.sql.*;
import java.util.*;

import gold.debug.windowstolinux.app.db.mod.*;
import gold.debug.windowstolinux.app.db.schema.SchemaAccessScope;
import gold.debug.windowstolinux.app.db.schema.SchemaCoordinates;
import gold.debug.windowstolinux.app.db.schema.SchemaProvisioner;
import gold.debug.windowstolinux.app.db.schema.SchemaSnapshot;



import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class PlatformModSchemaRetryTest {
    @TempDir
    Path root;

    PlatformModInitializationTest initial;

    PlatformModCoordinatorTest fixture;

    static final String OWNER = PlatformModInitializationTest.OWNER;

    PlatformRuntime failedInitialization() throws Exception {
        initial = new PlatformModInitializationTest();
        initial.root = root;
        initial.setup();
        initial.module("invalid", false);
        fixture = initial.fixture;
        fixture.enable(OWNER);
        var runtime = fixture.open(true);
        assertTrue(fixture.start(runtime).running().isEmpty());
        assertEquals("FAILED", text(runtime, "SELECT state FROM mod_schema"));
        Files.writeString(fixture.trace, "");
        return runtime;
    }

    @AfterEach
    void cleanup() throws Exception {
        if (initial != null)
            initial.cleanup();
    }

    @Test
    void explicitRetryRebuildsFailedTargetInThisBootAndStartsBusinessOnlyAfterRestart() throws Exception {
        String newGeneration;
        try (var runtime = failedInitialization()) {
            String old = text(runtime, "SELECT generation FROM mod_schema");
            String physical = text(runtime, "SELECT physical_name FROM mod_schema");
            sql(runtime.persistence().database(),
                "INSERT INTO \"" + physical + "\".item VALUES(99,'failed residue',NULL,NULL)");
            initial.module("valid", false);
            var review = runtime.reviewModSchemaRetry(OWNER, Optional.empty());
            assertEquals(old, text(runtime, "SELECT generation FROM mod_schema"));
            assertTrue(fixture.actions().isEmpty());
            assertEquals(Outcome.SUCCEEDED, runtime.retryModSchema(review));
            newGeneration = text(runtime, "SELECT generation FROM mod_schema");
            assertNotEquals(old, newGeneration);
            assertEquals("READY", text(runtime, "SELECT state FROM mod_schema"));
            assertEquals("FALSE", text(runtime, "SELECT ever_used FROM mod_schema"));
            assertEquals(0, count(runtime.persistence().database(),
                "SELECT COUNT(*) FROM \"" + physical + "\".item WHERE id=99"));
            assertEquals(List.of("schema:initialize", "schema:validate"), fixture.actions());
            assertEquals("TRUE", text(runtime, "SELECT enabled FROM mod_registry"));
            assertEquals(VERSION, text(runtime, "SELECT selected_version FROM mod_registry"));
            assertEquals("1", text(runtime, "SELECT revision FROM mod_registry"));
            assertEquals("FAILED", text(runtime, "SELECT last_runtime_state FROM mod_registry"));
            assertEquals(0, count(runtime.persistence().database(), "SELECT COUNT(*) FROM mod_operation"));
            assertThrows(SQLException.class,
                () -> new SchemaCoordinates(new SchemaIdentity(OWNER, "1", UUID.fromString(old)))
                    .requireRegistered(runtime.persistence().database(), Set.of("READY")));
            assertThrows(Exception.class, () -> runtime.retryModSchema(review));
            assertThrows(Exception.class, () -> runtime.reviewModSchemaRetry(OWNER, Optional.empty()));
        }
        try (var runtime = fixture.open(false)) {
            assertEquals(Set.of(OWNER), fixture.start(runtime).running());
            assertEquals(newGeneration, text(runtime, "SELECT generation FROM mod_schema"));
        }
        assertEquals(1, fixture.actions().stream().filter("schema:initialize"::equals).count());
    }

    @Test
    void repeatedFailureNeedsAnotherExplicitReviewAndDoesNotReplayOnNextBoot() throws Exception {
        try (var runtime = failedInitialization()) {
            var review = runtime.reviewModSchemaRetry(OWNER, Optional.empty());
            assertEquals(Outcome.FAILED, runtime.retryModSchema(review));
            assertEquals("FAILED", text(runtime, "SELECT state FROM mod_schema"));
            assertThrows(ApiException.class, () -> runtime.retryModSchema(review));
            assertEquals(Outcome.FAILED, runtime.retryModSchema(runtime.reviewModSchemaRetry(OWNER, Optional.empty())));
            assertEquals(2, fixture.actions().stream().filter("schema:initialize"::equals).count());
        }
        try (var runtime = fixture.open(false)) {
            assertTrue(fixture.start(runtime).running().isEmpty());
        }
        assertEquals(2, fixture.actions().stream().filter("schema:initialize"::equals).count());
    }

    @Test
    void activeCrossOwnerWorkAndStaleRevisionRejectWithoutResetOrQueuedRetry() throws Exception {
        try (var runtime = failedInitialization()) {
            var db = runtime.persistence().database();
            String generation = text(runtime, "SELECT generation FROM mod_schema");
            var review = runtime.reviewModSchemaRetry(OWNER, Optional.empty());
            var ledger = new TaskLedger(db);
            var task = ledger.enqueue(UUID.randomUUID(), "other.mod", VERSION, work(),
                List.of(new TaskResource("MOD", "other.mod", "other.mod", false),
                    new TaskResource("MOD", OWNER, "other.mod", false)));
            assertTrue(ledger.tryStart(task));
            assertThrows(ApiException.class, () -> runtime.retryModSchema(review));
            assertEquals(generation, text(runtime, "SELECT generation FROM mod_schema"));
            assertTrue(fixture.actions().isEmpty());
            assertTrue(ledger.complete(task, Outcome.SUCCEEDED, "{}", false));
            assertEquals(0, count(db, "SELECT COUNT(*) FROM mod_operation"));
            assertTrue(fixture.actions().isEmpty());
            var stale = runtime.reviewModSchemaRetry(OWNER, Optional.empty());
            sql(db, "UPDATE mod_registry SET revision=revision+1");
            assertThrows(ApiException.class, () -> runtime.retryModSchema(stale));
            sql(db, "UPDATE mod_registry SET revision=revision-1");
            assertEquals(generation, text(runtime, "SELECT generation FROM mod_schema"));
        }
    }

    @Test
    void unknownUsedOrAlreadyValidatedTargetCannotBeRebuilt() throws Exception {
        try (var runtime = failedInitialization()) {
            var db = runtime.persistence().database();
            sql(db, "UPDATE mod_schema SET state='UNKNOWN'");
            assertThrows(Exception.class, () -> runtime.reviewModSchemaRetry(OWNER, Optional.empty()));
            sql(db, "UPDATE mod_schema SET state='FAILED',ever_used=TRUE");
            assertThrows(Exception.class, () -> runtime.reviewModSchemaRetry(OWNER, Optional.empty()));
            sql(db, "UPDATE mod_schema SET ever_used=FALSE,validated_at=1,validation_digest='" + "1".repeat(64) + "'");
            assertThrows(Exception.class, () -> runtime.reviewModSchemaRetry(OWNER, Optional.empty()));
            assertTrue(fixture.actions().isEmpty());
        }
    }

    @Test
    void archiveFailureOnlyRetriesOriginalAcknowledgement() throws Exception {
        var runtime = failedInitialization();
        try {
            initial.module("valid", false);
            var review = runtime.reviewModSchemaRetry(OWNER, Optional.empty());
            sql(runtime.persistence().database(),
                "ALTER TABLE runtime_log ADD CONSTRAINT fail_retry_archive CHECK(event_code<>'MOD_SCHEMA_INITIALIZED')");
            assertThrows(Exception.class, () -> runtime.retryModSchema(review));
            assertEquals("PREPARING", text(runtime, "SELECT state FROM mod_schema"));
            assertEquals(1, count(runtime.persistence().database(), "SELECT COUNT(*) FROM mod_operation"));
            assertThrows(Exception.class, runtime::close);
            sql(runtime.persistence().database(), "ALTER TABLE runtime_log DROP CONSTRAINT fail_retry_archive");
            assertEquals(Outcome.SUCCEEDED, runtime.retryModSchema(review));
            assertEquals(List.of("schema:initialize", "schema:validate"), fixture.actions());
            assertEquals("READY", text(runtime, "SELECT state FROM mod_schema"));
        } finally {
            runtime.close();
        }
    }

    @Test
    void leakedRetryWriterBlocksExitAndOnlyFinishesOriginalFailureAfterRelease() throws Exception {
        var runtime = failedInitialization();
        try {
            initial.module("writerLeak", false);
            var review = runtime.reviewModSchemaRetry(OWNER, Optional.empty());
            assertThrows(Exception.class, () -> runtime.retryModSchema(review));
            assertThrows(Exception.class, runtime::close);
            var scope = (SchemaAccess) System.getProperties().get(PlatformModInitializationTest.SCOPE);
            assertThrows(SQLException.class, scope::openConnection);
            ((Connection) System.getProperties().remove(PlatformModInitializationTest.CONNECTION)).close();
            assertEquals(Outcome.FAILED, runtime.retryModSchema(review));
            assertEquals(List.of("schema:initialize"), fixture.actions());
            assertEquals("FAILED", text(runtime, "SELECT state FROM mod_schema"));
        } finally {
            initial.cleanup();
            runtime.close();
        }
    }

    @Test
    void confirmedMigrationRetryUsesCurrentProgramAndKeepsOriginalSource() throws Exception {
        var migration = new PlatformModMigrationTest();
        migration.root = root;
        migration.initializeOriginalBusinessData();
        migration.upgrade("secondFailure");
        fixture = migration.fixture;
        try (var runtime = fixture.open(true)) {
            assertTrue(fixture.start(runtime).running().isEmpty());
            assertEquals("FAILED", text(runtime, "SELECT state FROM mod_schema WHERE schema_version='3'"));
            String old = text(runtime, "SELECT generation FROM mod_schema WHERE schema_version='3'");
            String source = SchemaSnapshot.digest(runtime.persistence().database(),
                new SchemaCoordinates(migration.original));
            migration.buildUpgrade("valid");
            Files.writeString(fixture.trace, "");
            assertThrows(Exception.class, () -> runtime.reviewModSchemaRetry(OWNER, Optional.empty()));
            var review = runtime.reviewModSchemaRetry(OWNER, Optional.of(migration.original));
            assertEquals(Outcome.SUCCEEDED, runtime.retryModSchema(review));
            assertNotEquals(old, text(runtime, "SELECT generation FROM mod_schema WHERE schema_version='3'"));
            assertEquals(source,
                SchemaSnapshot.digest(runtime.persistence().database(), new SchemaCoordinates(migration.original)));
            assertEquals(List.of("migration:1->2", "migration:2->3", "schema:validate"), fixture.actions());
            assertEquals(2, count(runtime.persistence().database(), "SELECT COUNT(*) FROM mod_schema"));
            assertEquals(0, count(runtime.persistence().database(), "SELECT COUNT(*) FROM mod_operation"));
        }
    }

    @Test
    void interruptionBeforeResetKeepsFailedResidueAndRequiresAnotherExplicitRetry() throws Exception {
        ModRestartLedger.Preparation accepted;
        String physical;
        try (var runtime = failedInitialization()) {
            initial.module("valid", false);
            var review = runtime.reviewModSchemaRetry(OWNER, Optional.empty());
            accepted = runtime.modIntents().acceptRetry(review);
            physical = new SchemaCoordinates(accepted.target()).physicalName();
            assertEquals(1, count(runtime.persistence().database(), "SELECT COUNT(*) FROM mod_operation"));
            assertEquals(2, count(runtime.persistence().database(), "SELECT COUNT(*) FROM \"" + physical + "\".item"));
        }
        try (var runtime = fixture.open(true)) {
            assertEquals("UNKNOWN", text(runtime, "SELECT state FROM mod_schema"));
            assertTrue(fixture.start(runtime).running().isEmpty());
            assertTrue(fixture.actions().isEmpty());
            assertEquals(Outcome.FAILED, runtime.reconcileModSchema(accepted.operation()));
            assertTrue(fixture.actions().isEmpty());
            assertEquals("FAILED", text(runtime, "SELECT state FROM mod_schema"));
            assertEquals(2, count(runtime.persistence().database(), "SELECT COUNT(*) FROM \"" + physical + "\".item"));
            assertEquals(Outcome.SUCCEEDED,
                runtime.retryModSchema(runtime.reviewModSchemaRetry(OWNER, Optional.empty())));
        }
        assertEquals(List.of("schema:initialize", "schema:validate"), fixture.actions());
    }

    @Test
    void interruptionAfterNewTargetCreationUsesReadOnlyReconciliationWithoutAnotherReset() throws Exception {
        ModRestartLedger.Preparation accepted;
        try (var runtime = failedInitialization()) {
            initial.module("valid", false);
            accepted = runtime.modIntents().acceptRetry(runtime.reviewModSchemaRetry(OWNER, Optional.empty()));
            var db = runtime.persistence().database();
            var ledger = new ModRestartLedger(db);
            ledger.preparationPhase(accepted, false);
            new SchemaProvisioner(db).resetFailedTarget(accepted.target(), accepted.operation());
            ledger.resetCompleted(accepted);
            new SchemaProvisioner(db).create(accepted.target());
            ledger.created(accepted, new SchemaCoordinates(accepted.target()));
            try (var writer = new SchemaAccessScope(db, accepted.target(), accepted.writer(), false);
                 var c = writer.openConnection();
                 var s = c.createStatement()) {
                s.execute("CREATE TABLE item(id INT PRIMARY KEY,value_text VARCHAR NOT NULL)");
                s.execute("INSERT INTO item VALUES(1,'retry result'),(2,'retained')");
            }
            ledger.preparationPhase(accepted, true);
        }
        try (var runtime = fixture.open(true)) {
            fixture.start(runtime);
            assertEquals(Outcome.SUCCEEDED, runtime.reconcileModSchema(accepted.operation()));
            assertEquals(accepted.target().generation().toString(), text(runtime, "SELECT generation FROM mod_schema"));
            assertEquals(List.of("schema:validate"), fixture.actions());
            assertEquals(0, count(runtime.persistence().database(), "SELECT COUNT(*) FROM mod_operation"));
            assertEquals(0, count(runtime.persistence().database(),
                "SELECT COUNT(*) FROM runtime_log WHERE event_code='MOD_ACTIVATION_INTERRUPTED'"));
        }
    }

    @Test
    void actualFailedTargetSessionBlocksResetBeforeGenerationChanges() throws Exception {
        try (var runtime = failedInitialization()) {
            var db = runtime.persistence().database();
            var review = runtime.reviewModSchemaRetry(OWNER, Optional.empty());
            var target = new SchemaCoordinates(review.target());
            try (var old = db.openInternalConnection(target.physicalName(), target.databaseUser(),
                db.schemaPassword(target.databaseUser()), "fixture-original-target")) {
                try (var s = old.createStatement(); var user = s.executeQuery("SELECT CURRENT_USER")) {
                    user.next();
                    assertEquals(target.databaseUser(), user.getString(1));
                }
                assertEquals(1,
                    count(db,
                        "SELECT COUNT(*) FROM information_schema.sessions WHERE LOWER(user_name)='"
                            + target.databaseUser() + "'"),
                    text(runtime, "SELECT LISTAGG(user_name,',') FROM information_schema.sessions"));
                assertThrows(ApiException.class, () -> runtime.retryModSchema(review));
                assertEquals(review.target().generation().toString(),
                    text(runtime, "SELECT generation FROM mod_schema"));
                try (var s = old.createStatement(); var rows = s.executeQuery("SELECT COUNT(*) FROM item")) {
                    rows.next();
                    assertEquals(2, rows.getInt(1));
                }
            }
            assertTrue(fixture.actions().isEmpty());
            assertEquals(0, count(db, "SELECT COUNT(*) FROM mod_operation"));
        }
    }
}
