package gold.debug.windowstolinux.app.db;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import gold.debug.windowstolinux.app.db.server.StoredServerIdentity;
import gold.debug.windowstolinux.app.db.server.StoredServerProfile;
import gold.debug.windowstolinux.app.db.error.DesktopStorageException;
import gold.debug.windowstolinux.app.db.error.DesktopStorageFailureType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DesktopStorageIntegrationTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void storesOnlyTheLastRemoteObservationAsHistory() throws Exception {
        var server = new StoredServerIdentity("server-one", "example.test", 22, "SHA256:exampleFingerprint");
        var profile = new StoredServerProfile("server-one", "example.test", 22, "tester", "ssh/server-one/password");
        try (var database = DatabaseTestFixture.open(temporaryDirectory)) {
            DatabaseTestFixture.server(database, server);
            database.servers().recordObservation(profile, true, "first-system");
            database.servers().recordObservation(profile, false, "latest-system");
            database.servers().saveServerProfile(profile);
            assertEquals(server, database.servers().findServer("server-one").orElseThrow());
            assertEquals(profile, database.servers().findServerProfile("server-one").orElseThrow());
            var latest = database.servers().observation("server-one");
            assertFalse(latest.connected());
            assertEquals("latest-system", latest.operatingSystem());
            assertTrue(latest.checkedAt().isPresent());
        }
        try (var database = DatabaseTestFixture.open(temporaryDirectory)) {
            assertEquals(server, database.servers().findServer("server-one").orElseThrow());
            assertEquals("latest-system", database.servers().observation("server-one").operatingSystem());
            assertFalse(database.servers().observation("server-one").connected());
        }
    }

    @Test
    void storesNonSecretDesktopPreferences() throws Exception {
        try (var database = DatabaseTestFixture.open(temporaryDirectory)) {
            assertTrue(database.preferences().find("ui_locale").isEmpty());
            assertTrue(database.preferences().find("ui_theme").isEmpty());
            database.preferences().save("ui_locale", "zh-CN");
            database.preferences().save("ui_theme", "SYSTEM");
            assertEquals("zh-CN", database.preferences().find("ui_locale").orElseThrow());
            assertEquals("SYSTEM", database.preferences().find("ui_theme").orElseThrow());
        }
    }

    @Test
    void classifiesUnavailableDirectoryCorruptionLockAndRollbackFailure() throws Exception {
        var unavailable = Files.writeString(temporaryDirectory.resolve("not-a-directory"), "fixture");
        assertEvent(DesktopStorageFailureType.DATA_DIRECTORY_UNAVAILABLE,
            assertThrows(DesktopStorageException.class, () -> DatabaseTestFixture.open(unavailable)));
        var corrupt = temporaryDirectory.resolve("corrupt");
        try (var ignored = DatabaseTestFixture.open(corrupt)) {
        }
        var bytes = Files.readAllBytes(corrupt.resolve("windowstolinux.mv.db"));
        Files.write(corrupt.resolve("windowstolinux.mv.db"), new byte[4096]);
        assertEvent(DesktopStorageFailureType.DATABASE_CORRUPTED,
            assertThrows(DesktopStorageException.class, () -> DatabaseTestFixture.open(corrupt)));
        assertArrayEquals(new byte[4096], Files.readAllBytes(corrupt.resolve("windowstolinux.mv.db")));
        assertFalse(Arrays.equals(bytes, Files.readAllBytes(corrupt.resolve("windowstolinux.mv.db"))));
        var locked = temporaryDirectory.resolve("locked");
        try (var original = DatabaseTestFixture.open(locked)) {
            assertEvent(DesktopStorageFailureType.DATABASE_LOCKED,
                assertThrows(DesktopStorageException.class, () -> DatabaseTestFixture.open(locked)));
            assertEquals(0, original.database().openConnections());
        }
        var primary = new java.sql.SQLException("fixture transaction failure");
        var rollback = new java.sql.SQLException("fixture rollback failure");
        primary.addSuppressed(rollback);
        var failure = DesktopStorageException.create(DesktopStorageFailureType.ROLLBACK_FAILED,
            "The actual rollback cannot be confirmed", primary);
        assertEvent(DesktopStorageFailureType.ROLLBACK_FAILED, failure);
        assertSame(primary, failure.getCause());
        assertSame(rollback, failure.getCause().getSuppressed()[0]);
    }

    private static void assertEvent(DesktopStorageFailureType expected, DesktopStorageException failure) {
        assertEquals(expected.code(), failure.problem().eventCode(), () -> {
            var diagnostic = new StringBuilder();
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                diagnostic.append(cause).append('\n');
                if (cause instanceof org.h2.mvstore.MVStoreException store)
                    diagnostic.append("MVSTORE_CODE=").append(store.getErrorCode()).append('\n');
            }
            return diagnostic.toString();
        });
        assertTrue(failure.problem().diagnosticId().isPresent());
    }
}
