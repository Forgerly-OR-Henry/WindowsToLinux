package gold.debug.windowstolinux.mod.deployment.service.standard.analyze.workload;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import gold.debug.windowstolinux.mod.deployment.service.core.model.DeploymentProjectKind;
import gold.debug.windowstolinux.mod.deployment.service.core.model.project.DeploymentRuntimeReport;
import gold.debug.windowstolinux.mod.deployment.service.standard.analyze.core.DeploymentAnalysisFlow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StaticWebDeploymentCheckerTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void builtSitesHaveNoNodeDefaultAndExactEnginesCanBeSuggested() throws Exception {
        Files.writeString(temporaryDirectory.resolve("package-lock.json"), "{}");
        Files.writeString(temporaryDirectory.resolve("package.json"),
            "{\"engines\":{\"node\":\">=20\"},\"scripts\":{\"build\":\"vite build\"}}");
        var ranged = new DeploymentAnalysisFlow().analyze(temporaryDirectory, DeploymentProjectKind.STATIC_SITE, (temporaryDirectory).getFileName().toString())
            .runtimeSuggestion().orElseThrow();
        assertTrue(ranged.value(DeploymentRuntimeReport.RuntimeInputType.NODE_MAJOR_VERSION).isEmpty());
        assertTrue(ranged.requiredUserInput().stream()
            .anyMatch(message -> message.key().equals("analysis.deployment.runtime.nodeVersion")));

        Files.writeString(temporaryDirectory.resolve("package.json"),
            "{\"engines\":{\"node\":\"22\"},\"scripts\":{\"build\":\"vite build\"}}");
        var exact = new DeploymentAnalysisFlow().analyze(temporaryDirectory, DeploymentProjectKind.STATIC_SITE, (temporaryDirectory).getFileName().toString())
            .runtimeSuggestion().orElseThrow();
        assertEquals("22", exact.value(DeploymentRuntimeReport.RuntimeInputType.NODE_MAJOR_VERSION).orElseThrow());
    }

    @Test
    void pureStaticSitesNeverRequireNode() throws Exception {
        Files.writeString(temporaryDirectory.resolve("index.html"), "<!doctype html>");
        var suggestion = new DeploymentAnalysisFlow()
            .analyze(temporaryDirectory, DeploymentProjectKind.STATIC_SITE, (temporaryDirectory).getFileName().toString()).runtimeSuggestion().orElseThrow();
        assertTrue(suggestion.requiredUserInput().stream()
            .noneMatch(message -> message.key().equals("analysis.deployment.runtime.nodeVersion")));
    }
}
