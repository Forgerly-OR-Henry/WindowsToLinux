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
        while (path != null && !Files.isRegularFile(path.resolve("docs/reference/Config.md"))) path = path.getParent();
        if (path == null) throw new IllegalStateException("Project documentation root is unavailable");
        return path;
    }

    AppConfig load(String yaml, String mode) throws IOException {
        return ConfigLoader.load(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)), mode);
    }

    String sample() throws IOException {
        try (var input = ConfigLoader.class.getResourceAsStream(ConfigLoader.RESOURCE)) {
            assertNotNull(input);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n").replaceAll("(?m)[ \t]+#.*$", "");
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
    void outputPolicyDefaultsModeOverridesAndStrictTypes() throws Exception {
        String yaml = sample();
        var defaults = load(yaml, "app").ai();
        assertEquals(32768, defaults.recommendedMaxOutputTokens());
        assertTrue(defaults.allowOutputTokenOverride());
        assertEquals(8192, defaults.verificationMaxOutputTokens());
        String override = yaml.replace("jar: {}", "jar:\n  ai:\n    recommended-max-output-tokens: 65536\n    allow-output-token-override: false\n    verification-max-output-tokens: 4096");
        var jar = load(override, "jar").ai();
        assertEquals(65536, jar.recommendedMaxOutputTokens());
        assertFalse(jar.allowOutputTokenOverride());
        assertEquals(4096, jar.verificationMaxOutputTokens());
        assertEquals(defaults, load(override, "app").ai());
        assertEquals(32768, defaults.recommendedMaxOutputTokens());
        for (String key : new String[]{"recommended-max-output-tokens", "verification-max-output-tokens"}) {
            String value = key.equals("recommended-max-output-tokens") ? "32768" : "8192";
            assertThrows(IOException.class, () -> load(yaml.replace("    " + key + ": " + value + "\n", ""), "app"));
            assertThrows(IOException.class, () -> load(yaml.replace(key + ": " + value, key + ": " + value + "\n    " + key + ": 1"), "app"));
            for (String invalid : new String[]{"0", "-1", "2147483648", "1.5", "\"8192\"", "true", "null"})
                assertThrows(IOException.class, () -> load(yaml.replace(key + ": " + value, key + ": " + invalid), "app"), key + ": " + invalid);
            load(yaml.replace(key + ": " + value, key + ": 2147483647"), "app");
        }
        for (String invalid : new String[]{"\"true\"", "1", "null"})
            assertThrows(IOException.class, () -> load(yaml.replace("allow-output-token-override: true", "allow-output-token-override: " + invalid), "app"));
        assertThrows(IOException.class, () -> load(yaml.replace("    allow-output-token-override: true\n", ""), "app"));
        assertThrows(IOException.class, () -> load(yaml.replace("allow-output-token-override: true", "allow-output-token-override: true\n    allow-output-token-override: false"), "app"));
    }

    @Test
    void nativeStateLimitsAreRequiredStrictAndCapturedWithTheAiConfiguration() throws Exception {
        String yaml = sample();
        var configured = load(yaml.replace("max-states: 4096", "max-states: 8192")
            .replace("max-total-bytes: 67108864", "max-total-bytes: 134217728")
            .replace("max-value-bytes: 1048576", "max-value-bytes: 524288"), "app").ai().nativeState();
        assertEquals(new AppConfig.NativeState(8192, 134217728, 524288), configured);
        assertEquals(AppConfig.defaultsConfig().ai().nativeState(), load(yaml, "app").ai().nativeState());
        for (String field : new String[]{"max-states", "max-total-bytes", "max-value-bytes"}) {
            String line = yaml.lines().filter(value -> value.stripLeading().startsWith(field + ":")).findFirst().orElseThrow();
            assertThrows(IOException.class, () -> load(yaml.replace(line + "\n", ""), "app"));
            assertThrows(IOException.class, () -> load(yaml.replace(line, line + "\n" + line), "app"));
            for (String invalid : new String[]{"0", "-1", "null", "true", "1.5", "'4096'", "9223372036854775808"})
                assertThrows(IOException.class, () -> load(yaml.replace(line, "      " + field + ": " + invalid), "app"));
        }
        assertThrows(IOException.class, () -> load(yaml.replace("max-states: 4096", "max-states: 2147483648"), "app"));
        assertThrows(IOException.class, () -> load(yaml.replace("max-value-bytes: 1048576", "max-value-bytes: 1048577"), "app"));
        assertThrows(IOException.class, () -> load(yaml.replace("max-total-bytes: 67108864", "max-total-bytes: 1048575"), "app"));
        assertThrows(IOException.class, () -> load(yaml.replace("native-state:", "native-state:\n      extra: 1"), "app"));
    }

    @Test
    void internalAiTimeoutsRemainIndependentAndRejectRemovedTokenFields() throws Exception {
        String yaml = sample();
        var defaults = load(yaml, "app").ai();
        assertEquals(AppConfig.defaultsConfig().ai(), defaults);
        assertEquals(60, defaults.recovery().timeoutSeconds());
        assertEquals(30, defaults.deletion().timeoutSeconds());
        assertEquals(20, defaults.file().timeoutSeconds());
        String override = yaml.replace("jar: {}", "jar:\n  ai:\n    recovery:\n      timeout-seconds: 75\n    deletion:\n      timeout-seconds: 45\n    file:\n      timeout-seconds: 25");
        var jar = load(override, "jar").ai();
        assertEquals(75, jar.recovery().timeoutSeconds());
        assertEquals(45, jar.deletion().timeoutSeconds());
        assertEquals(25, jar.file().timeoutSeconds());
        assertEquals(defaults, load(override, "app").ai());
        for (String purpose : new String[]{"recovery", "deletion", "file"}) {
            for (String mode : new String[]{"common", "jar"}) {
                String invalid = mode.equals("common")
                    ? yaml.replace("    " + purpose + ":\n      timeout-seconds:", "    " + purpose + ":\n      max-tokens: 1\n      timeout-seconds:")
                    : yaml.replace("jar: {}", "jar:\n  ai:\n    " + purpose + ":\n      max-tokens: 1");
                var rejected = assertThrows(IOException.class, () -> load(invalid, "app"));
                assertTrue(rejected.getMessage().contains("Unknown configuration field: ai." + purpose + ".max-tokens"));
            }
            String original = "    " + purpose + ":\n      timeout-seconds: " + switch (purpose) {
                case "recovery" -> 60;
                case "deletion" -> 30;
                default -> 20;
            };
            assertThrows(IOException.class, () -> load(yaml.replace(original + "\n", ""), "app"));
            for (String invalid : new String[]{"0", "86401", "\"20\"", "null"})
                assertThrows(IOException.class, () -> load(yaml.replace(original, "    " + purpose + ":\n      timeout-seconds: " + invalid), "app"));
        }
    }

    @Test
    void guideTableCoversAllKeysAndExampleIsActuallyLoadable() throws Exception {
        String guide = Files.readString(root().resolve("docs/reference/Config.md")).replace("\r\n", "\n");
        guide = guide.split("## 部署 Mod 固定参数", 2)[0];
        var matcher = Pattern.compile("(?m)^\\| `([^`]+)` \\|").matcher(guide);
        var keys = new HashSet<String>();
        while (matcher.find()) assertTrue(keys.add(matcher.group(1)));
        assertEquals(ConfigLoader.keys(), keys);
        String example = guide.substring(guide.indexOf("```yaml\n") + 8).split("```", 2)[0];
        for (String mode : new String[]{"class", "jar", "app"})
            assertEquals(ConfigLoader.load(mode), load(example, mode));
    }
}
