package gold.debug.windowstolinux.app.main.startup;

import static gold.debug.windowstolinux.app.main.startup.PlatformRemoteCapabilitiesTest.*;
import static gold.debug.windowstolinux.app.main.startup.PlatformTaskAdmissionIntegrationTest.*;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.sql.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import javax.swing.SwingUtilities;

import gold.debug.windowstolinux.app.service.linux.*;
import gold.debug.windowstolinux.appapi.task.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlatformTransferSettingsTest {
    @TempDir
    Path root;

    @Test
    void defaultsAndPartialPreferencesDoNotWriteWhileSavedPairSurvivesActualReopen() throws Exception {
        try (var runtime = PlatformRuntime.open(root, PlatformStartupTest.PROTECTION)) {
            runtime.security().setupAndUnlock(PASSWORD.toCharArray(), PASSWORD.toCharArray());
            assertEquals(TransferSettings.DEFAULTS, runtime.transfers().read());
            assertEquals(0, count(runtime.persistence().database(),
                    "SELECT COUNT(*) FROM desktop_preference WHERE preference_key LIKE 'transfer_%'"));
            runtime.persistence().preferences().save("transfer_connect_seconds", "45");
            assertEquals(new TransferSettings(45, 300), runtime.transfers().read());
            assertEquals(new TransferSettings(60, 1200), runtime.transfers().save(new TransferSettings(60, 1200)));
            runtime.persistence().preferences().save("deploy_approval", "MANUAL");
        }
        try (var runtime = PlatformRuntime.open(root, PlatformStartupTest.PROTECTION)) {
            assertThrows(gold.debug.windowstolinux.app.service.failure.ApplicationServiceException.class,
                    runtime.transfers()::read);
            runtime.security().unlock(PASSWORD.toCharArray());
            assertEquals(new TransferSettings(60, 1200), runtime.transfers().read());
            assertEquals("MANUAL", runtime.persistence().preferences().find("deploy_approval").orElseThrow());
        }
    }

    public static final class FailSecondWrite implements org.h2.api.Trigger {
        static final AtomicInteger writes = new AtomicInteger();
        public void fire(Connection connection, Object[] oldRow, Object[] newRow) throws SQLException {
            if (writes.incrementAndGet() == 2)
                throw new SQLException("fixture second write rejected");
        }
    }

    @Test
    void bothPreferencesRollBackIfSecondWriteFailsAndCorruptStoredValuesAreNotReplaced() throws Exception {
        try (var runtime = PlatformRuntime.open(root, PlatformStartupTest.PROTECTION)) {
            runtime.security().setupAndUnlock(PASSWORD.toCharArray(), PASSWORD.toCharArray());
            var original = runtime.transfers().save(new TransferSettings(10, 20));
            var db = runtime.persistence().database();
            FailSecondWrite.writes.set(0);
            sql(db, "CREATE TRIGGER fixture_preferences BEFORE UPDATE ON desktop_preference FOR EACH ROW CALL '"
                    + FailSecondWrite.class.getName() + "'");
            try {
                assertThrows(SQLException.class, () -> runtime.transfers().save(new TransferSettings(15, 25)));
                assertEquals(2, FailSecondWrite.writes.get());
                assertEquals(original, runtime.transfers().read());
            } finally {
                sql(db, "DROP TRIGGER fixture_preferences");
            }
            runtime.persistence().preferences().save("transfer_idle_seconds", "invalid");
            assertThrows(SQLException.class, runtime.transfers()::read);
            assertEquals("invalid", runtime.persistence().preferences().find("transfer_idle_seconds").orElseThrow());
            runtime.transfers().save(original);
            assertThrows(IllegalArgumentException.class, () -> new TransferSettings(0, 10));
            assertThrows(IllegalArgumentException.class, () -> new TransferSettings(10, 86401));
            SwingUtilities.invokeAndWait(() -> assertThrows(IllegalStateException.class, runtime.transfers()::read));
            runtime.security().lock();
            assertThrows(gold.debug.windowstolinux.app.service.failure.ApplicationServiceException.class,
                    () -> runtime.transfers().save(new TransferSettings(1, 1)));
            runtime.security().unlock(PASSWORD.toCharArray());
            assertEquals(original, runtime.transfers().read());
        }
    }

    @Test
    void existingDefaultCapabilityReadsNewConnectionThresholdBeforeAttemptingAuthentication() throws Exception {
        try (var remote = new Remote(root.resolve("ssh"));
                var runtime = PlatformRuntime.open(root.resolve("db"), PlatformStartupTest.PROTECTION)) {
            remote.configure(runtime);
            runtime.setInteractionPresenter(new Reviews());
            var entered = new CountDownLatch(1);
            var exited = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            remote.listener.server().setPasswordAuthenticator((user, password, session) -> {
                entered.countDown();
                try {
                    assertTrue(release.await(8, TimeUnit.SECONDS));
                    return false;
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return false;
                } finally {
                    exited.countDown();
                }
            });
            try (var capabilities = runtime.remoteCapabilities(OWNER, VERSION)) {
                runtime.transfers().save(new TransferSettings(1, 300));
                var task = runtime.tasks().bind(OWNER, VERSION, false).submit(request("server"), context -> {
                    long start = System.nanoTime();
                    assertThrows(ApiException.class, () -> capabilities.linux().connect(SERVER, context));
                    assertEquals(0, entered.getCount());
                    long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
                    assertTrue(elapsed >= 700 && elapsed < 6000, "Actual connection wait: " + elapsed);
                    return success(null);
                });
                try {
                    assertEquals(Outcome.SUCCEEDED,
                            task.completion().toCompletableFuture().get(8, TimeUnit.SECONDS).outcome());
                } finally {
                    release.countDown();
                }
                assertTrue(exited.await(5, TimeUnit.SECONDS));
                assertEquals(0, count(runtime.persistence().database(),
                        "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL"));
            }
        }
    }
}
