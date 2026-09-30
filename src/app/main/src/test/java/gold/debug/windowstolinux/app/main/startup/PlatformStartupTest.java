package gold.debug.windowstolinux.app.main.startup;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

import javax.swing.SwingUtilities;

import gold.debug.windowstolinux.app.db.bootstrap.DatabaseProtection;
import gold.debug.windowstolinux.app.db.task.*;
import gold.debug.windowstolinux.app.windows.security.CurrentUserProtection;
import gold.debug.windowstolinux.appapi.task.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlatformStartupTest {
    @TempDir
    Path root;

    static final DatabaseProtection PROTECTION = new DatabaseProtection() {
        public byte[] protect(byte[] input) {
            byte[] result = input.clone();
            for (int i = 0; i < result.length; i++)
                result[i] ^= 0x67;
            return result;
        }

        public byte[] unprotect(byte[] input) {
            return protect(input);
        }
    };

    @Test
    void firstAndLaterBootUseH2AndKeepOldSqliteUntouched() throws Exception {
        byte[] old = "SQLite fixture preserved byte for byte".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(root.resolve("windowstolinux.db"), old);
        UUID generation;
        UUID firstBoot;
        byte[] bootstrap;
        try (var runtime = PlatformRuntime.open(root, PROTECTION)) {
            assertFalse(runtime.security().securityState().configured());
            assertThrows(RuntimeException.class, runtime.security()::beginTask);
            runtime.security().setupAndUnlock("isolated-platform-password".toCharArray(),
                    "isolated-platform-password".toCharArray());
            assertTrue(runtime.security().securityState().unlocked());
            runtime.persistence().preferences().save("ui_locale", "zh-CN");
            runtime.started();
            assertEquals("RUNNING", marker(runtime));
            generation = runtime.persistence().database().generation();
            firstBoot = runtime.persistence().database().bootId();
            bootstrap = Files.readAllBytes(root.resolve("db-access.bin"));
            assertTrue(Files.isRegularFile(root.resolve("platform.mv.db")));
            assertArrayEquals(old, Files.readAllBytes(root.resolve("windowstolinux.db")));
            assertFalse(Files.exists(root.resolve("backups")));
        }
        try (var runtime = PlatformRuntime.open(root, PROTECTION)) {
            assertEquals(generation, runtime.persistence().database().generation());
            assertNotEquals(firstBoot, runtime.persistence().database().bootId());
            assertEquals("STARTING", marker(runtime));
            assertTrue(runtime.security().securityState().configured());
            assertFalse(runtime.security().securityState().unlocked());
            assertEquals("zh-CN", runtime.persistence().preferences().find("ui_locale").orElseThrow());
            assertThrows(Exception.class, () -> runtime.security().unlock("wrong-password".toCharArray()));
            runtime.security().unlock("isolated-platform-password".toCharArray());
            assertArrayEquals(bootstrap, Files.readAllBytes(root.resolve("db-access.bin")));
        }
        assertArrayEquals(old, Files.readAllBytes(root.resolve("windowstolinux.db")));
    }

    @Test
    void shutdownWaitsForCredentialsConnectionsAndUnknownTasks() throws Exception {
        var runtime = PlatformRuntime.open(root, PROTECTION);
        try {
            runtime.security().setupAndUnlock("isolated-platform-password".toCharArray(),
                    "isolated-platform-password".toCharArray());
            try (var authorization = runtime.security().beginTask()) {
                assertThrows(RuntimeException.class, runtime::close);
                assertTrue(runtime.security().securityState().unlocked());
            }
            try (var connection = runtime.persistence().database().openConnection()) {
                assertThrows(SQLException.class, runtime::close);
                assertTrue(runtime.security().securityState().unlocked());
                assertTrue(connection.isValid(1));
            }
            var ledger = new TaskLedger(runtime.persistence().database());
            UUID id = ledger.enqueue(UUID.randomUUID(), "mod.example", "1.0.0",
                    new TaskRequest("write", "Example", List.of(), true),
                    List.of(new TaskResource("MOD", "mod.example", "mod.example", false)));
            assertTrue(ledger.tryStart(id));
            assertFalse(ledger.complete(id, Outcome.UNKNOWN, "{}", false));
            assertThrows(SQLException.class, runtime::close);
            assertEquals(TaskState.UNKNOWN, ledger.queries().snapshot(id).state());
            assertTrue(ledger.complete(id, Outcome.SUCCEEDED, "{}", true));
        } finally {
            runtime.close();
        }
        runtime.close();
        try (var reopened = PlatformRuntime.open(root, PROTECTION)) {
            assertTrue(new TaskLedger(reopened.persistence().database()).queries().unfinished().isEmpty());
        }
    }

    @Test
    void rejectsLifecycleOnEdtBeforeCreatingDatabaseFiles() throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                PlatformRuntime.open(root, PROTECTION);
            } catch (Throwable problem) {
                failure.set(problem);
            }
        });
        assertInstanceOf(IllegalStateException.class, failure.get());
        assertFalse(Files.exists(root.resolve("platform.mv.db")));
    }

    @Test
    void actualCurrentUserDpapiBootstrapReopensAndRejectsTampering() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getProperty("os.name").startsWith("Windows"));
        var nativeProtection = new CurrentUserProtection();
        var protection = new DatabaseProtection() {
            public byte[] protect(byte[] bytes) {
                return nativeProtection.protect(bytes);
            }

            public byte[] unprotect(byte[] bytes) {
                return nativeProtection.unprotect(bytes);
            }
        };
        UUID generation;
        try (var runtime = PlatformRuntime.open(root, protection)) {
            generation = runtime.persistence().database().generation();
            runtime.started();
        }
        try (var runtime = PlatformRuntime.open(root, protection)) {
            assertEquals(generation, runtime.persistence().database().generation());
        }
        var bootstrap = root.resolve("db-access.bin");
        byte[] damaged = Files.readAllBytes(bootstrap);
        damaged[damaged.length - 1] ^= 1;
        Files.write(bootstrap, damaged);
        byte[] dbBefore = Files.readAllBytes(root.resolve("platform.mv.db"));
        assertThrows(Exception.class, () -> PlatformRuntime.open(root, protection));
        assertArrayEquals(damaged, Files.readAllBytes(bootstrap));
        assertArrayEquals(dbBefore, Files.readAllBytes(root.resolve("platform.mv.db")));
    }

    static String marker(PlatformRuntime runtime) throws Exception {
        try (var connection = runtime.persistence().database().openConnection();
                var rows = connection.createStatement().executeQuery("SELECT shutdown_state FROM app_state")) {
            assertTrue(rows.next());
            return rows.getString(1);
        }
    }
}
