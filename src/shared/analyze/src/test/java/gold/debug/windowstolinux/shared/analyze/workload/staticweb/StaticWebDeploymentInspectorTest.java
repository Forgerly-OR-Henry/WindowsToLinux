package gold.debug.windowstolinux.shared.analyze.workload.staticweb;

import gold.debug.windowstolinux.shared.analyze.core.DeploymentAnalysisCoordinator;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectType;
import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSuggestion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StaticWebDeploymentInspectorTest {
    @TempDir Path temporaryDirectory;

    @Test
    void builtSitesHaveNoNodeDefaultAndExactEnginesCanBeSuggested() throws Exception {
        Files.writeString(temporaryDirectory.resolve("package-lock.json"), "{}");
        Files.writeString(temporaryDirectory.resolve("package.json"),
                "{\"engines\":{\"node\":\">=20\"},\"scripts\":{\"build\":\"vite build\"}}");
        var ranged = new DeploymentAnalysisCoordinator().analyze(temporaryDirectory, DeploymentProjectType.STATIC_SITE)
                .runtimeSuggestion().orElseThrow();
        assertTrue(ranged.value(DeploymentRuntimeSuggestion.RuntimeInput.NODE_MAJOR_VERSION).isEmpty());
        assertTrue(ranged.requiredUserInput().stream().anyMatch(message ->
                message.key().equals("analysis.deployment.runtime.nodeVersion")));

        Files.writeString(temporaryDirectory.resolve("package.json"),
                "{\"engines\":{\"node\":\"22\"},\"scripts\":{\"build\":\"vite build\"}}");
        var exact = new DeploymentAnalysisCoordinator().analyze(temporaryDirectory, DeploymentProjectType.STATIC_SITE)
                .runtimeSuggestion().orElseThrow();
        assertEquals("22", exact.value(DeploymentRuntimeSuggestion.RuntimeInput.NODE_MAJOR_VERSION).orElseThrow());
    }

    @Test
    void pureStaticSitesNeverRequireNode() throws Exception {
        Files.writeString(temporaryDirectory.resolve("index.html"), "<!doctype html>");
        var suggestion = new DeploymentAnalysisCoordinator().analyze(temporaryDirectory, DeploymentProjectType.STATIC_SITE)
                .runtimeSuggestion().orElseThrow();
        assertTrue(suggestion.requiredUserInput().stream().noneMatch(message ->
                message.key().equals("analysis.deployment.runtime.nodeVersion")));
    }
}
