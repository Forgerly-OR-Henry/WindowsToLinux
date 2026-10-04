package gold.debug.windowstolinux.mod.deployment.service.standard.analyze.component;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

import gold.debug.windowstolinux.mod.deployment.service.core.model.DeploymentHealthCheck;
import gold.debug.windowstolinux.mod.deployment.service.core.model.DeploymentProjectKind;
import gold.debug.windowstolinux.mod.deployment.service.core.model.DeploymentRuntimeDefinition;
import gold.debug.windowstolinux.mod.deployment.service.core.model.DeploymentStoragePath;
import gold.debug.windowstolinux.mod.deployment.service.core.model.analysis.DeploymentAdmissionStatus;
import gold.debug.windowstolinux.mod.deployment.service.core.model.assessment.PartIssue;
import gold.debug.windowstolinux.mod.deployment.service.core.model.project.component.PartIsolationSpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MixedProjectCheckerTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void producesStableNamespacedComponentRecords() throws Exception {
        goProject("database");
        goProject("backend");

        var assessment = new MixedProjectChecker().analyze(temporaryDirectory, "shop", List.of(
            go("backend", 8080, Set.of("database"),
                List.of(new DeploymentStoragePath("orders", DeploymentStoragePath.AccessMode.READ_ONLY,
                    "orders-v1", true))),
            go("database", 5432, Set.of(), List.of(new DeploymentStoragePath("orders",
                DeploymentStoragePath.AccessMode.READ_WRITE, "orders-v1", true)))), (temporaryDirectory).getFileName().toString());

        assertEquals(DeploymentAdmissionStatus.READY_FOR_PLANNING, assessment.admission());
        assertEquals(List.of("backend", "database"),
            assessment.components().stream().map(component -> component.componentId()).toList());
        assertEquals(Set.of("database"), assessment.components().getFirst().dependencies());
        assertEquals("shop-backend", assessment.components().getFirst().facts().applicationId());
        assertTrue(assessment.issues().isEmpty());
    }

    @Test
    void rejectsCrossComponentConflictsBeforePlanning() throws Exception {
        goProject("left");
        goProject("right");
        PartAnalysisRequest left = go("left", 8080, Set.of("right"),
            List.of(new DeploymentStoragePath("shared", DeploymentStoragePath.AccessMode.READ_WRITE, "v1", false)));
        PartAnalysisRequest right = new PartAnalysisRequest("right", "right",
            DeploymentProjectKind.GO_SERVICE, Optional.of(goRuntime("right", 8080)), List.of("left/.w2l/bin"),
            Set.of(8080), List.of("PORT"), List.of(),
            List.of(new DeploymentStoragePath("shared", DeploymentStoragePath.AccessMode.READ_WRITE, "v2", false)),
            Set.of("left"), true, new PartIsolationSpec(true, false, false, false));

        var assessment = new MixedProjectChecker().analyze(temporaryDirectory, "unsafe", List.of(left, right), (temporaryDirectory).getFileName().toString());
        Set<String> codes = assessment.issues().stream().map(PartIssue::code)
            .collect(java.util.stream.Collectors.toSet());

        assertEquals(DeploymentAdmissionStatus.REJECTED, assessment.admission());
        assertTrue(codes.contains("COMPONENT_ARTIFACT_OVERLAP"));
        assertTrue(codes.contains("COMPONENT_PORT_CONFLICT"));
        assertTrue(codes.contains("SHARED_DATA_SCHEMA_CONFLICT"));
        assertTrue(codes.contains("SHARED_DATA_MULTIPLE_WRITERS"));
        assertTrue(codes.contains("COMPONENT_DEPENDENCY_CYCLE"));
        assertTrue(codes.contains("ARBITRARY_SHELL_REQUIRED"));
    }

    @Test
    void rejectsOverlappingRootsAndRequiredPreviewComponents() throws Exception {
        goProject("service");
        goProject("service/nested");
        Path preview = Files.createDirectories(temporaryDirectory.resolve("scripts"));
        Files.writeString(preview.resolve("deploy.sh"), "#!/bin/sh\n");
        PartAnalysisRequest nested = new PartAnalysisRequest("nested", "service/nested",
            DeploymentProjectKind.GO_SERVICE, Optional.of(goRuntime("nested", 8081)),
            List.of("service/nested/.w2l/bin/nested"), Set.of(8081), List.of(), List.of(), List.of(), Set.of(),
            true, PartIsolationSpec.managed());
        PartAnalysisRequest previewRequest = new PartAnalysisRequest("scripts", "scripts",
            DeploymentProjectKind.RECOGNITION_PREVIEW, Optional.empty(), List.of(), Set.of(), List.of(), List.of(),
            List.of(), Set.of(), true, PartIsolationSpec.managed());

        var assessment = new MixedProjectChecker().analyze(temporaryDirectory, "mixed",
            List.of(go("service", 8080, Set.of(), List.of()), nested, previewRequest), (temporaryDirectory).getFileName().toString());
        Set<String> codes = assessment.issues().stream().map(PartIssue::code)
            .collect(java.util.stream.Collectors.toSet());

        assertEquals(DeploymentAdmissionStatus.REJECTED, assessment.admission());
        assertTrue(codes.contains("COMPONENT_ROOT_OVERLAP"));
        assertTrue(codes.contains("REQUIRED_COMPONENT_PREVIEW_ONLY"));
    }

    private PartAnalysisRequest go(String id, int port, Set<String> dependencies,
                                   List<DeploymentStoragePath> dataPaths) {
        return new PartAnalysisRequest(id, id, DeploymentProjectKind.GO_SERVICE, Optional.of(goRuntime(id, port)),
            List.of(id + "/.w2l/bin/" + id), Set.of(port), List.of("PORT"), List.of("service-token"), dataPaths,
            dependencies, true, PartIsolationSpec.managed());
    }

    private static DeploymentRuntimeDefinition goRuntime(String artifact, int port) {
        return new DeploymentRuntimeDefinition.GoService("1.24", artifact, "main.go",
            new DeploymentHealthCheck.Tcp(port, 10, 2));
    }

    private void goProject(String relative) throws Exception {
        Path root = Files.createDirectories(temporaryDirectory.resolve(relative));
        Files.writeString(root.resolve("go.mod"),
            "module example.test/" + relative.replace('/', '-') + "\n\ngo 1.24\n");
        Files.writeString(root.resolve("go.sum"), "example.test/dependency v1.0.0 h1:fixture\n");
        Files.writeString(root.resolve("main.go"), "package main\nfunc main() {}\n");
    }
}
