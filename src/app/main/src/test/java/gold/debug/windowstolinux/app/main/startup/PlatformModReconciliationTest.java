package gold.debug.windowstolinux.app.main.startup;

import static gold.debug.windowstolinux.app.main.startup.PlatformModIntentsTest.*;
import static gold.debug.windowstolinux.app.main.startup.PlatformTaskAdmissionIntegrationTest.*;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.sql.*;
import java.util.*;

import gold.debug.windowstolinux.app.db.mod.*;
import gold.debug.windowstolinux.app.db.execution.schema.*;
import gold.debug.windowstolinux.appapi.db.SchemaAccess;
import gold.debug.windowstolinux.appapi.task.Outcome;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class PlatformModReconciliationTest {
    @TempDir
    Path root;

    PlatformModInitializationTest initial;

    PlatformModCoordinatorTest fixture;

    ModRestartLedger.Preparation plan;

    static final String OWNER = PlatformModInitializationTest.OWNER;

    void interrupted(String mode, boolean create, boolean acknowledge) throws Exception {
        initial = new PlatformModInitializationTest();
        initial.root = root;
        initial.setup();
        initial.module(mode, false);
        fixture = initial.fixture;
        fixture.enable(OWNER);
        try (var runtime = fixture.open(false)) {
            var ledger = new ModRestartLedger(runtime.persistence().database());
            plan = ledger.beginPreparation(UUID.fromString(text(runtime, "SELECT operation_id FROM mod_operation")),
                    text(runtime, "SELECT manifest_json FROM mod_version"),
                    text(runtime, "SELECT content_sha256 FROM mod_version"));
            ledger.preparationPhase(plan, false);
            if (create) {
                var target = new SchemaCoordinates(plan.target());
                new SchemaProvisioner(runtime.persistence().database()).create(target);
                if (acknowledge)
                    ledger.created(plan, target);
                try (var writer = new SchemaAccessScope(runtime.persistence().database(), plan.target(), plan.writer(),
                        false); var c = writer.openConnection(); var s = c.createStatement()) {
                    s.execute("CREATE TABLE item(id INT PRIMARY KEY,value_text VARCHAR NOT NULL)");
                    s.execute("INSERT INTO item VALUES(1,'original'),(2,'retained')");
                }
            }
        }
    }

    @AfterEach
    void cleanLeak() throws Exception {
        if (initial != null)
            initial.cleanup();
    }

    @Test
    void explicitInspectionValidatesWithoutReinitializationAndDefersBusinessUntilNextBoot() throws Exception {
        interrupted("valid", true, true);
        try (var runtime = fixture.open(true)) {
            assertTrue(fixture.start(runtime).running().isEmpty());
            assertTrue(fixture.actions().isEmpty());
            assertEquals(Outcome.SUCCEEDED, runtime.reconcileModSchema(plan.operation()));
            assertEquals("READY", text(runtime, "SELECT state FROM mod_schema"));
            assertEquals("FALSE", text(runtime, "SELECT ever_used FROM mod_schema"));
            assertEquals(List.of("schema:validate"), fixture.actions());
            assertEquals(0, count(runtime.persistence().database(), "SELECT COUNT(*) FROM mod_operation"));
            assertEquals("TRUE", text(runtime, "SELECT enabled FROM mod_registry"));
            assertEquals(VERSION, text(runtime, "SELECT selected_version FROM mod_registry"));
            assertEquals("FAILED", text(runtime, "SELECT last_runtime_state FROM mod_registry"));
        }
        try (var runtime = fixture.open(false)) {
            assertEquals(Set.of(OWNER), fixture.start(runtime).running());
        }
        assertFalse(fixture.actions().contains("schema:initialize"));
        assertEquals(1, fixture.actions().stream().filter("schema:validate"::equals).count());
    }

    @Test
    void authorInvalidResultProvesFailureButRetainsFinalDataAndDoesNotRetry() throws Exception {
        interrupted("invalid", true, true);
        try (var runtime = fixture.open(true)) {
            fixture.start(runtime);
            assertEquals(Outcome.FAILED, runtime.reconcileModSchema(plan.operation()));
            assertEquals("FAILED", text(runtime, "SELECT state FROM mod_schema"));
            assertEquals(2, count(runtime.persistence().database(),
                    "SELECT COUNT(*) FROM \"" + new SchemaCoordinates(plan.target()).physicalName() + "\".item"));
            assertEquals(0, count(runtime.persistence().database(),
                    "SELECT COUNT(*) FROM runtime_log WHERE message LIKE '%private diagnostic%'"));
        }
        try (var runtime = fixture.open(false)) {
            assertTrue(fixture.start(runtime).running().isEmpty());
        }
        assertEquals(List.of("schema:validate"), fixture.actions());
    }

    @Test
    void absentTargetProvesIncompleteWithoutCallingAuthor() throws Exception {
        interrupted("valid", false, false);
        try (var runtime = fixture.open(true)) {
            fixture.start(runtime);
            assertEquals(Outcome.FAILED, runtime.reconcileModSchema(plan.operation()));
            assertEquals("FAILED", text(runtime, "SELECT state FROM mod_schema"));
            assertTrue(fixture.actions().isEmpty());
        }
    }

    @Test
    void unacknowledgedPhysicalTargetRemainsUnknownAndCanBeInspectedAgainWithoutMigration() throws Exception {
        interrupted("valid", true, false);
        try (var runtime = fixture.open(true)) {
            fixture.start(runtime);
            assertEquals(Outcome.UNKNOWN, runtime.reconcileModSchema(plan.operation()));
            assertEquals("UNKNOWN", text(runtime, "SELECT state FROM mod_schema"));
            String original = text(runtime, "SELECT operation_id FROM mod_operation WHERE operation_type='RECONCILE'");
            assertEquals(Outcome.UNKNOWN, runtime.reconcileModSchema(plan.operation()));
            assertEquals(original,
                    text(runtime, "SELECT operation_id FROM mod_operation WHERE operation_type='RECONCILE'"));
            assertTrue(fixture.actions().isEmpty());
        }
    }

    @Test
    void lockedOrPreviouslyUsedDataCannotBeReconciledAsUnused() throws Exception {
        interrupted("valid", true, true);
        try (var runtime = fixture.open(false)) {
            fixture.start(runtime);
            assertThrows(Exception.class, () -> runtime.reconcileModSchema(plan.operation()));
            assertEquals(0, count(runtime.persistence().database(),
                    "SELECT COUNT(*) FROM mod_operation WHERE operation_type='RECONCILE'"));
            runtime.security().unlock(PASSWORD.toCharArray());
            sql(runtime.persistence().database(), "UPDATE mod_schema SET ever_used=TRUE");
            assertThrows(SQLException.class, () -> runtime.reconcileModSchema(plan.operation()));
            assertTrue(fixture.actions().isEmpty());
        }
    }

    @Test
    void archiveFailureRetainsOriginalInspectionAndRetryDoesNotCallAuthorAgain() throws Exception {
        interrupted("valid", true, true);
        var runtime = fixture.open(true);
        try {
            fixture.start(runtime);
            sql(runtime.persistence().database(),
                    "ALTER TABLE runtime_log ADD CONSTRAINT reject_reconciliation CHECK(event_code<>'MOD_SCHEMA_RECONCILED_READY')");
            assertThrows(Exception.class, () -> runtime.reconcileModSchema(plan.operation()));
            assertEquals("UNKNOWN", text(runtime, "SELECT state FROM mod_schema"));
            assertEquals(3, count(runtime.persistence().database(), "SELECT COUNT(*) FROM mod_operation"));
            assertThrows(Exception.class, runtime::close);
            sql(runtime.persistence().database(), "ALTER TABLE runtime_log DROP CONSTRAINT reject_reconciliation");
            assertEquals(Outcome.SUCCEEDED, runtime.reconcileModSchema(plan.operation()));
            assertEquals(List.of("schema:validate"), fixture.actions());
            assertEquals("READY", text(runtime, "SELECT state FROM mod_schema"));
        } finally {
            runtime.close();
        }
    }

    @Test
    void leakedValidationConnectionSealsReaderAndOnlyRetriesOriginalCleanup() throws Exception {
        interrupted("readerLeak", true, true);
        var runtime = fixture.open(true);
        try {
            fixture.start(runtime);
            assertThrows(Exception.class, () -> runtime.reconcileModSchema(plan.operation()));
            var access = (SchemaAccess) System.getProperties().get(PlatformModInitializationTest.SCOPE);
            assertThrows(SQLException.class, access::openConnection);
            assertThrows(Exception.class, runtime::close);
            ((Connection) System.getProperties().remove(PlatformModInitializationTest.CONNECTION)).close();
            assertEquals(Outcome.SUCCEEDED, runtime.reconcileModSchema(plan.operation()));
            assertEquals(List.of("schema:validate"), fixture.actions());
            assertEquals("READY", text(runtime, "SELECT state FROM mod_schema"));
        } finally {
            initial.cleanup();
            runtime.close();
        }
    }

    @Test
    void actualTableConstraintViolationsFailEvenWhenAuthorAccepts() throws Exception {
        interrupted("valid", true, true);
        try (var runtime = fixture.open(true)) {
            sql(runtime.persistence().database(), "ALTER TABLE \"" + new SchemaCoordinates(plan.target()).physicalName()
                    + "\".item ADD CONSTRAINT bad CHECK(id>99) NOCHECK");
            fixture.start(runtime);
            assertEquals(Outcome.FAILED, runtime.reconcileModSchema(plan.operation()));
            assertEquals("FAILED", text(runtime, "SELECT state FROM mod_schema"));
        }
    }

    void interruptedChain() throws Exception {
        var migration = new PlatformModMigrationTest();
        migration.root = root;
        migration.initializeOriginalBusinessData();
        migration.upgrade("valid");
        fixture = migration.fixture;
        try (var runtime = fixture.open(false)) {
            var db = runtime.persistence().database();
            var ledger = new ModRestartLedger(db);
            plan = ledger.beginPreparation(UUID.fromString(text(runtime, "SELECT operation_id FROM mod_operation")),
                    text(runtime,
                            "SELECT manifest_json FROM mod_version WHERE version='" + PlatformModMigrationTest.NEXT
                                    + "'"),
                    text(runtime, "SELECT content_sha256 FROM mod_version WHERE version='"
                            + PlatformModMigrationTest.NEXT + "'"));
            ledger.sourceDigest(plan, SchemaSnapshot.digest(db, new SchemaCoordinates(plan.source().orElseThrow())),
                    false);
            ledger.preparationPhase(plan, false);
            for (var step : plan.steps()) {
                String before = SchemaSnapshot.digest(db, ModRestartLedger.sourceCoordinates(plan, step));
                ledger.beginStep(plan, step, before);
                var target = ModRestartLedger.targetCoordinates(plan, step);
                new SchemaProvisioner(db).create(target);
                ledger.created(plan, target);
                try (var writer = new SchemaAccessScope(db, target, step.writer(), false);
                        var c = writer.openConnection();
                        var s = c.createStatement()) {
                    s.execute("CREATE TABLE item(id INT PRIMARY KEY,value_text VARCHAR NOT NULL,a INT,b INT)");
                    s.execute("INSERT INTO item VALUES(1,'copied',1,2),(2,'retained',NULL,99)");
                }
                if (step.index() < plan.steps().size() - 1) {
                    String receipt;
                    try (var reader = new SchemaAccessScope(db, target, step.reader(), true)) {
                        receipt = SchemaValidation.validate(reader);
                    }
                    ledger.completeStep(plan, step, before,
                            SchemaSnapshot.digest(db, ModRestartLedger.sourceCoordinates(plan, step)), receipt);
                }
            }
            ledger.preparationPhase(plan, true);
        }
        Files.writeString(fixture.trace, "");
    }

    @Test
    void completedMigrationTargetIsValidatedAndOnlyRecordedIntermediateIsRemoved() throws Exception {
        interruptedChain();
        try (var runtime = fixture.open(true)) {
            fixture.start(runtime);
            assertEquals(Outcome.SUCCEEDED, runtime.reconcileModSchema(plan.operation()));
            assertEquals("READY", text(runtime, "SELECT state FROM mod_schema WHERE schema_version='3'"));
            assertEquals("READY", text(runtime, "SELECT state FROM mod_schema WHERE schema_version='1'"));
            assertEquals(0, count(runtime.persistence().database(),
                    "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name LIKE 't\\_%' ESCAPE '\\'"));
            assertEquals(List.of("schema:validate"), fixture.actions());
            assertEquals(0, count(runtime.persistence().database(), "SELECT COUNT(*) FROM mod_migration_step"));
        }
    }

    @Test
    void changedOriginalSourceRemainsUnknownWithoutAuthorValidationOrIntermediateDeletion() throws Exception {
        interruptedChain();
        try (var runtime = fixture.open(true)) {
            sql(runtime.persistence().database(),
                    "UPDATE \"" + new SchemaCoordinates(plan.source().orElseThrow()).physicalName()
                            + "\".item SET value_text='changed' WHERE id=1");
            fixture.start(runtime);
            assertEquals(Outcome.UNKNOWN, runtime.reconcileModSchema(plan.operation()));
            assertEquals("UNKNOWN", text(runtime, "SELECT state FROM mod_schema WHERE schema_version='3'"));
            assertEquals(1, count(runtime.persistence().database(),
                    "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name LIKE 't\\_%' ESCAPE '\\'"));
            assertTrue(fixture.actions().isEmpty());
        }
    }

    @Test
    void interruptedInspectionReusesOriginalIdentityAndReleasesItsRecordedReader() throws Exception {
        interrupted("valid", true, true);
        UUID operation, reader;
        try (var runtime = fixture.open(true)) {
            var ledger = new ModSchemaReconciliation(runtime.persistence().database());
            var attempt = ledger.begin(ledger.review(plan.operation()));
            operation = attempt.operation();
            reader = attempt.reader();
            String user = SchemaAccessScope.readerUser(reader),
                    schema = new SchemaCoordinates(plan.target()).physicalName();
            sql(runtime.persistence().database(), "CREATE USER \"" + user + "\" PASSWORD 'fixture'");
            sql(runtime.persistence().database(), "GRANT SELECT ON \"" + schema + "\".item TO \"" + user + "\"");
        }
        try (var runtime = fixture.open(true)) {
            assertEquals("UNKNOWN", text(runtime, "SELECT state FROM mod_operation WHERE operation_type='RECONCILE'"));
            fixture.start(runtime);
            assertEquals(Outcome.SUCCEEDED, runtime.reconcileModSchema(plan.operation()));
            assertEquals(1, count(runtime.persistence().database(),
                    "SELECT COUNT(*) FROM runtime_log WHERE event_code='MOD_SCHEMA_RECONCILED_READY' AND operation_id='"
                            + operation + "'"));
            assertEquals(0,
                    count(runtime.persistence().database(),
                            "SELECT COUNT(*) FROM information_schema.users WHERE user_name='"
                                    + SchemaAccessScope.readerUser(reader) + "'"));
            assertEquals(List.of("schema:validate"), fixture.actions());
        }
    }

    @Test
    void readerWithUnexpectedRightsIsRetainedUntilTheOriginalRightsAreRestored() throws Exception {
        interrupted("valid", true, true);
        var runtime = fixture.open(true);
        String user = SchemaAccessScope.readerUser(plan.reader()),
                schema = new SchemaCoordinates(plan.target()).physicalName();
        try {
            sql(runtime.persistence().database(), "CREATE USER \"" + user + "\" PASSWORD 'fixture'");
            sql(runtime.persistence().database(), "GRANT SELECT,UPDATE ON \"" + schema + "\".item TO \"" + user + "\"");
            fixture.start(runtime);
            assertThrows(Exception.class, () -> runtime.reconcileModSchema(plan.operation()));
            assertEquals("UNKNOWN", text(runtime, "SELECT state FROM mod_schema"));
            assertTrue(fixture.actions().isEmpty());
            assertEquals(1, count(runtime.persistence().database(),
                    "SELECT COUNT(*) FROM information_schema.users WHERE user_name='" + user + "'"));
            sql(runtime.persistence().database(), "REVOKE UPDATE ON \"" + schema + "\".item FROM \"" + user + "\"");
            assertEquals(Outcome.UNKNOWN, runtime.reconcileModSchema(plan.operation()));
            assertTrue(fixture.actions().isEmpty());
            assertEquals(Outcome.SUCCEEDED, runtime.reconcileModSchema(plan.operation()));
            assertEquals(List.of("schema:validate"), fixture.actions());
        } finally {
            runtime.close();
        }
    }

    @Test
    void crossOwnerWorkAndChangedReviewPreventClaimBeforeAnyAuthorCall() throws Exception {
        interrupted("valid", true, true);
        try (var runtime = fixture.open(true)) {
            var db = runtime.persistence().database();
            var ledger = new ModSchemaReconciliation(db);
            var review = ledger.review(plan.operation());
            var tasks = new gold.debug.windowstolinux.app.db.task.TaskLedger(db);
            var task = tasks.enqueue(UUID.randomUUID(), "other.mod", VERSION, work(),
                    List.of(new gold.debug.windowstolinux.app.db.task.TaskResource("MOD", "other.mod", "other.mod",
                            false),
                            new gold.debug.windowstolinux.app.db.task.TaskResource("MOD", OWNER, "other.mod", false)));
            assertTrue(tasks.tryStart(task));
            assertThrows(SQLException.class, () -> ledger.begin(review));
            assertTrue(tasks.complete(task, Outcome.SUCCEEDED, "{}", false));
            sql(db, "UPDATE mod_registry SET revision=revision+1");
            assertThrows(SQLException.class, () -> ledger.begin(review));
            sql(db, "UPDATE mod_registry SET revision=revision-1");
            fixture.start(runtime);
            assertEquals(Outcome.SUCCEEDED, runtime.reconcileModSchema(plan.operation()));
            assertEquals(List.of("schema:validate"), fixture.actions());
        }
    }
}
