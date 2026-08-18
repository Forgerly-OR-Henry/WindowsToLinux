package gold.debug.windowstolinux.shared.analyze.ecosystem.node.language;

import gold.debug.windowstolinux.shared.analyze.source.SourceInspection;
import gold.debug.windowstolinux.shared.model.project.LanguageFact;
import gold.debug.windowstolinux.shared.model.project.SourceLanguage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NodeLanguageInspectorTest {
    @TempDir Path temporaryDirectory;

    @Test
    void preservesMixedJavaScriptAndTypeScriptAndOnlyAcceptsAnExactEngine() throws Exception {
        Files.writeString(temporaryDirectory.resolve("package.json"), "{\"engines\":{\"node\":\"22\"}}");
        SourceInspection source = new SourceInspection(3, List.of(Path.of("package.json"), Path.of("src/a.js"), Path.of("src/b.ts")), "");

        var facts = new NodeLanguageInspector().inspect(temporaryDirectory, source);

        assertEquals("22", facts.values().get(LanguageFact.NODE_MAJOR_VERSION));
        assertTrue(facts.sourceLanguages().containsAll(List.of(SourceLanguage.JAVASCRIPT, SourceLanguage.TYPESCRIPT)));

        Files.writeString(temporaryDirectory.resolve("package.json"), "{\"engines\":{\"node\":\">=20\"}}");
        assertTrue(new NodeLanguageInspector().inspect(temporaryDirectory, source).values().isEmpty());
    }
}
