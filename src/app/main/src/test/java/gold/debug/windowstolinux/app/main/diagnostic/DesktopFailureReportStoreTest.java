package gold.debug.windowstolinux.app.main.diagnostic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import gold.debug.windowstolinux.appapi.task.Problem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DesktopFailureReportStoreTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void truncatesRotatesAndRedactsReportsWithoutRecordingUnknownMessages() throws Exception {
        DesktopFailureReportStore reports = new DesktopFailureReportStore(temporaryDirectory.resolve("data"));
        Problem oversized = DesktopSystemFailureType.UNKNOWN_RUNTIME_FAILURE.problem(java.util.UUID.randomUUID(),
                "password=hunter2 api-key=top-secret " + "x".repeat(400_000));
        DesktopStartupException structured = new DesktopStartupException(oversized,
                new IllegalStateException("Bearer should-never-be-recorded"));

        Path first = reports.record(structured).orElseThrow().reportPath().orElseThrow();
        assertEquals(temporaryDirectory.resolve("data/error-logs"), first.getParent());
        byte[] bytes = Files.readAllBytes(first);
        String content = new String(bytes, StandardCharsets.UTF_8);
        assertTrue(bytes.length <= DesktopFailureReportStore.MAX_REPORT_BYTES);
        assertTrue(content.contains("reportTruncated=true"));
        assertTrue(content.contains("password=[REDACTED]"));
        assertFalse(content.contains("hunter2"));
        assertFalse(content.contains("top-secret"));
        assertFalse(content.contains("should-never-be-recorded"));

        Path unknown = reports.record(new RuntimeException("secret=unknown-message")).orElseThrow().reportPath()
                .orElseThrow();
        assertFalse(Files.readString(unknown).contains("unknown-message"));

        for (int index = 0; index < DesktopFailureReportStore.MAX_REPORTS + 10; index++) {
            reports.record(DesktopStartupException.create(DesktopSystemFailureType.UNKNOWN_RUNTIME_FAILURE,
                    "bounded fixture " + index, null));
        }
        try (var files = Files.list(reports.diagnosticsDirectory().orElseThrow())) {
            assertTrue(files.filter(path -> path.getFileName().toString().endsWith(".txt"))
                    .count() <= DesktopFailureReportStore.MAX_REPORTS);
        }
    }

    @Test
    void originalSdkIdentityContextCauseAndSuppressedEvidenceAreRetainedWithoutUnknownMessages() throws Exception {
        var identity = java.util.UUID.randomUUID();
        var suppressedIdentity = java.util.UUID.randomUUID();
        var problem = new Problem(gold.debug.windowstolinux.appapi.task.ProblemCode.IO_FAILURE,
                "fixture.original-sdk", "password=do-not-record", java.util.Map.of("review", "approved",
                        "evidence", "api-key=do-not-record-context"), java.util.Optional.of(identity));
        var original = new gold.debug.windowstolinux.appapi.task.ApiException(problem);
        original.initCause(new IllegalStateException("Bearer unknown-cause-secret"));
        original.addSuppressed(new gold.debug.windowstolinux.appapi.task.ApiException(new Problem(problem.code(),
                "fixture.suppressed-sdk", "safe suppressed evidence", java.util.Map.of("phase", "cleanup"),
                java.util.Optional.of(suppressedIdentity))));
        var reports = new DesktopFailureReportStore(temporaryDirectory.resolve("sdk"));
        var record = reports.record(original).orElseThrow();
        org.junit.jupiter.api.Assertions.assertSame(problem, record.failure());
        var path = record.reportPath().orElseThrow();
        assertTrue(path.getFileName().toString().contains(identity.toString()));
        String content = Files.readString(path);
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
        Files.writeString(data.resolve("error-logs"), "not a directory");
        DesktopFailureReportStore reports = new DesktopFailureReportStore(data);

        var recorded = reports.record(new RuntimeException("raw message"));

        assertTrue(recorded.isPresent());
        assertTrue(recorded.orElseThrow().reportPath().isEmpty());
    }
}
