package gold.debug.windowstolinux.shared.analyze.workload.container;

import gold.debug.windowstolinux.shared.analyze.core.DeploymentAnalysisCoordinator;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ContainerDeploymentInspectorTest {
    @TempDir Path temporaryDirectory;

    @Test
    void returnsOnlyDeclaredSafePortsAndManagedVolumes() throws Exception {
        Files.writeString(temporaryDirectory.resolve("Dockerfile"),
                "FROM alpine:3.20\nEXPOSE 8080 8443/tcp\nVOLUME /var/lib/demo\n");

        var suggestion = new DeploymentAnalysisCoordinator().analyze(temporaryDirectory,
                DeploymentProjectType.DOCKERFILE_CONTAINER).runtimeSuggestion().orElseThrow();

        assertEquals(Map.of(8080, 8080, 8443, 8443), suggestion.suggestedContainerPorts());
        assertEquals("/var/lib/demo", suggestion.suggestedManagedVolumes().getFirst().containerPath());
    }
}
