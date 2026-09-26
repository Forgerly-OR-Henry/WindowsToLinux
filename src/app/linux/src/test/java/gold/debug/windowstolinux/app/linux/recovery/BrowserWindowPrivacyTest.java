package gold.debug.windowstolinux.app.linux.recovery;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.atomic.*;
import java.util.function.Consumer;

import gold.debug.windowstolinux.app.windows.window.WindowPrivacy;
import org.junit.jupiter.api.Test;

class BrowserWindowPrivacyTest {
    @Test
    void ownedBrowserHidesWithoutLosingTerminalAndReturnsOnlyOnUnlock() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getProperty("os.name").startsWith("Windows"));
        var callback = new AtomicReference<Consumer<Boolean>>();
        var removed = new AtomicBoolean();
        try (var browser = new PlaywrightTerminalSession(false,
                "data:text/html,<pre data-wtl-terminal tabindex='0'>private terminal fixture</pre>", listener -> {
                    callback.set(listener);
                    listener.accept(false);
                    return () -> removed.set(true);
                })) {
            browser.open();
            var field = PlaywrightTerminalSession.class.getDeclaredField("privacy");
            field.setAccessible(true);
            var privacy = (WindowPrivacy) field.get(browser);
            var targets = browser.targets();
            assertEquals(1, targets.size());
            long generation = browser.bind(targets.getFirst().id());
            assertTrue(privacy.visibleWindows() > 0);
            callback.get().accept(true);
            assertEquals(0, privacy.visibleWindows());
            assertTrue(browser.observe(generation).text().contains("private terminal fixture"));
            assertTrue(browser.valid(generation));
            callback.get().accept(false);
            assertTrue(privacy.visibleWindows() > 0);
        }
        assertTrue(removed.get());
    }

    @Test
    void rejectsUnownedNativeProcessBeforeWindowChanges() {
        var privacy = new WindowPrivacy();
        assertThrows(IllegalStateException.class, () -> privacy.identify(ProcessHandle.current().pid()));
    }
}
