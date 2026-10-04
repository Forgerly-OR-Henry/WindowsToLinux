package gold.debug.windowstolinux.mod.deployment.service.standard.analyze.ecosystem.python;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import gold.debug.windowstolinux.mod.deployment.service.core.model.language.LanguageFactKind;
import gold.debug.windowstolinux.mod.deployment.service.core.model.language.SourceLanguageType;
import gold.debug.windowstolinux.mod.deployment.service.standard.analyze.source.SourceCheckFacts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PythonLanguageCheckerTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void reportsExactVersionAndUniqueBoundedModulePath() throws Exception {
        Files.writeString(temporaryDirectory.resolve("pyproject.toml"), "[project]\nrequires-python = \"==3.12.*\"\n");
        SourceCheckFacts source = new SourceCheckFacts(2,
            List.of(Path.of("pyproject.toml"), Path.of("src/demo/__main__.py")), "", "reviewed-source");

        var facts = new PythonLanguageChecker().inspect(temporaryDirectory, source);

        assertTrue(facts.sourceLanguages().contains(SourceLanguageType.PYTHON));
        assertEquals("3.12", facts.values().get(LanguageFactKind.PYTHON_VERSION));
        assertEquals("demo", facts.values().get(LanguageFactKind.PYTHON_ENTRYPOINT));
    }
}
