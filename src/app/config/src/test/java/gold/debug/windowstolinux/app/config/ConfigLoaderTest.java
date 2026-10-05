package gold.debug.windowstolinux.app.config;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.HashSet;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

class ConfigLoaderTest {
    static Path root() {
        Path path = Path.of("").toAbsolutePath();
        while (path != null && !Files.isRegularFile(path.resolve("docs/Config.md"))) path = path.getParent();
        if (path == null) throw new IllegalStateException("Project documentation root is unavailable");
        return path;
    }

    AppConfig load(String yaml, String mode) throws IOException {
        return ConfigLoader.load(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)), mode);
    }

    String sample() throws IOException {
        try (var input = ConfigLoader.class.getResourceAsStream(ConfigLoader.RESOURCE)) {
            assertNotNull(input);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        }
    }

    @Test
    void fieldMergeSelectsOnlyCurrentModeAndKeepsExplicitFalse() throws Exception {
        String yaml = sample().replace("jar: {}", "jar:\n  ui:\n    navigation-collapsed: true\n  defaults:\n    connect-seconds: 45");
        var jar = load(yaml, "jar");
        assertTrue(jar.ui().navigationCollapsed());
        assertTrue(jar.modLoad());
        assertEquals(45, jar.defaults().connectSeconds());
        assertEquals(300, jar.defaults().idleSeconds());
        assertFalse(load(yaml, "class").modLoad());
        assertTrue(load(yaml, "app").modLoad());
        assertEquals("src/app/db/data", load(yaml, "class").dataDirectory());
        assertEquals("data", load(yaml, "app").dataDirectory());
        var snapshot = load(yaml, "app");
        load(yaml.replace("heartbeat-seconds: 30", "heartbeat-seconds: 7"), "app");
        assertEquals(30, snapshot.ssh().heartbeatSeconds());
    }

    @Test
    void rejectsMissingResourceFieldsSyntaxDuplicatesUnknownsAndIllegalEffectiveValues() throws Exception {
        assertThrows(IOException.class, () -> ConfigLoader.load((java.io.InputStream) null, "class"));
        assertThrows(IOException.class, () -> load(" ".repeat(262145), "class"));
        String yaml = sample();
        for (String invalid : new String[]{"common: [", "[]", yaml + "unknown: {}\n",
            yaml.replace("  ui:", "  unexpected:"), yaml.replace("heartbeat-seconds: 30", "heartbeat-seconds: 30\n    heartbeat-seconds: 7"),
            yaml.replace("    idle-seconds: 300\n", ""), yaml.replace("emergency-records: 100", "emergency-records: 0"),
            yaml.replace("emergency-records: 100", "emergency-records: 101"),
            yaml.replace("locale: system", "locale: fr-FR"), yaml.replace("theme: SYSTEM", "theme: dark"),
            yaml.replace("base-url: \"\"", "base-url: \"http://example.com\""), yaml.replace("heartbeat-seconds: 30", "heartbeat-seconds: null"),
            yaml.replace("heartbeat-seconds: 30", "heartbeat-seconds: 1.2"), yaml.replace("data-directory: data", "data-directory: \"data;bad\""),
            yaml + "---\ncommon: {}\n"})
            assertThrows(IOException.class, () -> load(invalid, "app"), invalid);
        assertThrows(IOException.class, () -> load(yaml, "unknown"));
        // Invalid values overwritten by the selected mode do not become effective.
        assertEquals("src/app/db/data", load(yaml.replace("data-directory: data", "data-directory: \"\""), "class").dataDirectory());
    }

    @Test
    void onlyClassAllowsModSwitchAndReleaseModesAlwaysLoad() throws Exception {
        String yaml = sample();
        assertFalse(load(yaml, "class").modLoad());
        assertTrue(load(yaml.replace("auto-load: false", "auto-load: true"), "class").modLoad());
        for (String mode : new String[]{"class", "jar", "app"}) {
            for (String value : new String[]{"true", "false"}) {
                assertThrows(IOException.class, () -> load(yaml.replace("  mod:\n    store:",
                    "  mod:\n    auto-load: " + value + "\n    store:"), mode));
                for (String section : new String[]{"jar", "app"})
                    assertThrows(IOException.class, () -> load(yaml.replace(section + ": {}",
                        section + ":\n  mod:\n    auto-load: " + value), mode));
            }
        }
        for (String value : new String[]{"\"false\"", "null", "7"})
            assertThrows(IOException.class, () -> load(yaml.replace("auto-load: false", "auto-load: " + value), "class"));
        String withoutSwitch = yaml.replace("  mod:\n    auto-load: false\n", "");
        assertThrows(IOException.class, () -> load(withoutSwitch, "class"));
        for (String mode : new String[]{"jar", "app"}) {
            assertTrue(load(yaml, mode).modLoad());
            assertTrue(load(withoutSwitch, mode).modLoad());
        }
    }

    @Test
    void permitsBoundsAbsolutePathsAndEmptyStoreButRejectsWrongTypes() throws Exception {
        String yaml = sample().replace("data-directory: data", "data-directory: 'I:/configured-data'")
            .replace("base-url: \"\"", "base-url: https://mods.example.com/api")
            .replace("emergency-records: 100", "emergency-records: 1").replace("connect-seconds: 30", "connect-seconds: 86400");
        var value = load(yaml, "app");
        assertTrue(Path.of(value.dataDirectory()).isAbsolute());
        assertEquals(1, value.emergencyRecords());
        assertEquals(86400, value.defaults().connectSeconds());
        assertEquals("https://mods.example.com/api", value.storeUrl().orElseThrow().toString());
        assertTrue(load(sample(), "app").storeUrl().isEmpty());
    }

    @Test
    void permitsOnlyExplicitSecureOrNumericLoopbackStoreRoots() throws Exception {
        String yaml = sample();
        for (String root : new String[]{"https://mods.example.com/api", "http://127.0.0.1:8123/store/", "http://[::1]:8123/store/"})
            assertEquals(root, load(yaml.replace("base-url: \"\"", "base-url: \"" + root + "\""), "app")
                .storeUrl().orElseThrow().toString());
        for (String root : new String[]{"http://localhost:8123/", "http://example.com/", "http://127.0.0.2/",
            "http://127.0.0.1.example.com/", "//mods.example.com/", "https://user@mods.example.com/",
            "http://user@127.0.0.1/", "https://mods.example.com/?token=secret", "https://mods.example.com/#part",
            "https://mods.example.com:0/", "http://127.0.0.1:65536/", "https:/missing-host", "http:/missing-host", "https://[invalid]/"})
            assertThrows(IOException.class, () -> load(yaml.replace("base-url: \"\"", "base-url: \"" + root + "\""), "app"), root);
    }

    @Test
    void guideTableCoversAllKeysAndExampleIsActuallyLoadable() throws Exception {
        String guide = Files.readString(root().resolve("docs/Config.md")).replace("\r\n", "\n");
        var matcher = Pattern.compile("(?m)^\\| `([^`]+)` \\|").matcher(guide);
        var keys = new HashSet<String>();
        while (matcher.find()) assertTrue(keys.add(matcher.group(1)));
        assertEquals(ConfigLoader.keys(), keys);
        String example = guide.substring(guide.indexOf("```yaml\n") + 8).split("```", 2)[0];
        for (String mode : new String[]{"class", "jar", "app"})
            assertEquals(ConfigLoader.load(mode), load(example, mode));
    }
}
