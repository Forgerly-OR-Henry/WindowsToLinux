package gold.debug.windowstolinux.shared.standard.analyze.ecosystem.node;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import gold.debug.windowstolinux.shared.model.language.LanguageFactKind;
import gold.debug.windowstolinux.shared.model.language.SourceLanguageType;
import gold.debug.windowstolinux.shared.standard.analyze.source.SourceInspectionFacts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NodeLanguageInspectorTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void preservesMixedJavaScriptAndTypeScriptAndOnlyAcceptsAnExactEngine() throws Exception {
        Files.writeString(temporaryDirectory.resolve("package.json"), "{\"engines\":{\"node\":\"22\"}}");
        SourceInspectionFacts source = new SourceInspectionFacts(3,
                List.of(Path.of("package.json"), Path.of("src/a.js"), Path.of("src/b.ts")), "");

        var facts = new NodeLanguageInspector().inspect(temporaryDirectory, source);

        assertEquals("22", facts.values().get(LanguageFactKind.NODE_MAJOR_VERSION));
        assertTrue(facts.sourceLanguages()
                .containsAll(List.of(SourceLanguageType.JAVASCRIPT, SourceLanguageType.TYPESCRIPT)));

        Files.writeString(temporaryDirectory.resolve("package.json"), "{\"engines\":{\"node\":\">=20\"}}");
        assertTrue(new NodeLanguageInspector().inspect(temporaryDirectory, source).values().isEmpty());
    }
}
