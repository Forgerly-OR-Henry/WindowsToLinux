package gold.debug.windowstolinux.app.ui.i18n;

import gold.debug.windowstolinux.shared.model.message.LocalizedMessage;
import gold.debug.windowstolinux.shared.model.analysis.EvidenceConfidence;
import gold.debug.windowstolinux.shared.model.deployment.DeploymentStatus;
import gold.debug.windowstolinux.shared.model.deployment.DeploymentStep;
import gold.debug.windowstolinux.shared.model.lifecycle.AutostartState;
import gold.debug.windowstolinux.shared.model.lifecycle.LifecycleAction;
import gold.debug.windowstolinux.shared.model.lifecycle.RuntimeState;
import gold.debug.windowstolinux.shared.model.project.LanguageEcosystem;
import gold.debug.windowstolinux.shared.model.project.SourceLanguage;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MessageCatalogTest {
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([a-zA-Z][a-zA-Z0-9_.-]*)}");
    private final MessageCatalog chinese = MessageCatalog.forLanguageTag(MessageCatalog.SIMPLIFIED_CHINESE_TAG);
    private final MessageCatalog english = MessageCatalog.forLanguageTag(MessageCatalog.ENGLISH_TAG);

    @Test
    void rendersEnglishAndSimplifiedChineseWithNamedArguments() {
        assertEquals("Deployment", english.text("nav.deployment"));
        assertEquals("部署", chinese.text("nav.deployment"));
        assertEquals("Deployment failed: network timeout", english.text(
                LocalizedMessage.of("deployment.failed", Map.of("detail", "network timeout"))));
        assertEquals("部署失败：network timeout", chinese.text(
                LocalizedMessage.of("deployment.failed", Map.of("detail", "network timeout"))));
    }

    @Test
    void keepsTheCanonicalAndChineseCatalogKeysAndPlaceholdersIdentical() throws Exception {
        Properties canonical = properties("Messages.properties");
        Properties translated = properties("Messages_zh_CN.properties");

        assertEquals(canonical.stringPropertyNames(), translated.stringPropertyNames());
        for (String key : canonical.stringPropertyNames()) {
            Set<String> canonicalArguments = placeholders(canonical.getProperty(key));
            Set<String> translatedArguments = placeholders(translated.getProperty(key));
            assertEquals(canonicalArguments, translatedArguments, () -> "placeholder mismatch for " + key);

            Map<String, Object> arguments = new LinkedHashMap<>();
            canonicalArguments.forEach(argument -> arguments.put(argument, argument));
            assertDoesNotThrow(() -> english.text(key, arguments), () -> "English rendering failed for " + key);
            assertDoesNotThrow(() -> chinese.text(key, arguments), () -> "Chinese rendering failed for " + key);
        }
    }

    @Test
    void choosesTheSystemDefaultOnlyWhenNoPreferenceExists() {
        assertEquals(MessageCatalog.SIMPLIFIED_CHINESE_TAG,
                MessageCatalog.defaultLanguageTag(Locale.SIMPLIFIED_CHINESE));
        assertEquals(MessageCatalog.SIMPLIFIED_CHINESE_TAG,
                MessageCatalog.defaultLanguageTag(Locale.TRADITIONAL_CHINESE));
        assertEquals(MessageCatalog.ENGLISH_TAG, MessageCatalog.defaultLanguageTag(Locale.ENGLISH));
        assertEquals(MessageCatalog.ENGLISH_TAG, MessageCatalog.defaultLanguageTag(Locale.FRENCH));
    }

    @Test
    void rejectsMissingDataAndFallsBackToEnglishForUnsupportedTags() {
        assertThrows(IllegalArgumentException.class, () -> chinese.text("missing.key"));
        assertThrows(IllegalArgumentException.class, () -> chinese.text("deployment.failed"));
        assertEquals("Deployment", MessageCatalog.forLanguageTag("fr-FR").text("nav.deployment"));
        assertEquals("Deployment", MessageCatalog.forLanguageTag("zh-TW").text("nav.deployment"));
        assertEquals("Deployment", MessageCatalog.forLanguageTag(null).text("nav.deployment"));
    }

    @Test
    void mapsEveryStructuredUiCodeInBothLanguages() {
        for (EvidenceConfidence value : EvidenceConfidence.values()) {
            assertCode("analysis.confidence." + value.name().toLowerCase(Locale.ROOT));
        }
        for (DeploymentStatus value : DeploymentStatus.values()) {
            assertCode("deployment.status." + value.name().toLowerCase(Locale.ROOT));
        }
        for (DeploymentStep value : DeploymentStep.values()) {
            assertCode("deployment.step." + value.code());
        }
        for (LifecycleAction value : LifecycleAction.values()) {
            assertCode("lifecycle.action." + value.name().toLowerCase(Locale.ROOT));
        }
        for (RuntimeState value : RuntimeState.values()) {
            assertCode("runtime.state." + value.name().toLowerCase(Locale.ROOT));
        }
        for (AutostartState value : AutostartState.values()) {
            assertCode("autostart.state." + value.name().toLowerCase(Locale.ROOT));
        }
        for (LanguageEcosystem value : LanguageEcosystem.values()) {
            assertCode("language.ecosystem." + value.name().toLowerCase(Locale.ROOT));
        }
        for (SourceLanguage value : SourceLanguage.values()) {
            assertCode("language.source." + value.name().toLowerCase(Locale.ROOT));
        }
    }

    @Test
    void rendersMixedJavaScriptAndTypeScriptFactsWithoutChoosingAPrimaryLanguage() {
        Map<String, Object> values = Map.of("ecosystems", english.text("language.ecosystem.node_js"),
                "sources", english.text("language.source.javascript") + ", "
                        + english.text("language.source.typescript"));
        assertEquals("Language ecosystems: Node.js\nSource languages: JavaScript, TypeScript\n",
                english.text("source.languageSummary", values));
        assertEquals("语言生态：Node.js\n源码语言：JavaScript, TypeScript\n",
                chinese.text("source.languageSummary", values));
    }

    private static Properties properties(String fileName) throws IOException {
        String resource = "/gold/debug/windowstolinux/app/ui/i18n/messages/" + fileName;
        try (InputStream input = MessageCatalogTest.class.getResourceAsStream(resource)) {
            if (input == null) {
                throw new IOException("missing test resource: " + resource);
            }
            Properties properties = new Properties();
            properties.load(new InputStreamReader(input, StandardCharsets.UTF_8));
            return properties;
        }
    }

    private static Set<String> placeholders(String value) {
        Set<String> placeholders = new LinkedHashSet<>();
        Matcher matcher = PLACEHOLDER.matcher(value);
        while (matcher.find()) {
            placeholders.add(matcher.group(1));
        }
        return placeholders;
    }

    private void assertCode(String key) {
        assertDoesNotThrow(() -> english.text(key), () -> "missing English code mapping for " + key);
        assertDoesNotThrow(() -> chinese.text(key), () -> "missing Chinese code mapping for " + key);
    }
}
