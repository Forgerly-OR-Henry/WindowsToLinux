package gold.debug.windowstolinux.app.main.startup;

import static gold.debug.windowstolinux.app.main.startup.PlatformTaskAdmissionIntegrationTest.*;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;

import gold.debug.windowstolinux.app.db.task.*;
import gold.debug.windowstolinux.app.linux.recovery.*;
import gold.debug.windowstolinux.app.service.recovery.TaskBrowserSession;
import gold.debug.windowstolinux.appapi.task.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TaskBrowserSessionTest {
    @TempDir
    Path root;

    static PlaywrightTerminalSession actual(TaskBrowserSession session) throws Exception {
        var field = TaskBrowserSession.class.getDeclaredField("browser");
        field.setAccessible(true);
        return (PlaywrightTerminalSession) field.get(session);
    }

    static long claims(PlatformRuntime runtime) throws Exception {
        return count(runtime.persistence().database(),
                "SELECT COUNT(*) FROM platform_task_resource WHERE resource_id LIKE 'browser-v1:%' AND released_at IS NULL");
    }

    static TaskBrowserSession create(PlatformRuntime runtime, TaskContext task,
            Function<Consumer<Boolean>, AutoCloseable> privacy) throws Exception {
        return new TaskBrowserSession(runtime.persistence().database(), runtime.tasks(), task, OWNER, VERSION, privacy);
    }

    @Test
    void nativeDriverStillAliveKeepsTheOriginalDurableClaimAfterLibraryCloseReturns() throws Exception {
        try (var runtime = PlatformRuntime.open(root.resolve("data"), PlatformStartupTest.PROTECTION)) {
            initialize(runtime, false);
            var owned = new AtomicReference<TaskBrowserSession>();
            var handle = runtime.tasks().bind(OWNER, VERSION, false).submit(request(), task -> {
                var browser = create(runtime, task, runtime.security()::observeLock);
                owned.set(browser);
                browser.open();
                return success(null);
            });
            com.microsoft.playwright.Playwright original = null;
            ExecutorService worker = null;
            try {
                state(handle, TaskState.FINISHING);
                var browser = actual(owned.get());
                var driver = PlatformRecoveryServiceTest.field(browser, "driver");
                original = (com.microsoft.playwright.Playwright) PlatformRecoveryServiceTest.field(driver,
                        "connection");
                var process = (Process) PlatformRecoveryServiceTest.field(driver, "process");
                worker = (ExecutorService) PlatformRecoveryServiceTest.field(browser, "worker");
                var connection = driver.getClass().getDeclaredField("connection");
                connection.setAccessible(true);
                connection.set(driver,
                        java.lang.reflect.Proxy.newProxyInstance(
                                com.microsoft.playwright.Playwright.class.getClassLoader(),
                                new Class<?>[]{com.microsoft.playwright.Playwright.class}, (proxy, method, args) -> {
                                    assertEquals("close", method.getName());
                                    return null;
                                }));
                assertThrows(BrowserRecoveryException.class, owned.get()::close);
                assertTrue(process.isAlive());
                assertEquals(1, claims(runtime));
                assertEquals(TaskState.FINISHING, handle.snapshot().state());
                assertFalse(handle.completion().toCompletableFuture().isDone());
                worker.submit(original::close).get(15, TimeUnit.SECONDS);
                owned.get().close();
                assertFalse(process.isAlive());
                assertEquals(Outcome.SUCCEEDED,
                        handle.completion().toCompletableFuture().get(8, TimeUnit.SECONDS).outcome());
                assertEquals(0, claims(runtime));
            } finally {
                if (original != null && worker != null && !worker.isShutdown())
                    worker.submit(original::close).get(15, TimeUnit.SECONDS);
                if (owned.get() != null)
                    owned.get().close();
            }
        }
    }

    @Test
    void actualBrowserRetainsOriginalTaskUntilPhysicalAndDurableRelease() throws Exception {
        try (var runtime = PlatformRuntime.open(root.resolve("data"), PlatformStartupTest.PROTECTION)) {
            initialize(runtime, false);
            var owned = new AtomicReference<TaskBrowserSession>();
            var handle = runtime.tasks().bind(OWNER, VERSION, false).submit(request(), task -> {
                assertThrows(ApiException.class, () -> new TaskBrowserSession(runtime.persistence().database(),
                        runtime.tasks(), task, "foreign", VERSION, runtime.security()::observeLock));
                var browser = create(runtime, task, runtime.security()::observeLock);
                owned.set(browser);
                browser.open();
                browser.open();
                assertTrue(browser.available());
                var facts = new TaskLedger(runtime.persistence().database()).queries().recoveries(task.id(), "PROCESS",
                        "browser-v1:");
                assertEquals(1, facts.size());
                var checkpoint = ProcessRecovery.decode(facts.values().iterator().next(), "browser.v1");
                assertEquals(runtime.persistence().database().bootId(), checkpoint.boot());
                assertEquals(checkpoint.pid().getAsLong(), actual(browser).processes().getFirst().pid());
                assertEquals(checkpoint.started().orElseThrow(), actual(browser).processes().getFirst().started());
                assertTrue(actual(browser).processes().getFirst().alive());
                return success(null);
            });
            try {
                state(handle, TaskState.FINISHING);
                assertFalse(handle.completion().toCompletableFuture().isDone());
                assertEquals(1, claims(runtime));
                assertThrows(BrowserRecoveryException.class, owned.get()::targets);
                owned.get().close();
                assertEquals(Outcome.SUCCEEDED,
                        handle.completion().toCompletableFuture().get(8, TimeUnit.SECONDS).outcome());
                assertEquals(0, claims(runtime));
                assertTrue(actual(owned.get()).resourcesClosed());
                assertTrue(actual(owned.get()).processes().stream().noneMatch(BrowserProcess::alive));
                owned.get().close();
            } finally {
                if (owned.get() != null)
                    owned.get().close();
            }
        }
    }

    @Test
    void failedPrivacyCleanupKeepsTheSameClaimAndCanFinishAfterCallbackReturn() throws Exception {
        try (var runtime = PlatformRuntime.open(root.resolve("data"), PlatformStartupTest.PROTECTION)) {
            initialize(runtime, false);
            var owned = new AtomicReference<TaskBrowserSession>();
            var attempts = new AtomicInteger();
            var handle = runtime.tasks().bind(OWNER, VERSION, false).submit(request(), task -> {
                var browser = create(runtime, task, listener -> () -> {
                    if (attempts.incrementAndGet() == 1)
                        throw new IllegalStateException("fixture privacy cleanup");
                });
                owned.set(browser);
                browser.open();
                assertThrows(BrowserRecoveryException.class, browser::close);
                assertTrue(actual(browser).processes().stream().noneMatch(BrowserProcess::alive));
                assertFalse(actual(browser).resourcesClosed());
                assertEquals(1, claims(runtime));
                return success(null);
            });
            try {
                state(handle, TaskState.FINISHING);
                owned.get().close();
                state(handle, TaskState.ENDED);
                assertEquals(2, attempts.get());
                assertEquals(0, claims(runtime));
            } finally {
                if (owned.get() != null)
                    owned.get().close();
            }
        }
    }

    @Test
    void failedPrecreationOrPidCheckpointCannotAdmitTerminalWork() throws Exception {
        for (boolean beforeCreation : List.of(true, false)) {
            try (var runtime = PlatformRuntime.open(root.resolve("data-" + beforeCreation),
                    PlatformStartupTest.PROTECTION)) {
                initialize(runtime, false);
                var db = runtime.persistence().database();
                sql(db, "ALTER TABLE platform_task_resource ADD CONSTRAINT fixture_browser CHECK(resource_id NOT LIKE 'browser-v1:%' OR recovery_json NOT LIKE '%"
                        + (beforeCreation ? "STARTING" : "STARTED") + "%')");
                var owned = new AtomicReference<TaskBrowserSession>();
                var original = new AtomicReference<TaskContext>();
                var binding = runtime.tasks().bind(OWNER, VERSION, false);
                var handle = binding.submit(request(), task -> {
                    original.set(task);
                    var browser = create(runtime, task, runtime.security()::observeLock);
                    owned.set(browser);
                    try (browser) {
                        assertThrows(BrowserRecoveryException.class, browser::open);
                        assertEquals(beforeCreation ? 0 : 1, actual(browser).processes().size());
                        assertThrows(BrowserRecoveryException.class, browser::targets);
                        assertEquals(beforeCreation ? 0 : 1, claims(runtime));
                    }
                    return success(null);
                });
                try {
                    state(handle, TaskState.UNKNOWN);
                    PlatformProcessServiceTest.returned(runtime, original.get());
                    assertTrue(actual(owned.get()).resourcesClosed());
                    assertEquals(0, claims(runtime));
                    sql(db, "ALTER TABLE platform_task_resource DROP CONSTRAINT fixture_browser");
                    try (var recovery = binding.registerRecovery("fixture", (previous, task) -> {
                        owned.get().close();
                        return PlatformProcessServiceTest.reconciledFailure();
                    })) {
                        assertEquals(TaskState.ENDED, runtime.tasks().reconcile(handle.id()).toCompletableFuture()
                                .get(8, TimeUnit.SECONDS).state());
                    }
                    assertTrue(actual(owned.get()).processes().stream().noneMatch(BrowserProcess::alive));
                } finally {
                    if (owned.get() != null)
                        owned.get().close();
                }
            }
        }
    }

    @Test
    void failedCleanupCheckpointOrReleaseRetriesTheSameLeaseWithoutLaunchingAnotherBrowser() throws Exception {
        for (boolean checkpointFailure : List.of(true, false)) {
            try (var runtime = PlatformRuntime.open(root.resolve("data-" + checkpointFailure),
                    PlatformStartupTest.PROTECTION)) {
                initialize(runtime, false);
                var db = runtime.persistence().database();
                sql(db, "ALTER TABLE platform_task_resource ADD CONSTRAINT fixture_browser CHECK(resource_id NOT LIKE 'browser-v1:%' OR "
                        + (checkpointFailure ? "recovery_json NOT LIKE '%browser.closed.v1%'" : "released_at IS NULL")
                        + ")");
                var owned = new AtomicReference<TaskBrowserSession>();
                var original = new AtomicReference<TaskContext>();
                var binding = runtime.tasks().bind(OWNER, VERSION, false);
                var handle = binding.submit(request(), task -> {
                    original.set(task);
                    var browser = create(runtime, task, runtime.security()::observeLock);
                    owned.set(browser);
                    browser.open();
                    assertThrows(BrowserRecoveryException.class, browser::close);
                    assertTrue(actual(browser).resourcesClosed());
                    assertTrue(actual(browser).processes().stream().noneMatch(BrowserProcess::alive));
                    assertEquals(1, claims(runtime));
                    var facts = new TaskLedger(db).queries().recoveries(task.id(), "PROCESS", "browser-v1:").values().iterator()
                            .next();
                    assertEquals(checkpointFailure ? "browser.v1" : "browser.closed.v1", facts.protocol());
                    return success(null);
                });
                try {
                    state(handle, TaskState.UNKNOWN);
                    PlatformProcessServiceTest.returned(runtime, original.get());
                    var identities = actual(owned.get()).processes();
                    sql(db, "ALTER TABLE platform_task_resource DROP CONSTRAINT fixture_browser");
                    try (var recovery = binding.registerRecovery("fixture", (previous, task) -> {
                        owned.get().close();
                        return PlatformProcessServiceTest.reconciledFailure();
                    })) {
                        assertEquals(TaskState.ENDED, runtime.tasks().reconcile(handle.id()).toCompletableFuture()
                                .get(8, TimeUnit.SECONDS).state());
                    }
                    assertEquals(0, claims(runtime));
                    assertEquals(identities, actual(owned.get()).processes());
                } finally {
                    sql(db, "ALTER TABLE platform_task_resource DROP CONSTRAINT IF EXISTS fixture_browser");
                    if (owned.get() != null)
                        owned.get().close();
                }
            }
        }
    }
}
