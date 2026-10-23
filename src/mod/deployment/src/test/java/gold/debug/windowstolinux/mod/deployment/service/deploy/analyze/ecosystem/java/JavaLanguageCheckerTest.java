package gold.debug.windowstolinux.mod.deployment.service.deploy.analyze.ecosystem.java;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import gold.debug.windowstolinux.mod.deployment.service.core.model.language.LanguageEcosystemType;
import gold.debug.windowstolinux.mod.deployment.service.core.model.language.SourceLanguageType;
import gold.debug.windowstolinux.mod.deployment.service.deploy.analyze.source.SourceCheckFacts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JavaLanguageCheckerTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void reportsLanguageMarkersWithoutOpeningTheJar() throws Exception {
        Files.writeString(temporaryDirectory.resolve("app.jar"), "not an archive");
        SourceCheckFacts source = new SourceCheckFacts(1,
            List.of(Path.of("src/App.java"), Path.of("app.jar")), "class App {}", "reviewed-source");

        var facts = new JavaLanguageChecker().inspect(source);

        assertTrue(facts.ecosystems().contains(LanguageEcosystemType.JAVA));
        assertTrue(facts.sourceLanguages().contains(SourceLanguageType.JAVA));
        assertTrue(facts.values().isEmpty());
        assertEquals(1, facts.evidence().size());
        assertEquals(Path.of("src/App.java").toString(), facts.evidence().getFirst().source());
    }
}
