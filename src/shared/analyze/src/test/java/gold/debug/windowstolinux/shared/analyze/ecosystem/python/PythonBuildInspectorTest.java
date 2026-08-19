package gold.debug.windowstolinux.shared.analyze.ecosystem.python;

import gold.debug.windowstolinux.shared.model.project.DeploymentBuildToolType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

        assertEquals(List.of("requirements.lock", "Pipfile.lock", "poetry.lock", "uv.lock"), facts.lockFiles());
    }

    @Test
    void selectsEachNamedDependencyArchitectureFromItsLockfile() throws Exception {
        Map<String, DeploymentBuildToolType> architectures = new LinkedHashMap<>();
        architectures.put("requirements.lock", DeploymentBuildToolType.PIP_LOCKED);
        architectures.put("Pipfile.lock", DeploymentBuildToolType.PIPENV_LOCKED);
        architectures.put("poetry.lock", DeploymentBuildToolType.POETRY_LOCKED);
        architectures.put("uv.lock", DeploymentBuildToolType.UV_LOCKED);

        int index = 0;
        for (Map.Entry<String, DeploymentBuildToolType> architecture : architectures.entrySet()) {
            Path root = Files.createDirectory(temporaryDirectory.resolve("architecture-" + index++));
            Files.writeString(root.resolve("pyproject.toml"), "[project]\nname = \"demo\"\n");
            Files.writeString(root.resolve(architecture.getKey()), "lock");
            PythonBuildFacts facts = new PythonBuildInspector().inspect(root).orElseThrow();
            assertEquals(architecture.getValue(), facts.buildTool());
            assertEquals(List.of(architecture.getKey()), facts.lockFiles());
        }
    }
}
