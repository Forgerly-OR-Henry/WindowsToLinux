package gold.debug.windowstolinux.app.linux.recovery;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;

import com.microsoft.playwright.Playwright;
import org.junit.jupiter.api.Test;

class PlaywrightDriverTest {
    @Test
    void aDeadProcessDoesNotEraseFailedConnectionCleanupAndRetryKeepsTheSameOwner() throws Exception {
        var driver = new PlaywrightDriver();
        var original = driver.start();
        var process = (Process) BrowserCleanupTest.field(driver, "process");
        var calls = new AtomicInteger();
        var failure = new IllegalStateException("Original connection cleanup unconfirmed");
        var connection = PlaywrightDriver.class.getDeclaredField("connection");
        connection.setAccessible(true);
        var wrapped = (Playwright) Proxy.newProxyInstance(Playwright.class.getClassLoader(),
                new Class<?>[]{Playwright.class}, (proxy, method, args) -> {
                    assertEquals("close", method.getName());
                    if (calls.incrementAndGet() == 1) {
                        original.close();
                        throw failure;
                    }
                    original.close();
                    return null;
                });
        connection.set(driver, wrapped);
        try {
            assertSame(failure, assertThrows(IllegalStateException.class, driver::close));
            assertFalse(process.isAlive());
            assertSame(wrapped, connection.get(driver));
            assertSame(process, BrowserCleanupTest.field(driver, "process"));
            driver.close();
            assertEquals(2, calls.get());
            assertNull(connection.get(driver));
            driver.close();
            assertEquals(2, calls.get());
        } finally {
            original.close();
            driver.close();
        }
    }
}
