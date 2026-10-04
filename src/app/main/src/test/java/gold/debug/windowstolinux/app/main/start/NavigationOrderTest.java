package gold.debug.windowstolinux.app.main.start;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.List;

import gold.debug.windowstolinux.app.service.setting.NavigationOrderService;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NavigationOrderTest {
    @TempDir
    Path root;

    void unlock(PlatformRuntime runtime) throws Exception {
        var password = "isolated-sort-password".toCharArray();
        if (runtime.security().securityState().configured()) runtime.security().unlock(password);
        else runtime.security().setupAndUnlock(password, password.clone());
    }

    NavigationOrderService service(PlatformRuntime runtime) {
        return new NavigationOrderService(runtime.persistence().navigation(), runtime.security());
    }

    @Test
    void newEntriesAppendHiddenSlotsStayAndRestartRestoresCommittedOrder() throws Exception {
        var defaults = List.of("platform.servers", "shell.mods", "platform.models");
        try (var runtime = PlatformRuntime.open(root, PlatformStartupTest.PROTECTION)) {
            unlock(runtime);
            var service = service(runtime);
            assertEquals(defaults, service.load(defaults));
            service.save(defaults, defaults, defaults);
            assertEquals(List.of("platform.models", "shell.mods", "platform.servers"),
                service.save(List.of("platform.servers", "platform.models"), defaults, List.of("platform.models", "platform.servers")));
            var expected = service.load(defaults);
            assertThrows(ApiException.class, () -> service.save(defaults, defaults, defaults));
            assertEquals(expected, service.load(defaults));
        }
        try (var runtime = PlatformRuntime.open(root, PlatformStartupTest.PROTECTION)) {
            unlock(runtime);
            var service = service(runtime);
            var expanded = List.of("platform.servers", "platform.models", "tasks.title");
            var saved = service.load(expanded);
            assertEquals(List.of("platform.models", "shell.mods", "platform.servers", "tasks.title"), saved);
            assertEquals(List.of("platform.servers", "shell.mods", "platform.models", "tasks.title"), service.save(expanded, saved, expanded));
        }
    }

    @Test
    void corruptDuplicateAndFailedWritesRetainOriginalPreference() throws Exception {
        try (var runtime = PlatformRuntime.open(root, PlatformStartupTest.PROTECTION)) {
            unlock(runtime);
            var service = service(runtime);
            var defaults = List.of("platform.servers", "platform.models");
            service.save(defaults, defaults, defaults);
            String original = runtime.persistence().preferences().find("platform_navigation_order").orElseThrow();
            PlatformTaskAdmissionIntegrationTest.sql(runtime.persistence().database(), "ALTER TABLE desktop_preference ADD CONSTRAINT navigation_write_trap CHECK(preference_key <> 'platform_navigation_order' OR preference_value='" + original.replace("'", "''") + "')");
            assertThrows(ApiException.class, () -> service.save(defaults, defaults, defaults.reversed()));
            assertEquals(original, runtime.persistence().preferences().find("platform_navigation_order").orElseThrow());
            PlatformTaskAdmissionIntegrationTest.sql(runtime.persistence().database(), "ALTER TABLE desktop_preference DROP CONSTRAINT navigation_write_trap");
            for (String broken : List.of("broken", "{\"formatVersion\":1,\"entries\":[\"platform.models\",\"platform.models\"]}",
                "{\"formatVersion\":4294967297,\"entries\":[\"platform.models\"]}")) {
                runtime.persistence().preferences().save("platform_navigation_order", broken);
                assertThrows(ApiException.class, () -> service.load(defaults));
                assertThrows(ApiException.class, () -> service.save(defaults, defaults, defaults.reversed()));
                assertEquals(broken, runtime.persistence().preferences().find("platform_navigation_order").orElseThrow());
            }
        }
    }
}
