package gold.debug.windowstolinux.app.main.start;

import static gold.debug.windowstolinux.app.main.start.PlatformTaskAdmissionIntegrationTest.*;
import static org.junit.jupiter.api.Assertions.*;

import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import gold.debug.windowstolinux.app.db.log.*;
import gold.debug.windowstolinux.app.db.schema.SchemaCoordinates;
import gold.debug.windowstolinux.appapi.task.log.LogCategory;
import gold.debug.windowstolinux.appapi.task.log.LogEvent;
import gold.debug.windowstolinux.appapi.task.log.LogLevel;
import gold.debug.windowstolinux.appapi.task.log.LogStorage;
import gold.debug.windowstolinux.appapi.task.log.LogTarget;
import gold.debug.windowstolinux.appapi.task.log.LogWriteResult;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlatformLogServiceTest {
    @TempDir
    Path root;

    static final LogCategory CATEGORY = new LogCategory("runtime", "Runtime", Optional.empty());

    static LogEvent event(String message) {
        return new LogEvent(LogLevel.INFO, "TEST_EVENT", message, Optional.empty(), Optional.empty(), Optional.empty(),
            Optional.empty());
    }

    static LogStore.Target target(PlatformRuntime runtime, LogTarget handle) throws Exception {
        return new LogStore(runtime.persistence().database()).targets().stream()
            .filter(t -> t.key().equals(handle.key())).findFirst().orElseThrow();
    }

    static Path file(Path root, LogTarget target) {
        return root.resolve("log/emergency").resolve(target.key() + ".log");
    }

    public static final class RejectWrite implements org.h2.api.Trigger {
        static final AtomicBoolean reject = new AtomicBoolean(true);

        public void fire(Connection connection, Object[] oldRow, Object[] newRow) throws SQLException {
            if (reject.get())
                throw new SQLException("fixture unavailable");
        }
    }

    static void trigger(PlatformRuntime runtime, String when) throws Exception {
        RejectWrite.reject.set(true);
        sql(runtime.persistence().database(), "CREATE TRIGGER fixture_logs BEFORE " + when
            + " ON runtime_log FOR EACH ROW CALL '" + RejectWrite.class.getName() + "'");
    }

    @Test
    void realOwnerCategoriesRetainHundredThenTenAndNeverCreateNormalLogFiles() throws Exception {
        try (var runtime = PlatformRuntime.open(root, PlatformStartupTest.PROTECTION);
             var first = runtime.capabilities().logs(OWNER, VERSION, Optional.empty());
             var second = runtime.capabilities().logs("example.other", VERSION, Optional.empty())) {
            runtime.security().setupAndUnlock(PASSWORD.toCharArray(), PASSWORD.toCharArray());
            var a = first.register(CATEGORY);
            var b = second.register(CATEGORY);
            assertSame(a, first.register(CATEGORY));
            assertThrows(ApiException.class,
                () -> first.register(new LogCategory("runtime", "different label", Optional.empty())));
            assertThrows(ApiException.class,
                () -> first.register(new LogCategory("per_task", "Different", Optional.empty())));
            assertEquals(LogStorage.NOT_SAVED, second.write(a, event("foreign")).storage());
            assertEquals(LogStorage.NOT_SAVED, first.write(new LogTarget(a.key()), event("copied UUID")).storage());
            for (int i = 0; i < 105; i++)
                assertEquals(LogStorage.DATABASE, first.write(a, event("record-" + i)).storage());
            assertEquals(LogStorage.DATABASE, second.write(b, event("other owner")).storage());
            assertFalse(Files.exists(root.resolve("log")), "Ordinary database writes must not create log copies");
            var repository = new LogStore(runtime.persistence().database());
            assertEquals(100, repository.read(target(runtime, a)).size());
            var report = runtime.logs().cleanHistory();
            assertTrue(report.stream().allMatch(r -> r.problem().isEmpty()));
            var aReport = report.stream().filter(r -> r.target().key().equals(a.key())).findFirst().orElseThrow();
            assertEquals(90L, aReport.databaseRemoved().orElseThrow());
            assertEquals(10L, aReport.databaseAfter().orElseThrow());
            assertEquals(10, repository.read(target(runtime, a)).size());
            assertEquals(1, repository.read(target(runtime, b)).size());
            assertEquals(6, repository.targets().size());
            assertFalse(Files.exists(root.resolve("log")));
        }
    }

    @Test
    void interruptedReplacementEndingAtACompleteLineNeverReplacesTheOriginalFile() throws Exception {
        try (var runtime = PlatformRuntime.open(root, PlatformStartupTest.PROTECTION);
             var logger = runtime.capabilities().logs(OWNER, VERSION, Optional.empty())) {
            runtime.security().setupAndUnlock(PASSWORD.toCharArray(), PASSWORD.toCharArray());
            var handle = logger.register(CATEGORY);
            var target = target(runtime, handle);
            var original = LogRecordCodec.create(target, VERSION, event("original retained"));
            var next = LogRecordCodec.create(target, VERSION, event("first line before interrupted replacement"));
            new EmergencyLogs(root).append(target, original);
            var active = file(root, handle);
            byte[] originalBytes = Files.readAllBytes(active);
            var pending = active.resolveSibling(handle.key() + ".pending");
            byte[] partialReplacement = LogRecordCodec.encode(target.key(), next);
            Files.write(pending, partialReplacement);
            assertThrows(java.io.IOException.class, () -> new EmergencyLogs(root).read(target));
            var report = runtime.logs().cleanHistory().stream().filter(r -> r.target().key().equals(handle.key()))
                .findFirst().orElseThrow();
            assertTrue(report.problem().isPresent());
            assertTrue(report.fileAfter().isEmpty());
            var result = logger.write(handle, event("new database diagnostic"));
            assertEquals(LogStorage.DATABASE, result.storage());
            assertTrue(result.problem().isPresent());
            assertArrayEquals(originalBytes, Files.readAllBytes(active));
            assertArrayEquals(partialReplacement, Files.readAllBytes(pending));
            assertEquals(1, new LogStore(runtime.persistence().database()).read(target).size());
        }
    }

    @Test
    void utf8EnvelopeIsBoundedAndSanitizedBeforeSqlAndDebugResetsOnReopen() throws Exception {
        try (var runtime = PlatformRuntime.open(root, PlatformStartupTest.PROTECTION);
             var logger = runtime.capabilities().logs(OWNER, VERSION, Optional.empty())) {
            runtime.security().setupAndUnlock(PASSWORD.toCharArray(), PASSWORD.toCharArray());
            var handle = logger.register(CATEGORY);
            var detail = "password=SECRET123 token:ABC999 Authorization: Bearer BEARER987 https://user:pw@example.invalid/private \"api_key\":\"KEYSECRET\"\n-----BEGIN PRIVATE KEY-----\nPRIVATEBYTES\n-----END PRIVATE KEY-----\n"
                + "😀\n\\\"".repeat(20000);
            var result = logger.write(handle, new LogEvent(LogLevel.INFO, "ENCODING", "中文😀".repeat(1000),
                Optional.of(detail), Optional.empty(), Optional.empty(), Optional.of(TaskResult.SUCCEEDED)));
            assertEquals(LogStorage.DATABASE, result.storage(), result.toString());
            assertTrue(result.truncated());
            var t = target(runtime, handle);
            var record = new LogStore(runtime.persistence().database()).read(t).getFirst();
            byte[] encoded = LogRecordCodec.encode(t.key(), record);
            assertTrue(encoded.length <= 16384);
            assertTrue(record.message().getBytes(StandardCharsets.UTF_8).length <= 2048);
            String text = new String(encoded, StandardCharsets.UTF_8);
            for (var secret : List.of("SECRET123", "ABC999", "BEARER987", "KEYSECRET", "PRIVATEBYTES", "user:pw"))
                assertFalse(text.contains(secret), secret);
            assertEquals(record, LogRecordCodec.decode(t, encoded));
            var debug = new LogEvent(LogLevel.DEBUG, "DEBUG_TEST", "diagnostic", Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty());
            assertEquals(LogStorage.NOT_SAVED, logger.write(handle, debug).storage());
            runtime.logs().debug(true);
            assertEquals(LogStorage.DATABASE, logger.write(handle, debug).storage());
            assertEquals(
                LogStorage.NOT_SAVED, logger
                    .write(handle,
                        new LogEvent(LogLevel.INFO, "UNKNOWN", "unfinished", Optional.empty(),
                            Optional.empty(), Optional.empty(), Optional.of(TaskResult.UNKNOWN)))
                    .storage());
        }
        try (var runtime = PlatformRuntime.open(root, PlatformStartupTest.PROTECTION);
             var logger = runtime.capabilities().logs(OWNER, VERSION, Optional.empty())) {
            var handle = logger.register(CATEGORY);
            assertEquals(LogStorage.NOT_SAVED, logger.write(handle, new LogEvent(LogLevel.DEBUG, "DEBUG_TEST",
                "not retained", Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty())).storage());
            assertEquals(2, new LogStore(runtime.persistence().database()).read(target(runtime, handle)).size());
        }
    }

    @Test
    void failedDatabaseWriteUsesOriginalEmergencyIdentityAndRealReopenBackfillsExactlyOnce() throws Exception {
        UUID id;
        long recorded;
        LogTarget original;
        try (var runtime = PlatformRuntime.open(root, PlatformStartupTest.PROTECTION);
             var logger = runtime.capabilities().logs(OWNER, VERSION, Optional.empty())) {
            original = logger.register(CATEGORY);
            trigger(runtime, "INSERT");
            try {
                var result = logger.write(original, event("recover me"));
                id = result.logId();
                assertEquals(LogStorage.EMERGENCY_FILE, result.storage(), result.toString());
                var pending = new EmergencyLogs(root).read(target(runtime, original));
                assertEquals(id, pending.getFirst().id());
                recorded = pending.getFirst().recordedAt();
                assertEquals(0, count(runtime.persistence().database(), "SELECT COUNT(*) FROM runtime_log"));
            } finally {
                sql(runtime.persistence().database(), "DROP TRIGGER fixture_logs");
            }
        }
        try (var runtime = PlatformRuntime.open(root, PlatformStartupTest.PROTECTION);
             var logger = runtime.capabilities().logs(OWNER, VERSION, Optional.empty())) {
            var handle = logger.register(CATEGORY);
            assertEquals(original.key(), handle.key());
            var target = target(runtime, handle);
            var repository = new LogStore(runtime.persistence().database());
            var records = repository.read(target);
            assertEquals(1, records.size());
            assertEquals(id, records.getFirst().id());
            assertEquals(recorded, records.getFirst().recordedAt());
            assertTrue(new EmergencyLogs(root).read(target).isEmpty());
            assertTrue(runtime.logs().replayAll().stream().allMatch(r -> r.problem().isEmpty()));
            assertEquals(1, repository.read(target).size());
        }
    }

    @Test
    void insertAndRetentionRollbackTogetherAndEmergencyCopyCanRecover() throws Exception {
        try (var runtime = PlatformRuntime.open(root, PlatformStartupTest.PROTECTION);
             var logger = runtime.capabilities().logs(OWNER, VERSION, Optional.empty())) {
            var handle = logger.register(CATEGORY);
            var target = target(runtime, handle);
            var repository = new LogStore(runtime.persistence().database());
            for (int i = 0; i < 100; i++)
                assertEquals(LogStorage.DATABASE, logger.write(handle, event("before-" + i)).storage());
            var before = repository.read(target);
            trigger(runtime, "DELETE");
            LogWriteResult failed;
            try {
                failed = logger.write(handle, event("new after failed pruning"));
                assertEquals(LogStorage.EMERGENCY_FILE, failed.storage(), failed.toString());
                assertEquals(before, repository.read(target));
            } finally {
                sql(runtime.persistence().database(), "DROP TRIGGER fixture_logs");
            }
            assertTrue(runtime.logs().replayAll().stream().allMatch(r -> r.problem().isEmpty()));
            var recovered = repository.read(target);
            assertEquals(100, recovered.size());
            assertTrue(recovered.stream().anyMatch(r -> r.id().equals(failed.logId())));
        }
    }

    @Test
    void conflictingReplayCannotOverwriteOrCleanCategoryButNewDiagnosticsStillPersist() throws Exception {
        try (var runtime = PlatformRuntime.open(root, PlatformStartupTest.PROTECTION);
             var logger = runtime.capabilities().logs(OWNER, VERSION, Optional.empty())) {
            runtime.security().setupAndUnlock(PASSWORD.toCharArray(), PASSWORD.toCharArray());
            var handle = logger.register(CATEGORY);
            var target = target(runtime, handle);
            var repository = new LogStore(runtime.persistence().database());
            for (int i = 0; i < 20; i++)
                logger.write(handle, event("original-" + i));
            var original = repository.read(target).getFirst();
            new EmergencyLogs(root).append(target, original.withDetails("conflicting content", false));
            var report = runtime.logs().cleanHistory().stream().filter(r -> r.target().key().equals(handle.key()))
                .findFirst().orElseThrow();
            assertTrue(report.problem().isPresent());
            assertEquals(20, repository.read(target).size());
            assertEquals(1, new EmergencyLogs(root).read(target).size());
            var next = logger.write(handle, event("new valid diagnostic"));
            assertEquals(LogStorage.DATABASE, next.storage());
            assertTrue(next.problem().isPresent());
            assertEquals(21, repository.read(target).size());
            assertTrue(repository.read(target).contains(original));
        }
    }

    @Test
    void malformedAndPartialEmergencyRecordsRemainVisibleFailuresWithoutDeletingEvidence() throws Exception {
        try (var runtime = PlatformRuntime.open(root, PlatformStartupTest.PROTECTION);
             var logger = runtime.capabilities().logs(OWNER, VERSION, Optional.empty())) {
            var handle = logger.register(CATEGORY);
            var target = target(runtime, handle);
            byte[] valid = LogRecordCodec.encode(target.key(),
                LogRecordCodec.create(target, VERSION, event("complete record")));
            Files.createDirectories(file(root, handle).getParent());
            byte[] broken = (new String(valid, StandardCharsets.UTF_8) + "{\"partial\"")
                .getBytes(StandardCharsets.UTF_8);
            Files.write(file(root, handle), broken);
            var report = runtime.logs().replayAll().stream().filter(r -> r.target().key().equals(handle.key()))
                .findFirst().orElseThrow();
            assertTrue(report.problem().isPresent());
            assertTrue(report.fileBefore().isEmpty());
            assertArrayEquals(broken, Files.readAllBytes(file(root, handle)));
            assertEquals(0, count(runtime.persistence().database(), "SELECT COUNT(*) FROM runtime_log"));
            String duplicate = new String(valid, StandardCharsets.UTF_8).replace("\"format_version\":1",
                "\"format_version\":1,\"format_version\":1");
            assertThrows(java.io.IOException.class,
                () -> LogRecordCodec.decode(target, duplicate.getBytes(StandardCharsets.UTF_8)));
            assertThrows(java.io.IOException.class, () -> LogRecordCodec.decode(target,
                ("\uFEFF" + new String(valid, StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8)));
            assertThrows(java.io.IOException.class, () -> LogRecordCodec.decode(target, new byte[]{(byte) 0xc3, 10}));
            assertEquals(LogStorage.DATABASE, logger.write(handle, event("new despite bad old file")).storage());
            assertArrayEquals(broken, Files.readAllBytes(file(root, handle)));
        }
    }

    @Test
    void emergencyQuotaOfflineCleanupAndUnregisteredFilesUseExactCategoryScope() throws Exception {
        try (var runtime = PlatformRuntime.open(root, PlatformStartupTest.PROTECTION);
             var logger = runtime.capabilities().logs(OWNER, VERSION, Optional.empty())) {
            runtime.security().setupAndUnlock(PASSWORD.toCharArray(), PASSWORD.toCharArray());
            var handle = logger.register(CATEGORY);
            var target = target(runtime, handle);
            trigger(runtime, "INSERT");
            try {
                for (int i = 0; i < 103; i++)
                    assertEquals(LogStorage.EMERGENCY_FILE, logger.write(handle, event("offline-" + i)).storage());
            } finally {
                sql(runtime.persistence().database(), "DROP TRIGGER fixture_logs");
            }
            assertEquals(100, new EmergencyLogs(root).read(target).size());
            var orphan = root.resolve("log/emergency/" + UUID.randomUUID() + ".log");
            Files.writeString(orphan, "retained unknown owner");
            sql(runtime.persistence().database(), "ALTER TABLE runtime_log RENAME TO unavailable_log");
            try {
                var result = runtime.logs().inventory(true);
                assertEquals(List.of(orphan.getFileName().toString()), result.unregistered());
                var report = result.categories().stream().filter(r -> r.target().key().equals(handle.key())).findFirst()
                    .orElseThrow();
                assertTrue(report.databaseBefore().isEmpty());
                assertTrue(report.databaseAfter().isEmpty());
                assertTrue(report.databaseRemoved().isEmpty());
                assertEquals(10L, report.fileAfter().orElseThrow());
                assertEquals(90L, report.fileRemoved().orElseThrow());
                assertTrue(report.problem().isPresent());
                assertEquals("retained unknown owner", Files.readString(orphan));
            } finally {
                sql(runtime.persistence().database(), "ALTER TABLE unavailable_log RENAME TO runtime_log");
            }
            runtime.logs().replayAll();
            assertEquals(10, new LogStore(runtime.persistence().database()).read(target).size());
            assertTrue(new EmergencyLogs(root).read(target).isEmpty());
            assertEquals("retained unknown owner", Files.readString(orphan));
        }
    }

    @Test
    void lowerEmergencyRetentionStillReadsOldRecordsAndOnlyTrimsOnWrite() throws Exception {
        try (var runtime = PlatformRuntime.open(root, PlatformStartupTest.PROTECTION);
             var logger = runtime.capabilities().logs(OWNER, VERSION, Optional.empty())) {
            runtime.security().setupAndUnlock(PASSWORD.toCharArray(), PASSWORD.toCharArray());
            var handle = logger.register(CATEGORY);
            var target = target(runtime, handle);
            for (int index = 0; index < 8; index++)
                new EmergencyLogs(root).append(target, LogRecordCodec.create(target, VERSION, event("old-" + index)));
            var reduced = new EmergencyLogs(root, 3);
            assertEquals(8, reduced.read(target).size());
            reduced.replace(target, reduced.read(target));
            assertEquals(8, reduced.read(target).size());
            reduced.append(target, LogRecordCodec.create(target, VERSION, event("new")));
            assertEquals(3, reduced.read(target).size());
            assertThrows(IllegalArgumentException.class, () -> new EmergencyLogs(root, 0));
        }
    }

    @Test
    void concurrentReleaseBindingsShareQuotaAndFixedTemplateRejectsChangedPlatformNullability() throws Exception {
        try (var runtime = PlatformRuntime.open(root, PlatformStartupTest.PROTECTION);
             var first = runtime.capabilities().logs(OWNER, VERSION, Optional.empty());
             var second = runtime.capabilities().logs(OWNER, "2.0.0", Optional.empty());
             var workers = Executors.newFixedThreadPool(2)) {
            var a = first.register(CATEGORY);
            var b = second.register(CATEGORY);
            assertEquals(a.key(), b.key());
            var one = workers.submit(() -> {
                for (int i = 0; i < 75; i++)
                    assertEquals(LogStorage.DATABASE, first.write(a, event("first-" + i)).storage());
            });
            var two = workers.submit(() -> {
                for (int i = 0; i < 75; i++)
                    assertEquals(LogStorage.DATABASE, second.write(b, event("second-" + i)).storage());
            });
            one.get(10, TimeUnit.SECONDS);
            two.get(10, TimeUnit.SECONDS);
            var records = new LogStore(runtime.persistence().database()).read(target(runtime, a));
            assertEquals(100, records.size());
            assertEquals(100, records.stream().map(LogRecordCodec.Record::id).distinct().count());
            sql(runtime.persistence().database(), "ALTER TABLE runtime_log ALTER COLUMN owner_id DROP NOT NULL");
            try {
                assertThrows(ApiException.class, () -> first.register(CATEGORY));
            } finally {
                sql(runtime.persistence().database(), "ALTER TABLE runtime_log ALTER COLUMN owner_id SET NOT NULL");
            }
            first.close();
            assertEquals(LogStorage.NOT_SAVED, first.write(a, event("after close")).storage());
            assertEquals(LogStorage.DATABASE,
                second.write(b, event("other activation still owns its logger")).storage());
        }
    }

    @Test
    void businessLogRegistrationChecksRealSchemaShapeAndNeverImportsIntoARecreatedGeneration() throws Exception {
        try (var runtime = PlatformRuntime.open(root, PlatformStartupTest.PROTECTION)) {
            var identity = initialize(runtime, true);
            var schema = new SchemaCoordinates(identity).physicalName();
            try (var logger = runtime.capabilities().logs(OWNER, VERSION, Optional.of(identity));
                 var c = runtime.persistence().database().openConnection()) {
                c.setSchema(schema);
                try (var s = c.createStatement()) {
                    s.execute("CREATE TABLE wrong_log(log_id CHAR(36) PRIMARY KEY)");
                }
                assertThrows(ApiException.class,
                    () -> logger.register(new LogCategory("history", "History", Optional.of("wrong_log"))));
                String ddl;
                try (var input = getClass()
                    .getResourceAsStream("/gold/debug/windowstolinux/app/db/schema/platform-v1.sql")) {
                    ddl = new String(Objects.requireNonNull(input).readAllBytes(), StandardCharsets.UTF_8);
                }
                ddl = ddl.substring(ddl.indexOf("CREATE TABLE runtime_log")).replace("runtime_log", "history_log");
                org.h2.tools.RunScript.execute(c, new StringReader(ddl));
                var handle = logger.register(new LogCategory("history", "History", Optional.of("history_log")));
                var target = target(runtime, handle);
                assertEquals(LogStorage.DATABASE, logger.write(handle, event("owned business history")).storage());
                assertEquals(0, count(runtime.persistence().database(), "SELECT COUNT(*) FROM runtime_log"));
                var pending = LogRecordCodec.create(target, VERSION, event("old generation only"));
                new EmergencyLogs(root).append(target, pending);
                sql(runtime.persistence().database(), "UPDATE mod_schema SET generation=? WHERE mod_id=?",
                    UUID.randomUUID(), OWNER);
                assertTrue(runtime.logs().replayAll().stream().filter(r -> r.target().key().equals(handle.key()))
                    .findFirst().orElseThrow().problem().isPresent());
                assertEquals(1, new EmergencyLogs(root).read(target).size());
                try (var s = c.createStatement(); var rows = s.executeQuery("SELECT COUNT(*) FROM history_log")) {
                    assertTrue(rows.next());
                    assertEquals(1, rows.getInt(1));
                }
            }
        }
    }
}
