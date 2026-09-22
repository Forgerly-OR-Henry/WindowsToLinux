package gold.debug.windowstolinux.shared.standard.analyze;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;

import gold.debug.windowstolinux.shared.model.project.DeploymentProjectType;
import gold.debug.windowstolinux.shared.standard.analyze.core.DeploymentAnalysisCoordinator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ApplicationBundleIdentityTest {
    @TempDir
    Path directory;

    @Test
    void identicalBuildDirectoryNamesDoNotMergeDistinctApplications() throws Exception {
        for (String name : new String[]{"first-bundle", "second-bundle"}) {
            Path root = Files.createDirectory(directory.resolve(name));
            Path cli = Files.createDirectory(root.resolve("cli"));
            python(cli);
            Files.writeString(root.resolve("windowstolinux-application.properties"),
                    "version=1\nprojectType=PYTHON_SERVICE\nbuildDirectory=cli\nmode=ON_DEMAND\n");
            var assessed = new DeploymentAnalysisCoordinator().analyze(root, DeploymentProjectType.PYTHON_SERVICE);
            var facts = assessed.facts().orElseThrow(() -> new AssertionError(assessed));
            assertEquals(name, facts.applicationId());
            assertEquals(root.toRealPath(), facts.sourceRoot());
            assertEquals("cli", facts.buildDirectory());
        }
    }

    @Test
    void ordinaryProjectsKeepTheirDeclaredIdentity() throws Exception {
        python(directory);
        var facts = new DeploymentAnalysisCoordinator().analyze(directory, DeploymentProjectType.PYTHON_SERVICE).facts()
                .orElseThrow();
        assertEquals("shared-package", facts.applicationId());
        assertEquals("", facts.buildDirectory());
    }

    private void python(Path root) throws Exception {
        Files.writeString(root.resolve("pyproject.toml"),
                "[project]\nname = \"shared-package\"\nversion = \"1.0.0\"\nrequires-python = \"==3.12.*\"\ndependencies = []\n");
        Files.writeString(root.resolve("main.py"), "print('fixture')\n");
    }
}
