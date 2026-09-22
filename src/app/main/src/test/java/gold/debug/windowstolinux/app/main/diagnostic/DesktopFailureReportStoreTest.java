package gold.debug.windowstolinux.app.main.diagnostic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import gold.debug.windowstolinux.shared.model.failure.FailureDescriptor;
import gold.debug.windowstolinux.shared.model.failure.OperationIdentity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DesktopFailureReportStoreTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void truncatesRotatesAndRedactsReportsWithoutRecordingUnknownMessages() throws Exception {
        DesktopFailureReportStore reports = new DesktopFailureReportStore(temporaryDirectory.resolve("data"));
        FailureDescriptor oversized = FailureDescriptor.create(DesktopSystemFailureType.UNKNOWN_RUNTIME_FAILURE,
                OperationIdentity.create(), "password=hunter2 api-key=top-secret " + "x".repeat(400_000));
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
    void reportWriteFailureDoesNotRecurseOrEscape() throws Exception {
        Path data = Files.createDirectories(temporaryDirectory.resolve("blocked"));
        Files.writeString(data.resolve("error-logs"), "not a directory");
        DesktopFailureReportStore reports = new DesktopFailureReportStore(data);

        var recorded = reports.record(new RuntimeException("raw message"));

        assertTrue(recorded.isPresent());
        assertTrue(recorded.orElseThrow().reportPath().isEmpty());
    }
}
