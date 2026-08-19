package gold.debug.windowstolinux.shared.analyze.ecosystem.python;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PythonBuildInspectorTest {
    @TempDir Path temporaryDirectory;

    @Test
    void preservesTheReviewedOrderOfNamedDependencyArchitectures() throws Exception {
        Files.writeString(temporaryDirectory.resolve("pyproject.toml"), "[project]\nname = \"demo\"\n");
        for (String lockFile : List.of("requirements.lock", "poetry.lock", "uv.lock", "Pipfile.lock")) {
            Files.writeString(temporaryDirectory.resolve(lockFile), "lock");
        }

        PythonBuildFacts facts = new PythonBuildInspector().inspect(temporaryDirectory).orElseThrow();

        assertEquals(List.of("requirements.lock", "poetry.lock", "uv.lock", "Pipfile.lock"), facts.lockFiles());
    }
}
