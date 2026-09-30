package gold.debug.windowstolinux.app.main.startup;

import static gold.debug.windowstolinux.app.main.startup.PlatformLogServiceTest.*;
import static gold.debug.windowstolinux.app.main.startup.PlatformModDeletionTest.*;
import static gold.debug.windowstolinux.app.main.startup.PlatformModInstallationTest.OWNER;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import gold.debug.windowstolinux.app.db.log.*;
import gold.debug.windowstolinux.app.db.mod.ModDeletionLedger;
import gold.debug.windowstolinux.appapi.task.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class PlatformLogDeletionTest {
    @TempDir
    Path root;

    PlatformModInstallationTest fixture;

    @BeforeEach
    void setup() throws Exception {
        fixture = new PlatformModInstallationTest();
        fixture.root = root;
        fixture.compile();
    }

    @AfterEach
    void authorWasNotRun() {
        fixture.noAuthorExecution();
    }

    public static final class PauseSelect implements org.h2.api.Trigger {
        static final AtomicBoolean first = new AtomicBoolean();

        static CountDownLatch entered, release;
        public void fire(Connection c, Object[] before, Object[] after) throws SQLException {
            if (!first.compareAndSet(true, false))
                return;
            entered.countDown();
            try {
                if (!release.await(10, TimeUnit.SECONDS))
                    throw new SQLException("fixture wait expired");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new SQLException(interrupted);
            }
        }
    }

    public static final class RejectCategoryRead implements org.h2.api.Trigger {
        public void fire(Connection c, Object[] before, Object[] after) throws SQLException {
            throw new SQLException("fixture category read unavailable");
        }
    }

    void pause(PlatformRuntime runtime) throws Exception {
        PauseSelect.first.set(true);
        PauseSelect.entered = new CountDownLatch(1);
        PauseSelect.release = new CountDownLatch(1);
        sql(runtime, "CREATE TRIGGER pause_log_read BEFORE SELECT ON runtime_log CALL '" + PauseSelect.class.getName()
                + "'");
    }

    LogRepository.Target category(PlatformRuntime runtime) throws Exception {
        try (var binding = runtime.capabilities().logs(OWNER, "1.0.0", Optional.empty())) {
            var handle = binding.register(CATEGORY);
            assertEquals(LogStorage.DATABASE, binding.write(handle, event("retained original")).storage());
            return target(runtime, handle);
        }
    }

    void removeProgram() throws Exception {
        Path scope = root.resolve("mod").resolve(OWNER);
        try (var paths = Files.walk(scope)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                assertTrue(path.startsWith(scope));
                Files.delete(path);
            }
        }
    }

    void finish(PlatformRuntime runtime, ModDeletionLedger ledger, UUID id, Path emergency) throws Exception {
        removeProgram();
        ledger.programRemoved(id);
        ledger.removeData(id);
        Files.deleteIfExists(emergency);
        ledger.removeReferences(id);
        ledger.removeRegistry(id);
        ledger.cleanup(id);
        assertTrue(ledger.finish(id));
    }

    @Test
    void actualBindingsMustCloseBeforeDeletionAndOtherOwnersRemainUsable() throws Exception {
        var original = fixture.original("one.wtlmod", "1.0.0", "one");
        try (var runtime = fixture.runtime();
                var installer = fixture.installer(runtime);
                var binding = runtime.capabilities().logs(OWNER, "1.0.0", Optional.empty());
                var other = runtime.capabilities().logs("example.other", "1.0.0", Optional.empty())) {
            assertEquals(Outcome.SUCCEEDED, installer.install(installer.review(original)).outcome());
            acknowledged(runtime);
            var target = binding.register(CATEGORY);
            var another = other.register(CATEGORY);
            assertThrows(SQLException.class, () -> installer.deletion().review(OWNER, "1.0.0"));
            assertEquals(LogStorage.DATABASE, binding.write(target, event("not deleted")).storage());
            binding.close();
            try (var sealed = runtime.logs().sealDeletion(OWNER)) {
                assertThrows(IllegalStateException.class, () -> runtime.capabilities().logs(OWNER, "1.0.0", Optional.empty()));
                assertThrows(ApiException.class, () -> runtime.logs().close());
                assertEquals(LogStorage.DATABASE, other.write(another, event("other remains available")).storage());
                assertEquals(LogStorage.NOT_SAVED, binding.write(target, event("closed binding")).storage());
            }
            var review = installer.deletion().review(OWNER, "1.0.0");
            assertEquals(Outcome.SUCCEEDED, installer.deletion().delete(review, false).outcome());
            assertFalse(Files.exists(fixture.target("1.0.0")));
        }
    }

    @Test
    void sameOwnerPhysicalInspectionDrainsBeforeDeletionCanEnter() throws Exception {
        try (var runtime = fixture.runtime(); var executor = Executors.newFixedThreadPool(2)) {
            var target = category(runtime);
            pause(runtime);
            var inspection = executor.submit(() -> runtime.logs().inspect(target.key()));
            assertTrue(PauseSelect.entered.await(5, TimeUnit.SECONDS));
            var attempted = new CountDownLatch(1);
            var deletion = executor.submit(() -> {
                attempted.countDown();
                try (var scope = runtime.logs().sealDeletion(OWNER)) {
                    return true;
                }
            });
            try {
                assertTrue(attempted.await(5, TimeUnit.SECONDS));
                assertThrows(TimeoutException.class, () -> deletion.get(100, TimeUnit.MILLISECONDS));
            } finally {
                PauseSelect.release.countDown();
            }
            assertEquals(1, inspection.get(5, TimeUnit.SECONDS).records().size());
            assertTrue(deletion.get(5, TimeUnit.SECONDS));
            sql(runtime, "DROP TRIGGER pause_log_read");
        } finally {
            if (PauseSelect.release != null)
                PauseSelect.release.countDown();
        }
    }

    @Test
    void previouslyCapturedReplaySnapshotCannotRecreateAnEmergencyFileAfterWholeOwnerRemoval() throws Exception {
        var original = fixture.original("one.wtlmod", "1.0.0", "one");
        try (var runtime = fixture.runtime();
                var installer = fixture.installer(runtime);
                var executor = Executors.newSingleThreadExecutor()) {
            assertEquals(Outcome.SUCCEEDED, installer.install(installer.review(original)).outcome());
            acknowledged(runtime);
            var target = category(runtime);
            Path emergency = file(root.resolve("data"), new LogTarget(target.key()));
            new EmergencyLogs(root.resolve("data")).append(target,
                    LogRecordCodec.create(target, "1.0.0", event("old pending")));
            var ledger = new ModDeletionLedger(runtime.persistence().database(), root);
            pause(runtime);
            var maintenance = executor.submit(() -> runtime.logs().replayAll());
            assertTrue(PauseSelect.entered.await(5, TimeUnit.SECONDS));
            try {
                try (var scope = runtime.logs().sealDeletion(OWNER)) {
                    var id = ledger.begin(ledger.review(OWNER), "{}");
                    finish(runtime, ledger, id, emergency);
                }
            } finally {
                PauseSelect.release.countDown();
            }
            var report = maintenance.get(5, TimeUnit.SECONDS);
            var removed = report.stream().filter(r -> r.target().key().equals(target.key())).findFirst().orElseThrow();
            assertTrue(removed.problem().isPresent());
            assertEquals("LOG_OWNER_DELETING", removed.problem().orElseThrow().eventCode());
            assertTrue(removed.fileBefore().isEmpty());
            assertFalse(Files.exists(emergency));
            sql(runtime, "DROP TRIGGER pause_log_read");
            try (var replacement = runtime.capabilities().logs(OWNER, "2.0.0", Optional.empty())) {
                var fresh = replacement.register(CATEGORY);
                assertNotEquals(target.key(), fresh.key());
                assertEquals(LogStorage.DATABASE, replacement.write(fresh, event("new owner activation")).storage());
                assertEquals(1,
                        new LogRepository(runtime.persistence().database()).read(target(runtime, fresh)).size());
            }
            assertFalse(Files.exists(emergency));
        } finally {
            if (PauseSelect.release != null)
                PauseSelect.release.countDown();
        }
    }

    @Test
    void pendingDeletionBlocksReplayAndNewBindingsAcrossRuntimeReopenUntilTheOriginalCleanupFinishes()
            throws Exception {
        UUID id;
        Path emergency;
        byte[] retained;
        var original = fixture.original("one.wtlmod", "1.0.0", "one");
        try (var runtime = fixture.runtime(); var installer = fixture.installer(runtime)) {
            assertEquals(Outcome.SUCCEEDED, installer.install(installer.review(original)).outcome());
            acknowledged(runtime);
            var target = category(runtime);
            emergency = file(root.resolve("data"), new LogTarget(target.key()));
            new EmergencyLogs(root.resolve("data")).append(target,
                    LogRecordCodec.create(target, "1.0.0", event("preserve while pending")));
            retained = Files.readAllBytes(emergency);
            var ledger = new ModDeletionLedger(runtime.persistence().database(), root);
            try (var scope = runtime.logs().sealDeletion(OWNER)) {
                id = ledger.begin(ledger.review(OWNER), "{}");
                ledger.unconfirmed(id);
            }
            assertThrows(IllegalStateException.class, () -> runtime.capabilities().logs(OWNER, "1.0.0", Optional.empty()));
            var pending = runtime.logs().cleanHistory().stream().filter(r -> r.target().owner().equals(OWNER)).toList();
            assertEquals(1, pending.size());
            assertEquals("LOG_OWNER_DELETING", pending.getFirst().problem().orElseThrow().eventCode());
            assertArrayEquals(retained, Files.readAllBytes(emergency));
        }
        try (var runtime = fixture.runtime()) {
            assertThrows(IllegalStateException.class, () -> runtime.capabilities().logs(OWNER, "1.0.0", Optional.empty()));
            assertArrayEquals(retained, Files.readAllBytes(emergency));
            var ledger = new ModDeletionLedger(runtime.persistence().database(), root);
            try (var scope = runtime.logs().sealDeletion(OWNER)) {
                ledger.resume(id);
                finish(runtime, ledger, id, emergency);
            }
            assertFalse(Files.exists(emergency));
            try (var allowed = runtime.capabilities().logs(OWNER, "2.0.0", Optional.empty())) {
                assertNotNull(allowed.register(CATEGORY));
            }
        }
    }

    @Test
    void failedFinalAvailabilityCheckKeepsAdmissionClosedButReleasesItsActualWorkerLock() throws Exception {
        try (var runtime = fixture.runtime()) {
            category(runtime);
            var scope = runtime.logs().sealDeletion(OWNER);
            sql(runtime, "CREATE TRIGGER reject_category_read BEFORE SELECT ON log_category CALL '"
                    + RejectCategoryRead.class.getName() + "'");
            try {
                assertThrows(SQLException.class, scope::close);
            } finally {
                sql(runtime, "DROP TRIGGER reject_category_read");
            }
            assertThrows(IllegalStateException.class, () -> runtime.capabilities().logs(OWNER, "1.0.0", Optional.empty()));
            try (var recovered = runtime.logs().sealDeletion(OWNER)) {
            }
            try (var binding = runtime.capabilities().logs(OWNER, "1.0.0", Optional.empty())) {
                assertNotNull(binding.register(CATEGORY));
            }
        }
    }
}
