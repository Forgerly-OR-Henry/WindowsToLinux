package gold.debug.windowstolinux.app.ui.mod;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import javax.swing.*;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.ui.page.*;
import org.junit.jupiter.api.Test;

class ModTaskPageTest {
    @Test void taskTemplatesRetainExactReleaseAndActivationLifetime() throws Exception {
        var environment = new UiEnvironment(Locale.ENGLISH, "light", false);
        var registry = new ModPageRegistry(new ModPageRegistry.PageHost() {
            public void show(ModPageRegistry.PageKey key, PageSpec spec, JComponent component) {}
            public void focus(ModPageRegistry.PageKey key) {}
            public void remove(ModPageRegistry.PageKey key) {}
            public void environment(UiEnvironment next) {}
        }, environment);
        var active = new LinkedHashMap<String, BooleanSupplier>();
        var pages = new LinkedHashMap<String, Page>();
        registry.installTaskPages((owner, version, activation) -> {
            assertTrue(SwingUtilities.isEventDispatchThread());
            active.put(owner + "@" + version, activation);
            var page = new Page(); pages.put(owner, page); return page;
        });
        var first = registry.begin("example.first", "1.0.0", false, UiExtensionValues.PLATFORM);
        var second = registry.begin("example.second", "2.0.0", false, UiExtensionValues.PLATFORM);
        var rescue = registry.begin("example.rescue", "1.0.0", true, UiExtensionValues.PLATFORM);
        try {
            assertThrows(ApiException.class, first::createTaskPage);
            first.commit(); second.commit(); rescue.commit();
            first.createTaskPage(); second.createTaskPage();
            assertThrows(ApiException.class, rescue::createTaskPage);
            assertEquals(Set.of("example.first@1.0.0", "example.second@2.0.0"), active.keySet());
            assertTrue(active.get("example.first@1.0.0").getAsBoolean());
            registry.environment(new UiEnvironment(Locale.SIMPLIFIED_CHINESE, "dark", true));
            assertTrue(pages.values().stream().allMatch(page -> page.environment.locked()));
            first.seal();
            assertFalse(active.get("example.first@1.0.0").getAsBoolean());
            assertTrue(active.get("example.second@2.0.0").getAsBoolean());
            assertTrue(pages.get("example.first").closed);
            assertFalse(pages.get("example.second").closed);
            assertThrows(ApiException.class, first::createTaskPage);
        } finally { first.close(); second.close(); rescue.close(); }
        assertTrue(pages.values().stream().allMatch(page -> page.closed));
        assertTrue(active.values().stream().noneMatch(BooleanSupplier::getAsBoolean));
    }

    private static final class Page implements ModPage {
        UiEnvironment environment;
        boolean closed;
        public JComponent create(UiEnvironment value) { return new JPanel(); }
        public void environmentChanged(UiEnvironment value) {
            assertTrue(SwingUtilities.isEventDispatchThread()); environment = value;
        }
        public void close() { assertTrue(SwingUtilities.isEventDispatchThread()); closed = true; }
    }
}
