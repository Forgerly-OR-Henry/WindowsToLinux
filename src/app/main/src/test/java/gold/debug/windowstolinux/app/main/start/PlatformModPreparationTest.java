package gold.debug.windowstolinux.app.main.start;

import static gold.debug.windowstolinux.app.main.start.PlatformModIntentsTest.*;
import static gold.debug.windowstolinux.app.main.start.PlatformTaskAdmissionIntegrationTest.*;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.*;

import gold.debug.windowstolinux.app.db.mod.ModRestartLedger;
import gold.debug.windowstolinux.app.db.task.TaskLedger;
import gold.debug.windowstolinux.app.db.task.TaskResource;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class PlatformModPreparationTest {
    @TempDir
    Path root;

    PlatformModInitializationTest initial;

    static final String OWNER = PlatformModInitializationTest.OWNER;

    @BeforeEach
    void setup() throws Exception {
        initial = new PlatformModInitializationTest();
        initial.root = root;
        initial.setup();
        initial.module("valid", false);
    }

    @AfterEach
    void cleanup() throws Exception {
        initial.cleanup();
    }

    PlatformRuntime waiting() throws Exception {
        var runtime = initial.fixture.open(true);
        try {
            var report = initial.fixture.start(runtime);
            assertTrue(report.running().isEmpty());
            assertEquals("MOD_DATA_NOT_READY", report.problems().getFirst().eventCode());
            assertTrue(initial.fixture.actions().isEmpty());
            assertEquals("TRUE", text(runtime, "SELECT enabled FROM mod_registry"));
            assertEquals("STOPPED", text(runtime, "SELECT last_runtime_state FROM mod_registry"));
            assertEquals(0, count(runtime.persistence().database(), "SELECT COUNT(*) FROM mod_schema"));
            assertEquals(0, count(runtime.persistence().database(), "SELECT COUNT(*) FROM mod_operation"));
            return runtime;
        } catch (Exception | Error failure) {
            runtime.close();
            throw failure;
        }
    }

    @Test
    void firstAutomaticChoiceWaitsForExplicitPreparationAndOnlyNextBootRunsAuthorCode() throws Exception {
        try (var runtime = waiting()) {
            var service = runtime.modIntents();
            service.apply(service.review(OWNER, choice(true, VERSION), Optional.empty()), false);
            assertEquals(0, count(runtime.persistence().database(), "SELECT COUNT(*) FROM mod_operation"));
            var review = service.reviewPreparation(OWNER, Optional.empty());
            assertEquals(Map.of(OWNER, choice(true, VERSION)), review.choices());
            assertEquals(Set.of(OWNER), review.affected());
            assertEquals(0, count(runtime.persistence().database(), "SELECT COUNT(*) FROM mod_operation"));
            service.apply(review, false);
            assertTrue(initial.fixture.actions().isEmpty());
            assertEquals("SWITCH", text(runtime, "SELECT operation_type FROM mod_operation"));
            assertEquals("WAIT_RESTART", text(runtime, "SELECT phase FROM mod_operation"));
            assertTrue(new ModRestartLedger(runtime.persistence().database()).needsPreparation(
                service.list().getFirst().operations().getFirst().id()));
            assertThrows(ApiException.class, () -> service.reviewPreparation(OWNER, Optional.empty()));
        }
        try (var runtime = initial.fixture.open(false)) {
            var report = initial.fixture.start(runtime);
            assertTrue(report.problems().isEmpty(), report.problems().toString());
            assertEquals(Set.of(OWNER), report.running());
            assertEquals("READY", text(runtime, "SELECT state FROM mod_schema"));
            assertEquals(0, count(runtime.persistence().database(), "SELECT COUNT(*) FROM mod_operation"));
            assertEquals(List.of("schema:initialize", "schema:validate", OWNER + ":construct", OWNER + ":start"), initial.fixture.actions());
        }
    }

    @Test
    void cancellingPreparationPreservesEnabledVersionAndDoesNotRunOrPrepareOnRestart() throws Exception {
        try (var runtime = waiting()) {
            var service = runtime.modIntents();
            var request = service.apply(service.reviewPreparation(OWNER, Optional.empty()), false);
            service.apply(service.reviewCancellation(request), false);
            assertEquals("TRUE", text(runtime, "SELECT enabled FROM mod_registry"));
            assertEquals(VERSION, text(runtime, "SELECT selected_version FROM mod_registry"));
            assertEquals(0, count(runtime.persistence().database(), "SELECT COUNT(*) FROM mod_operation"));
            assertTrue(initial.fixture.actions().isEmpty());
        }
        try (var runtime = initial.fixture.open(true)) {
            assertEquals("MOD_DATA_NOT_READY", initial.fixture.start(runtime).problems().getFirst().eventCode());
            assertNotNull(runtime.modIntents().reviewPreparation(OWNER, Optional.empty()));
            assertTrue(initial.fixture.actions().isEmpty());
        }
    }

    @Test
    void preparationRejectsLockStaleReviewAndCrossOwnerWorkWithoutChangingSelection() throws Exception {
        try (var runtime = waiting()) {
            var db = runtime.persistence().database();
            var service = runtime.modIntents();
            runtime.security().lock();
            assertThrows(gold.debug.windowstolinux.app.service.error.ApplicationServiceException.class,
                () -> service.reviewPreparation(OWNER, Optional.empty()));
            runtime.security().unlock(PASSWORD.toCharArray());
            var review = service.reviewPreparation(OWNER, Optional.empty());
            var tasks = new TaskLedger(db);
            var task = tasks.enqueue(UUID.randomUUID(), "other.mod", VERSION, work(),
                List.of(new TaskResource("MOD", "other.mod", "other.mod", false),
                    new TaskResource("MOD", OWNER, "other.mod", false)));
            assertTrue(tasks.tryStart(task));
            try {
                assertThrows(ApiException.class, () -> service.apply(review, false));
                assertThrows(ApiException.class, () -> service.reviewPreparation(OWNER, Optional.empty()));
            } finally {
                assertTrue(tasks.complete(task, TaskResult.SUCCEEDED, "{}", false));
            }
            assertEquals("TRUE", text(runtime, "SELECT enabled FROM mod_registry"));
            assertEquals(0, count(db, "SELECT COUNT(*) FROM mod_operation"));
        }
    }

    @Test
    void ledgerRejectsSameChoiceWithoutAnActualPreparationPlan() throws Exception {
        try (var runtime = waiting()) {
            var service = runtime.modIntents();
            service.apply(service.reviewPreparation(OWNER, Optional.empty()), false);
            var id = service.list().getFirst().operations().getFirst().id();
            String original = text(runtime, "SELECT request_json FROM mod_operation");
            var node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(original);
            ((com.fasterxml.jackson.databind.node.ObjectNode) node).putObject("data").put("mode", "NONE");
            sql(runtime.persistence().database(), "UPDATE mod_operation SET request_json=?", node.toString());
            assertThrows(java.sql.SQLException.class, () -> new ModRestartLedger(runtime.persistence().database()).needsPreparation(id));
            sql(runtime.persistence().database(), "UPDATE mod_operation SET request_json=?", original);
            assertTrue(new ModRestartLedger(runtime.persistence().database()).needsPreparation(id));
        }
    }

    @Test
    void firstPreparationRequiresAnExplicitValidSourceAndCannotReplaceExistingData() throws Exception {
        try (var runtime = waiting()) {
            var service = runtime.modIntents();
            var retained = schema(runtime, OWNER, "2");
            assertEquals("MOD_MIGRATION_SOURCE_REQUIRED", assertThrows(ApiException.class,
                () -> service.reviewPreparation(OWNER, Optional.empty())).problem().eventCode());
            assertEquals("MOD_MIGRATION_PATH_INVALID", assertThrows(ApiException.class,
                () -> service.reviewPreparation(OWNER, Optional.of(retained))).problem().eventCode());
            assertEquals(0, count(runtime.persistence().database(), "SELECT COUNT(*) FROM mod_operation"));
            schema(runtime, OWNER, "1");
            assertEquals("MOD_PREPARATION_UNAVAILABLE", assertThrows(ApiException.class,
                () -> service.reviewPreparation(OWNER, Optional.empty())).problem().eventCode());
            assertTrue(initial.fixture.actions().isEmpty());
        }
    }
}
