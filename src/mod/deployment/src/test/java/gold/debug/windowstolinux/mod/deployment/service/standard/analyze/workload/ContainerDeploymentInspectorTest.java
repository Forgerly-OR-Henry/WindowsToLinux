package gold.debug.windowstolinux.mod.deployment.service.standard.analyze.workload;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import gold.debug.windowstolinux.mod.deployment.service.core.model.DeploymentProjectKind;
import gold.debug.windowstolinux.mod.deployment.service.standard.analyze.core.DeploymentAnalysisCoordinator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ContainerDeploymentInspectorTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void returnsOnlyDeclaredSafePortsAndManagedVolumes() throws Exception {
        Files.writeString(temporaryDirectory.resolve("Dockerfile"),
                "FROM alpine@sha256:" + "a".repeat(64) + "\nEXPOSE 8080 8443/tcp\nVOLUME /var/lib/demo\n");

        var suggestion = new DeploymentAnalysisCoordinator()
                .analyze(temporaryDirectory, DeploymentProjectKind.DOCKERFILE_CONTAINER).runtimeSuggestion()
                .orElseThrow();

        assertEquals(Map.of(8080, 8080, 8443, 8443), suggestion.suggestedContainerPorts());
        assertEquals("/var/lib/demo", suggestion.suggestedManagedVolumes().getFirst().containerPath());
    }

    @Test
    void rejectsMutableExternalBaseImagesButAllowsPinnedAndInternalStages() throws Exception {
        Files.writeString(temporaryDirectory.resolve("Dockerfile"), "FROM alpine:3.20\nEXPOSE 8080\n");
        var rejected = new DeploymentAnalysisCoordinator().analyze(temporaryDirectory,
                DeploymentProjectKind.DOCKERFILE_CONTAINER);
        assertEquals("CONTAINER_BASE_IMAGE_UNPINNED", rejected.rejections().getFirst().code());

        Files.writeString(temporaryDirectory.resolve("Dockerfile"),
                "FROM alpine@sha256:" + "b".repeat(64) + " AS build\nFROM build\nEXPOSE 8080\n");
        assertEquals(DeploymentProjectKind.DOCKERFILE_CONTAINER,
                new DeploymentAnalysisCoordinator()
                        .analyze(temporaryDirectory, DeploymentProjectKind.DOCKERFILE_CONTAINER).facts().orElseThrow()
                        .projectType());
    }
}
