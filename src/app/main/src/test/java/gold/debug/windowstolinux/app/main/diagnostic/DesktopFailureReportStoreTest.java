package gold.debug.windowstolinux.app.main.diagnostic;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import gold.debug.windowstolinux.app.db.bootstrap.DatabaseProtection;
import gold.debug.windowstolinux.app.db.log.*;
import gold.debug.windowstolinux.app.main.startup.PlatformRuntime;
import gold.debug.windowstolinux.appapi.task.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DesktopFailureReportStoreTest {
    @TempDir
    Path temporaryDirectory;

    private static final DatabaseProtection PROTECTION = new DatabaseProtection() {
        public byte[] protect(byte[] input) {
            byte[] result = input.clone();
            for (int index = 0; index < result.length; index++)
                result[index] ^= 0x67;
            return result;
        }

        public byte[] unprotect(byte[] input) {
            return protect(input);
        }
    };

    @Test
    void truncatesRotatesAndRedactsReportsWithoutRecordingUnknownMessages() throws Exception {
        Path data = Files.createDirectories(temporaryDirectory.resolve("data"));
        DesktopFailureReportStore reports = new DesktopFailureReportStore(data, "fixture-release");
        Problem oversized = DesktopSystemFailureType.UNKNOWN_RUNTIME_FAILURE.problem(java.util.UUID.randomUUID(),
                "password=hunter2 api-key=top-secret " + "x".repeat(400_000));
        DesktopStartupException structured = new DesktopStartupException(oversized,
                new IllegalStateException("Bearer should-never-be-recorded"));

        Path first = reports.record(structured).orElseThrow().reportPath().orElseThrow();
        assertEquals(data.resolve("log/emergency"), first.getParent());
        assertEquals(LogRepository.startupTarget().key() + ".log", first.getFileName().toString());
        byte[] bytes = Files.readAllBytes(first);
        String content = new String(bytes, StandardCharsets.UTF_8);
        assertTrue(bytes.length <= LogRecordCodec.MAX_BYTES);
        var bounded = new EmergencyLogs(data).read(LogRepository.startupTarget()).getFirst();
        assertTrue(bounded.truncated());
        assertEquals(oversized.diagnosticId().orElseThrow(), bounded.id());
        assertTrue(content.contains("password=[redacted]"));
        assertFalse(content.contains("hunter2"));
        assertFalse(content.contains("top-secret"));
        assertFalse(content.contains("should-never-be-recorded"));

        Path unknown = reports.record(new RuntimeException("secret=unknown-message")).orElseThrow().reportPath()
                .orElseThrow();
        assertFalse(Files.readString(unknown).contains("unknown-message"));

        for (int index = 0; index < 110; index++) {
            reports.record(DesktopStartupException.create(DesktopSystemFailureType.UNKNOWN_RUNTIME_FAILURE,
                    "bounded fixture " + index, null));
        }
        var retained = new EmergencyLogs(data).read(LogRepository.startupTarget());
        assertEquals(100, retained.size());
        assertEquals(100, retained.stream().map(LogRecordCodec.Record::id).distinct().count());
        for (var record : retained)
            assertTrue(LogRecordCodec.encode(LogRepository.startupTarget().key(),
                    record).length <= LogRecordCodec.MAX_BYTES);
        try (var files = Files.list(reports.diagnosticsDirectory().orElseThrow())) {
            assertEquals(List.of(LogRepository.startupTarget().key() + ".log"),
                    files.map(path -> path.getFileName().toString()).toList());
        }
    }

    @Test
    void originalSdkIdentityContextCauseAndSuppressedEvidenceAreRetainedWithoutUnknownMessages() throws Exception {
        var identity = java.util.UUID.randomUUID();
        var suppressedIdentity = java.util.UUID.randomUUID();
        var problem = new Problem(gold.debug.windowstolinux.appapi.task.ProblemCode.IO_FAILURE, "fixture.original-sdk",
                "password=do-not-record",
                java.util.Map.of("review", "approved", "evidence", "api-key=do-not-record-context"),
                java.util.Optional.of(identity));
        var original = new gold.debug.windowstolinux.appapi.task.ApiException(problem);
        original.initCause(new IllegalStateException("Bearer unknown-cause-secret"));
        original.addSuppressed(new gold.debug.windowstolinux.appapi.task.ApiException(
                new Problem(problem.code(), "fixture.suppressed-sdk", "safe suppressed evidence",
                        java.util.Map.of("phase", "cleanup"), java.util.Optional.of(suppressedIdentity))));
        Path data = Files.createDirectories(temporaryDirectory.resolve("sdk"));
        var reports = new DesktopFailureReportStore(data, "fixture-release");
        var record = reports.record(original).orElseThrow();
        org.junit.jupiter.api.Assertions.assertSame(problem, record.failure());
        var path = record.reportPath().orElseThrow();
        assertEquals(LogRepository.startupTarget().key() + ".log", path.getFileName().toString());
        var originalRecord = new EmergencyLogs(data).read(LogRepository.startupTarget()).getFirst();
        assertEquals(identity, originalRecord.id());
        assertEquals("app", originalRecord.owner());
        assertEquals("runtime", originalRecord.category());
        assertEquals("fixture-release", originalRecord.version());
        assertNull(originalRecord.subjectMod());
        String content = originalRecord.details();
        assertTrue(content.contains("code=fixture.original-sdk"));
        assertTrue(content.contains("context.review=approved"));
        assertTrue(content.contains("java.lang.IllegalStateException"));
        assertTrue(content.contains("suppressedBy[0]="));
        assertTrue(content.contains("fixture.suppressed-sdk"));
        assertTrue(content.contains(suppressedIdentity.toString()));
        assertFalse(content.contains("do-not-record"));
        assertFalse(content.contains("unknown-cause-secret"));
    }

    @Test
    void reportWriteFailureDoesNotRecurseOrEscape() throws Exception {
        Path data = Files.createDirectories(temporaryDirectory.resolve("blocked"));
        Files.writeString(data.resolve("log"), "not a directory");
        DesktopFailureReportStore reports = new DesktopFailureReportStore(data);

        var recorded = reports.record(new RuntimeException("raw message"));

        assertTrue(recorded.isPresent());
        assertTrue(recorded.orElseThrow().reportPath().isEmpty());
        assertEquals("not a directory", Files.readString(data.resolve("log")),
                "Failed reporting must preserve the original blocking file without fallback or recursive writes");
    }

    @Test
    void earlyStartupQueueReopensIntoOriginalHostRecordsWithoutChangingIdentityOrTime() throws Exception {
        Path data = Files.createDirectories(temporaryDirectory.resolve("early"));
        var reports = new DesktopFailureReportStore(data, "fixture-release");
        var identity = UUID.randomUUID();
        var problem = new Problem(ProblemCode.IO_FAILURE, "fixture.early-startup", "Original startup failure",
                Map.of("phase", "before-database"), Optional.of(identity));
        var recorded = reports.record(new ApiException(problem)).orElseThrow();
        assertSame(problem, recorded.failure());
        var queued = new EmergencyLogs(data).read(LogRepository.startupTarget());
        assertEquals(1, queued.size());
        var original = queued.getFirst();
        assertEquals(identity, original.id());
        assertTrue(original.recordedAt() > 0);
        assertTrue(Files.isRegularFile(recorded.reportPath().orElseThrow()));
        try (var runtime = PlatformRuntime.open(data, PROTECTION)) {
            var repository = new LogRepository(runtime.persistence().database());
            var host = hostTarget(repository);
            assertNotEquals(LogRepository.startupTarget().key(), host.key());
            assertTrue(
                    repository.targets().stream()
                            .noneMatch(target -> target.key().equals(LogRepository.startupTarget().key())),
                    "The fixed pre-database queue must never be registered as a Mod or business category");
            assertEquals(List.of(original), repository.read(host));
            assertTrue(new EmergencyLogs(data).read(LogRepository.startupTarget()).isEmpty());
            runtime.logs().replayAll();
            assertEquals(List.of(original), repository.read(host));
        }
        try (var runtime = PlatformRuntime.open(data, PROTECTION)) {
            var repository = new LogRepository(runtime.persistence().database());
            assertEquals(List.of(original), repository.read(hostTarget(repository)));
            assertTrue(new EmergencyLogs(data).read(LogRepository.startupTarget()).isEmpty());
        }
    }

    @Test
    void failedStartupCommitRetainsExactQueueUntilRetryAndAttachedFailuresUseOnlyHostStorage() throws Exception {
        Path data = Files.createDirectories(temporaryDirectory.resolve("commit"));
        var reports = new DesktopFailureReportStore(data, "fixture-release");
        var identity = UUID.randomUUID();
        var problem = new Problem(ProblemCode.IO_FAILURE, "fixture.pending-startup", "Retain original before commit",
                Map.of("phase", "before-commit"), Optional.of(identity));
        LogRecordCodec.Record original;
        try (var runtime = PlatformRuntime.open(data, PROTECTION)) {
            var repository = new LogRepository(runtime.persistence().database());
            var host = hostTarget(repository);
            try (var connection = runtime.persistence().database().openConnection();
                    var statement = connection.createStatement()) {
                statement.execute(
                        "ALTER TABLE app.runtime_log ADD CONSTRAINT fixture_startup_commit CHECK(event_code <> 'fixture.pending-startup')");
            }
            Path queue = reports.record(new ApiException(problem)).orElseThrow().reportPath().orElseThrow();
            byte[] before = Files.readAllBytes(queue);
            original = new EmergencyLogs(data).read(LogRepository.startupTarget()).getFirst();
            assertEquals(identity, original.id());
            try {
                runtime.logs().replayAll();
                assertArrayEquals(before, Files.readAllBytes(queue),
                        "Unconfirmed SQL commit cannot clear or rewrite original evidence");
                assertEquals(List.of(original), new EmergencyLogs(data).read(LogRepository.startupTarget()));
                assertTrue(repository.read(host).isEmpty());
                assertEquals("LOG_STARTUP_REPLAY_PENDING", runtime.logs().issue().orElseThrow().eventCode());
            } finally {
                try (var connection = runtime.persistence().database().openConnection();
                        var statement = connection.createStatement()) {
                    statement.execute("ALTER TABLE app.runtime_log DROP CONSTRAINT fixture_startup_commit");
                }
            }
            runtime.logs().replayAll();
            assertEquals(List.of(original), repository.read(host));
            assertTrue(new EmergencyLogs(data).read(LogRepository.startupTarget()).isEmpty());
            reports.attach(runtime.logs(), runtime.version());
            var liveIdentity = UUID.randomUUID();
            var liveProblem = new Problem(ProblemCode.IO_FAILURE, "fixture.live-startup", "password=live-secret",
                    Map.of("phase", "live"), Optional.of(liveIdentity));
            var live = reports.record(new ApiException(liveProblem)).orElseThrow();
            assertSame(liveProblem, live.failure());
            assertTrue(live.reportPath().isEmpty(), "Confirmed H2 storage cannot advertise a nonexistent report file");
            var actual = repository.read(host);
            assertEquals(2, actual.size());
            assertEquals(1, actual.stream().filter(record -> record.id().equals(identity)).count());
            assertEquals(1, actual.stream().filter(record -> record.id().equals(liveIdentity)).count());
            var liveRecord = actual.stream().filter(record -> record.id().equals(liveIdentity)).findFirst()
                    .orElseThrow();
            assertEquals("app", liveRecord.owner());
            assertEquals("runtime", liveRecord.category());
            assertEquals(runtime.version(), liveRecord.version());
            assertFalse(liveRecord.message().contains("live-secret"));
            assertFalse(liveRecord.details().contains("live-secret"));
            assertTrue(new EmergencyLogs(data).read(LogRepository.startupTarget()).isEmpty());
            assertTrue(new EmergencyLogs(data).read(host).isEmpty());
            runtime.logs().replayAll();
            assertEquals(actual, repository.read(host));
        }
        try (var runtime = PlatformRuntime.open(data, PROTECTION)) {
            var repository = new LogRepository(runtime.persistence().database());
            var records = repository.read(hostTarget(repository));
            assertEquals(2, records.size());
            assertEquals(original,
                    records.stream().filter(record -> record.id().equals(identity)).findFirst().orElseThrow());
            assertTrue(new EmergencyLogs(data).read(LogRepository.startupTarget()).isEmpty());
        }
    }

    private static LogRepository.Target hostTarget(LogRepository repository) throws Exception {
        return repository.targets().stream()
                .filter(target -> target.owner().equals("app") && target.category().equals("runtime")).findFirst()
                .orElseThrow();
    }

}
