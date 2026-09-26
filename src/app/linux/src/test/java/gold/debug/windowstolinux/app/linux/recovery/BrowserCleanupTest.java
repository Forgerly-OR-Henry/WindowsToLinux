package gold.debug.windowstolinux.app.linux.recovery;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import com.microsoft.playwright.BrowserContext;
import org.junit.jupiter.api.Test;

class BrowserCleanupTest {
    static Object field(PlaywrightTerminalSession browser, String name) throws Exception {
        var field = PlaywrightTerminalSession.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(browser);
    }

    static PlaywrightTerminalSession browser(AutoCloseable registration) {
        return new PlaywrightTerminalSession(true, "data:text/html,<pre data-wtl-terminal>cleanup fixture</pre>",
                listener -> registration);
    }

    @Test
    void failedOriginalContextCloseRetainsBrowserAndRetriesTheRemainingOwner() throws Exception {
        var removed = new AtomicBoolean();
        var browser = browser(() -> removed.set(true));
        try (browser) {
            browser.open();
            assertEquals(1, browser.processes().size());
            assertTrue(browser.processes().getFirst().alive());
            var actual = (BrowserContext) field(browser, "context");
            var attempts = new AtomicInteger();
            var wrapped = (BrowserContext) Proxy.newProxyInstance(BrowserContext.class.getClassLoader(),
                    new Class<?>[]{BrowserContext.class}, (proxy, method, args) -> {
                        if (method.getName().equals("close") && attempts.incrementAndGet() == 1)
                            throw new IllegalStateException("fixture close refused");
                        try {
                            return method.invoke(actual, args);
                        } catch (InvocationTargetException cause) {
                            throw cause.getCause();
                        }
                    });
            var context = PlaywrightTerminalSession.class.getDeclaredField("context");
            context.setAccessible(true);
            context.set(browser, wrapped);
            var failure = assertThrows(BrowserRecoveryException.class, browser::close);
            assertEquals(BrowserRecoveryException.Reason.CLEANUP_FAILED, failure.reason());
            assertFalse(browser.resourcesClosed());
            assertFalse(removed.get());
            assertTrue(browser.processes().getFirst().alive());
            assertThrows(BrowserRecoveryException.class, browser::open);
            browser.close();
            assertEquals(2, attempts.get());
            assertTrue(browser.resourcesClosed());
            assertTrue(removed.get());
            assertTrue(browser.processes().stream().noneMatch(BrowserProcess::alive));
        }
    }

    @Test
    void failedPrivacyRemovalCanRetryAfterActualBrowserAndWorkerExit() throws Exception {
        var attempts = new AtomicInteger();
        var browser = browser(() -> {
            if (attempts.incrementAndGet() == 1)
                throw new IllegalStateException("fixture registration retained");
        });
        try (browser) {
            browser.open();
            assertThrows(BrowserRecoveryException.class, browser::close);
            assertFalse(browser.resourcesClosed());
            assertTrue(browser.processes().stream().noneMatch(BrowserProcess::alive));
            assertTrue(((ExecutorService) field(browser, "worker")).isTerminated());
            browser.close();
            assertTrue(browser.resourcesClosed());
            assertEquals(2, attempts.get());
            browser.close();
            assertEquals(2, attempts.get());
        }
    }

    @Test
    void observationTimeoutKeepsTheSameCleanupAndDoesNotInterruptTheOriginalWorker() throws Exception {
        var removed = new AtomicBoolean();
        var browser = browser(() -> removed.set(true));
        var release = new CountDownLatch(1);
        var entered = new CountDownLatch(1);
        var interrupted = new AtomicBoolean();
        try {
            browser.open();
            var worker = (ExecutorService) field(browser, "worker");
            var original = worker.submit(() -> {
                entered.countDown();
                try {
                    release.await();
                } catch (InterruptedException failure) {
                    interrupted.set(true);
                    Thread.currentThread().interrupt();
                }
            });
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertThrows(BrowserRecoveryException.class, browser::close);
            var cleanup = field(browser, "cleanup");
            assertNotNull(cleanup);
            assertFalse(((Future<?>) cleanup).isDone());
            assertFalse(original.isCancelled());
            assertFalse(interrupted.get());
            assertFalse(removed.get());
            assertTrue(browser.processes().getFirst().alive());
            assertFalse(browser.resourcesClosed());
            release.countDown();
            browser.close();
            assertSame(cleanup, field(browser, "cleanup"));
            assertTrue(browser.resourcesClosed());
            assertTrue(worker.isTerminated());
            assertTrue(removed.get());
            assertFalse(interrupted.get());
        } finally {
            release.countDown();
            browser.close();
        }
    }

    @Test
    void cleanupPreservesCallerInterruptionAndProcessIdentityCannotTargetAnotherProcess() throws Exception {
        var current = ProcessHandle.current();
        assertThrows(IllegalStateException.class, () -> BrowserProcess.capture(current.pid()));
        assertFalse(
                new BrowserProcess(current.pid(), current.info().startInstant().orElseThrow().minusSeconds(1)).alive());
        try (var browser = browser(() -> {
        })) {
            browser.open();
            Thread.currentThread().interrupt();
            try {
                browser.close();
                assertTrue(Thread.currentThread().isInterrupted());
                assertTrue(browser.resourcesClosed());
            } finally {
                Thread.interrupted();
            }
        }
    }
}
