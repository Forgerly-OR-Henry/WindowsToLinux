package gold.debug.windowstolinux.shared.standard.analyze.ecosystem.python;

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

class PythonLanguageInspectorTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void reportsExactVersionAndUniqueBoundedModulePath() throws Exception {
        Files.writeString(temporaryDirectory.resolve("pyproject.toml"), "[project]\nrequires-python = \"==3.12.*\"\n");
        SourceInspectionFacts source = new SourceInspectionFacts(2,
                List.of(Path.of("pyproject.toml"), Path.of("src/demo/__main__.py")), "");

        var facts = new PythonLanguageInspector().inspect(temporaryDirectory, source);

        assertTrue(facts.sourceLanguages().contains(SourceLanguageType.PYTHON));
        assertEquals("3.12", facts.values().get(LanguageFactKind.PYTHON_VERSION));
        assertEquals("demo", facts.values().get(LanguageFactKind.PYTHON_ENTRYPOINT));
    }
}
