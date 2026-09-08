package gold.debug.windowstolinux.shared.analyze.ecosystem.java;

import gold.debug.windowstolinux.shared.analyze.source.SourceInspectionFacts;
import gold.debug.windowstolinux.shared.model.language.LanguageEcosystemType;
import gold.debug.windowstolinux.shared.model.language.SourceLanguageType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaLanguageInspectorTest {
    @TempDir Path temporaryDirectory;

    @Test
    void reportsLanguageMarkersWithoutOpeningTheJar() throws Exception {
        Files.writeString(temporaryDirectory.resolve("app.jar"), "not an archive");
        SourceInspectionFacts source = new SourceInspectionFacts(1, List.of(Path.of("src/App.java"), Path.of("app.jar")), "class App {}");

        var facts = new JavaLanguageInspector().inspect(source);

        assertTrue(facts.ecosystems().contains(LanguageEcosystemType.JAVA));
        assertTrue(facts.sourceLanguages().contains(SourceLanguageType.JAVA));
        assertTrue(facts.values().isEmpty());
        assertEquals(1, facts.evidence().size());
        assertEquals(Path.of("src/App.java").toString(), facts.evidence().getFirst().source());
    }
}
