package gold.debug.windowstolinux.app.main.startup;

import static gold.debug.windowstolinux.app.main.startup.PlatformModIntentsTest.*;
import static gold.debug.windowstolinux.app.main.startup.PlatformTaskAdmissionIntegrationTest.*;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.TimeUnit;

import gold.debug.windowstolinux.app.db.mod.*;
import gold.debug.windowstolinux.app.db.task.*;
import gold.debug.windowstolinux.app.mod.execution.lifecycle.ModRuntimeSession;
import gold.debug.windowstolinux.app.mod.loader.ModClassLoader;
import gold.debug.windowstolinux.app.mod.version.SemanticVersion;
import gold.debug.windowstolinux.app.ui.mod.ModPageRegistry;
import gold.debug.windowstolinux.appapi.mod.*;
import gold.debug.windowstolinux.appapi.task.*;
import gold.debug.windowstolinux.appapi.ui.UiEnvironment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlatformModRestartTest {
    @TempDir
    Path root;

    PlatformRuntime open() throws Exception {
        var helper = new PlatformModIntentsTest();
        helper.root = root;
        return helper.runtime();
    }

    UUID accept(PlatformRuntime runtime, boolean enabled, String schema) throws Exception {
        add(runtime, OWNER, !enabled, Map.of(), schema);
        if (schema != null)
            schema(runtime, OWNER, schema);
        runtime.modIntents().apply(runtime.modIntents().review(OWNER, choice(enabled, VERSION), Optional.empty()),
                false);
        return UUID.fromString(text(runtime, "SELECT operation_id FROM mod_operation"));
    }

    void previousClosed(PlatformRuntime runtime) throws Exception {
        sql(runtime.persistence().database(),
                "UPDATE mod_registry SET last_runtime_state='STOPPED',actual_version=NULL");
    }

    ModClassLoader loader(boolean schema) throws Exception {
        var helper = new PlatformModContextTest();
        helper.root = root;
        return helper.loader(schema);
    }

    ModRuntimeSession session(PlatformRuntime runtime, ModClassLoader loader, ModRestartLedger.Application prepared)
            throws Exception {
        runtime.setInteractionPresenter(new PlatformRemoteCapabilitiesTest.Reviews());
        runtime.setSourcePicker((title, task, cancel) -> root);
        var pages = new ModPageRegistry(new PlatformModContextTest.Host(),
                new UiEnvironment(Locale.SIMPLIFIED_CHINESE, "LIGHT", false));
        return runtime.modSession(loader, prepared.schema(), ModLaunchMode.NORMAL, pages,
                SemanticVersion.parse("1.0.0-dev.1"));
    }

    void archive(PlatformRuntime runtime, UUID id, String archive, String outcome) throws Exception {
        assertEquals(0, count(runtime.persistence().database(), "SELECT COUNT(*) FROM mod_operation"));
        assertEquals(archive, text(runtime, "SELECT log_id FROM runtime_log WHERE operation_id='" + id + "'"));
        assertEquals(outcome, text(runtime, "SELECT outcome FROM runtime_log WHERE operation_id='" + id + "'"));
    }

    @Test
    void newBootStartsRealClassAndArchiveFailureRetriesOnlyTheOriginalSummary() throws Exception {
        UUID operation;
        String archive;
        try (var runtime = open()) {
            operation = accept(runtime, true, null);
            archive = text(runtime, "SELECT archive_log_id FROM mod_operation");
            assertThrows(SQLException.class,
                    () -> new ModRestartLedger(runtime.persistence().database()).begin(operation));
            assertEquals("WAIT_RESTART", text(runtime, "SELECT phase FROM mod_operation"));
        }
        try (var runtime = open(); var loader = loader(false)) {
            var ledger = new ModRestartLedger(runtime.persistence().database());
            var prepared = ledger.begin(operation);
            assertThrows(SQLException.class, () -> ledger.begin(operation));
            assertThrows(SQLException.class, () -> ledger.applied(operation));
            assertThrows(SQLException.class, () -> ledger.failed(operation));
            var session = session(runtime, loader, prepared);
            var entry = loader.loadClass("example.admission.Mod");
            entry.getFields();
            try {
                session.start();
                var context = (ModContext) entry.getField("context").get(null);
                assertThrows(ApiException.class, () -> context.tasks().submit(work(), t -> success(null)));
                sql(runtime.persistence().database(),
                        "ALTER TABLE runtime_log ADD CONSTRAINT fixture_archive CHECK(category<>'mod_operation')");
                assertThrows(SQLException.class, () -> ledger.applied(operation));
                assertEquals("RUNNING", text(runtime, "SELECT last_runtime_state FROM mod_registry"));
                assertEquals("APPLYING", text(runtime, "SELECT phase FROM mod_operation"));
                assertNull(text(runtime, "SELECT outcome FROM mod_operation"));
                assertEquals(1, entry.getField("instances").getInt(null));
                sql(runtime.persistence().database(), "ALTER TABLE runtime_log DROP CONSTRAINT fixture_archive");
                assertTrue(ledger.applied(operation));
                archive(runtime, operation, archive, "SUCCEEDED");
                assertFalse(ledger.applied(operation));
                assertEquals(1, entry.getField("instances").getInt(null));
                assertEquals(Outcome.SUCCEEDED, context.tasks().submit(work(), t -> success("actual")).completion()
                        .toCompletableFuture().get(5, TimeUnit.SECONDS).outcome());
                assertEquals("1", text(runtime, "SELECT revision FROM mod_registry"));
                assertNull(text(runtime, "SELECT disable_ack_revision FROM mod_registry"));
            } finally {
                session.stop(StopReason.APPLICATION_EXIT);
            }
            assertEquals(1, entry.getField("stops").getInt(null));
        }
    }

    @Test
    void knownAuthorStartupFailureIsArchivedWithoutChangingDesiredSwitchOrSelection() throws Exception {
        UUID operation;
        String archive;
        try (var runtime = open()) {
            operation = accept(runtime, true, null);
            archive = text(runtime, "SELECT archive_log_id FROM mod_operation");
        }
        try (var runtime = open(); var loader = loader(false)) {
            var ledger = new ModRestartLedger(runtime.persistence().database());
            var prepared = ledger.begin(operation);
            var entry = loader.loadClass("example.admission.Mod");
            entry.getField("failStart").setBoolean(null, true);
            var session = session(runtime, loader, prepared);
            assertThrows(IllegalStateException.class, session::start);
            assertTrue(session.finalized());
            assertEquals("FAILED", text(runtime, "SELECT last_runtime_state FROM mod_registry"));
            assertThrows(SQLException.class, () -> ledger.applied(operation));
            assertTrue(ledger.failed(operation));
            archive(runtime, operation, archive, "FAILED");
            assertEquals("TRUE", text(runtime, "SELECT enabled FROM mod_registry"));
            assertEquals(VERSION, text(runtime, "SELECT selected_version FROM mod_registry"));
            assertNotNull(text(runtime, "SELECT failure_code FROM mod_registry"));
            assertNull(text(runtime, "SELECT disable_ack_revision FROM mod_registry"));
            assertTrue(runtime.modContexts().isEmpty());
            assertTrue(runtime.modSessions().isEmpty());
        }
    }

    @Test
    void disableAcknowledgementAndFixedArchiveRollBackTogether() throws Exception {
        UUID operation;
        String archive;
        try (var runtime = open()) {
            operation = accept(runtime, false, null);
            archive = text(runtime, "SELECT archive_log_id FROM mod_operation");
            previousClosed(runtime);
        }
        try (var runtime = open()) {
            var db = runtime.persistence().database();
            var ledger = new ModRestartLedger(db);
            assertFalse(ledger.begin(operation).enabled());
            sql(db, "ALTER TABLE runtime_log ADD CONSTRAINT fixture_archive CHECK(category<>'mod_operation')");
            assertThrows(SQLException.class, () -> ledger.applied(operation));
            assertNull(text(runtime, "SELECT disable_ack_revision FROM mod_registry"));
            assertEquals("APPLYING", text(runtime, "SELECT phase FROM mod_operation"));
            sql(db, "ALTER TABLE runtime_log DROP CONSTRAINT fixture_archive");
            assertTrue(ledger.applied(operation));
            archive(runtime, operation, archive, "SUCCEEDED");
            assertEquals("1", text(runtime, "SELECT disable_ack_revision FROM mod_registry"));
            assertEquals(db.bootId().toString(), text(runtime, "SELECT last_boot_id FROM mod_registry"));
            assertEquals("FALSE", text(runtime, "SELECT enabled FROM mod_registry"));
            assertEquals("STOPPED", text(runtime, "SELECT last_runtime_state FROM mod_registry"));
        }
    }

    @Test
    void oldRunningAndCrossOwnerResourcesCannotBeMistakenForRestartDisableCompletion() throws Exception {
        UUID operation;
        try (var runtime = open()) {
            operation = accept(runtime, false, null);
        }
        try (var runtime = open()) {
            var db = runtime.persistence().database();
            var ledger = new ModRestartLedger(db);
            assertThrows(SQLException.class, () -> ledger.begin(operation));
            assertNull(text(runtime, "SELECT disable_ack_revision FROM mod_registry"));
            previousClosed(runtime);
            var tasks = new TaskLedger(db);
            var task = tasks.enqueue(UUID.randomUUID(), "other.mod", VERSION, work(),
                    List.of(new TaskResource("MOD", "other.mod", "other.mod", false),
                            new TaskResource("MOD", OWNER, "other.mod", false)));
            assertTrue(tasks.tryStart(task));
            assertThrows(SQLException.class, () -> ledger.begin(operation));
            assertTrue(tasks.complete(task, Outcome.SUCCEEDED, "{}", false));
            ledger.begin(operation);
            sql(db, "UPDATE mod_registry SET revision=revision+1");
            assertThrows(SQLException.class, () -> ledger.applied(operation));
            assertNull(text(runtime, "SELECT disable_ack_revision FROM mod_registry"));
            sql(db, "UPDATE mod_registry SET revision=revision-1");
            sql(db, "UPDATE mod_registry SET last_boot_id=?", db.bootId());
            assertThrows(SQLException.class, () -> ledger.applied(operation));
        }
    }

    @Test
    void databaseReopenMarksInterruptedApplicationUnknownAndDoesNotReplayIt() throws Exception {
        UUID operation;
        String archive;
        try (var runtime = open()) {
            operation = accept(runtime, true, null);
            archive = text(runtime, "SELECT archive_log_id FROM mod_operation");
        }
        try (var runtime = open()) {
            new ModRestartLedger(runtime.persistence().database()).begin(operation);
        }
        try (var runtime = open()) {
            var ledger = new ModRestartLedger(runtime.persistence().database());
            assertEquals("UNKNOWN", text(runtime, "SELECT state FROM mod_operation"));
            assertEquals("APPLYING", text(runtime, "SELECT phase FROM mod_operation"));
            assertNull(text(runtime, "SELECT outcome FROM mod_operation"));
            assertEquals(archive, text(runtime, "SELECT archive_log_id FROM mod_operation"));
            assertThrows(SQLException.class, () -> ledger.begin(operation));
            assertThrows(SQLException.class, () -> ledger.applied(operation));
            assertThrows(SQLException.class, () -> ledger.failed(operation));
            assertThrows(SQLException.class,
                    () -> new ModActivationLedger(runtime.persistence().database(), OWNER, VERSION, Optional.empty())
                            .starting());
        }
    }

    @Test
    void exactReadySchemaIsReusedAndStaleGenerationPreventsAuthorDelivery() throws Exception {
        UUID operation;
        UUID generation;
        try (var runtime = open()) {
            operation = accept(runtime, true, "1");
            generation = UUID.fromString(text(runtime, "SELECT generation FROM mod_schema"));
        }
        try (var runtime = open(); var loader = loader(true)) {
            var db = runtime.persistence().database();
            var ledger = new ModRestartLedger(db);
            sql(db, "UPDATE mod_schema SET generation=?", UUID.randomUUID());
            assertThrows(SQLException.class, () -> ledger.begin(operation));
            assertEquals("WAIT_RESTART", text(runtime, "SELECT phase FROM mod_operation"));
            sql(db, "UPDATE mod_schema SET generation=?", generation);
            var prepared = ledger.begin(operation);
            assertEquals(generation, prepared.schema().orElseThrow().generation());
            var session = session(runtime, loader, prepared);
            try {
                session.start();
                assertEquals("TRUE", text(runtime, "SELECT ever_used FROM mod_schema"));
                assertTrue(ledger.applied(operation));
                assertEquals(generation.toString(), text(runtime, "SELECT generation FROM mod_schema"));
            } finally {
                session.stop(StopReason.APPLICATION_EXIT);
            }
        }
    }

    @Test
    void malformedRequestAndUnpreparedDataNeverEnterApplication() throws Exception {
        UUID operation;
        String request;
        try (var runtime = open()) {
            add(runtime, OWNER, false, Map.of(), "1");
            runtime.modIntents().apply(runtime.modIntents().review(OWNER, choice(true, VERSION), Optional.empty()),
                    false);
            operation = UUID.fromString(text(runtime, "SELECT operation_id FROM mod_operation"));
            request = text(runtime, "SELECT request_json FROM mod_operation");
        }
        try (var runtime = open()) {
            var db = runtime.persistence().database();
            var ledger = new ModRestartLedger(db);
            assertThrows(SQLException.class, () -> ledger.begin(operation));
            for (String invalid : List.of(request + "{}",
                    request.replace("\"formatVersion\":1", "\"formatVersion\":1,\"formatVersion\":1"),
                    request.replace("\"affected\":[\"" + OWNER + "\"]", "\"affected\":[]"))) {
                assertNotEquals(request, invalid);
                sql(db, "UPDATE mod_operation SET request_json=?", invalid);
                assertThrows(SQLException.class, () -> ledger.begin(operation));
                assertEquals("WAIT_RESTART", text(runtime, "SELECT phase FROM mod_operation"));
            }
            sql(db, "UPDATE mod_operation SET request_json=?", request);
            assertEquals(0, count(db, "SELECT COUNT(*) FROM mod_schema"));
            assertNull(text(runtime, "SELECT actual_version FROM mod_registry"));
        }
    }
}
