package gold.debug.windowstolinux.shared.analyze.ecosystem.python.language;

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

class PythonLanguageInspectorTest {
    @TempDir Path temporaryDirectory;

    @Test
    void reportsExactVersionAndUniqueBoundedModulePath() throws Exception {
        Files.writeString(temporaryDirectory.resolve("pyproject.toml"), "[project]\nrequires-python = \"==3.12.*\"\n");
        SourceInspection source = new SourceInspection(2, List.of(Path.of("pyproject.toml"), Path.of("src/demo/__main__.py")), "");

        var facts = new PythonLanguageInspector().inspect(temporaryDirectory, source);

        assertTrue(facts.sourceLanguages().contains(SourceLanguage.PYTHON));
        assertEquals("3.12", facts.values().get(LanguageFact.PYTHON_VERSION));
        assertEquals("demo", facts.values().get(LanguageFact.PYTHON_ENTRYPOINT));
    }
}
