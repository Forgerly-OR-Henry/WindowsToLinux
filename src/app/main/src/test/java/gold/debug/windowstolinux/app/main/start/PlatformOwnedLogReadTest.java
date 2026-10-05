package gold.debug.windowstolinux.app.main.start;

import static gold.debug.windowstolinux.app.main.start.PlatformLogServiceTest.*;
import static gold.debug.windowstolinux.app.main.start.PlatformTaskAdmissionIntegrationTest.*;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import javax.swing.SwingUtilities;

import gold.debug.windowstolinux.appapi.task.log.*;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlatformOwnedLogReadTest {
    @TempDir
    Path root;

    @Test
    void originalTargetVersionTaskAndLockBoundariesRetainOnlyRedactedOwnRecords() throws Exception {
        try (var runtime = PlatformRuntime.open(root, PlatformStartupTest.PROTECTION);
             var logger = runtime.capabilities().logs(OWNER, VERSION, Optional.empty());
             var other = runtime.capabilities().logs("example.other", VERSION, Optional.empty());
             var old = runtime.capabilities().logs(OWNER, "0.9.0", Optional.empty())) {
            initialize(runtime, false);
            var target = logger.register(CATEGORY);
            var foreign = other.register(CATEGORY);
            var oldTarget = old.register(CATEGORY);
            assertEquals(LogStorage.DATABASE, old.write(oldTarget, event("old-release-only")).storage());
            assertEquals(LogStorage.DATABASE, other.write(foreign, event("other-owner-only")).storage());
            var tasks = runtime.tasks().bind(OWNER, VERSION, false);
            var first = tasks.submit(request(), task -> {
                assertEquals(LogStorage.DATABASE, logger.write(target, new LogEvent(LogLevel.INFO,
                    "OWNED_STAGE", "password=do-not-display", Optional.empty(), Optional.of(task.id()),
                    Optional.of(task.id()), Optional.empty())).storage());
                return success(null);
            });
            first.completion().toCompletableFuture().get(10, TimeUnit.SECONDS);
            var second = tasks.submit(request(), task -> {
                logger.write(target, new LogEvent(LogLevel.INFO, "OTHER_STAGE", "second task",
                    Optional.empty(), Optional.of(task.id()), Optional.empty(), Optional.empty()));
                return success(null);
            });
            second.completion().toCompletableFuture().get(10, TimeUnit.SECONDS);
            var selected = logger.read(target, Optional.of(first.id()), 10);
            assertTrue(selected.problem().isEmpty());
            assertEquals(1, selected.records().size());
            assertEquals(first.id(), selected.records().getFirst().event().taskId().orElseThrow());
            assertFalse(selected.toString().contains("do-not-display"));
            assertTrue(selected.records().getFirst().event().message().contains("[redacted]"));
            assertEquals(2, logger.read(target, Optional.empty(), 100).records().size());
            assertEquals(1, logger.read(target, Optional.empty(), 1).records().size());
            assertThrows(ApiException.class, () -> logger.read(new LogTarget(target.key()), Optional.empty(), 10));
            assertThrows(ApiException.class, () -> logger.read(foreign, Optional.empty(), 10));
            assertThrows(ApiException.class, () -> logger.read(target, Optional.empty(), 101));
            SwingUtilities.invokeAndWait(() -> assertThrows(ApiException.class,
                () -> logger.read(target, Optional.empty(), 10)));
            runtime.security().lock();
            assertThrows(ApiException.class, () -> logger.read(target, Optional.empty(), 10));
            runtime.security().unlock(PASSWORD.toCharArray());
            assertEquals(2, logger.read(target, Optional.empty(), 100).records().size());
            logger.close();
            assertThrows(ApiException.class, () -> logger.read(target, Optional.empty(), 10));
        }
    }

    @Test
    void mergedEmergencyRecordsRetainIdentityAndCorruptLocationIsExplicitlyPartial() throws Exception {
        try (var runtime = PlatformRuntime.open(root, PlatformStartupTest.PROTECTION);
             var logger = runtime.capabilities().logs(OWNER, VERSION, Optional.empty())) {
            runtime.security().setupAndUnlock(PASSWORD.toCharArray(), PASSWORD.toCharArray());
            var target = logger.register(CATEGORY);
            var database = logger.write(target, event("database evidence"));
            trigger(runtime, "INSERT");
            try {
                var fallback = logger.write(target, event("token=hidden-emergency"));
                assertEquals(LogStorage.EMERGENCY_FILE, fallback.storage());
                var read = logger.read(target, Optional.empty(), 100);
                assertTrue(read.problem().isEmpty());
                assertEquals(2, read.records().size());
                assertTrue(read.records().stream().anyMatch(record -> record.id().equals(fallback.logId())));
                assertFalse(read.toString().contains("hidden-emergency"));
                var path = file(root, target);
                var original = Files.readAllBytes(path);
                try {
                    Files.writeString(path, "invalid diagnostic receipt\n");
                    var partial = logger.read(target, Optional.empty(), 100);
                    assertEquals("LOG_READ_EMERGENCY", partial.problem().orElseThrow().eventCode());
                    assertEquals(database.logId(), partial.records().getFirst().id());
                } finally {
                    Files.write(path, original);
                }
            } finally {
                sql(runtime.persistence().database(), "DROP TRIGGER fixture_logs");
            }
        }
    }
}
