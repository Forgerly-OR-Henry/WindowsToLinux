package gold.debug.windowstolinux.shared.standard.analyze.component;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

import gold.debug.windowstolinux.shared.model.analysis.DeploymentAdmissionStatus;
import gold.debug.windowstolinux.shared.model.assessment.ComponentIssue;
import gold.debug.windowstolinux.shared.model.health.HealthCheck;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectType;
import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSpecification;
import gold.debug.windowstolinux.shared.model.project.component.ComponentDataPath;
import gold.debug.windowstolinux.shared.model.project.component.ComponentIsolationSpecification;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MixedProjectInspectorTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void producesStableNamespacedComponentRecords() throws Exception {
        goProject("database");
        goProject("backend");

        var assessment = new MixedProjectInspector().analyze(temporaryDirectory, "shop", List.of(
                go("backend", 8080, Set.of("database"),
                        List.of(new ComponentDataPath("orders", ComponentDataPath.AccessMode.READ_ONLY, "orders-v1",
                                true))),
                go("database", 5432, Set.of(), List.of(
                        new ComponentDataPath("orders", ComponentDataPath.AccessMode.READ_WRITE, "orders-v1", true)))));

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
        ComponentAnalysisRequest left = go("left", 8080, Set.of("right"),
                List.of(new ComponentDataPath("shared", ComponentDataPath.AccessMode.READ_WRITE, "v1", false)));
        ComponentAnalysisRequest right = new ComponentAnalysisRequest("right", "right",
                DeploymentProjectType.GO_SERVICE, Optional.of(goRuntime("right", 8080)), List.of("left/.w2l/bin"),
                Set.of(8080), List.of("PORT"), List.of(),
                List.of(new ComponentDataPath("shared", ComponentDataPath.AccessMode.READ_WRITE, "v2", false)),
                Set.of("left"), true, new ComponentIsolationSpecification(true, false, false, false));

        var assessment = new MixedProjectInspector().analyze(temporaryDirectory, "unsafe", List.of(left, right));
        Set<String> codes = assessment.issues().stream().map(ComponentIssue::code)
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
        ComponentAnalysisRequest nested = new ComponentAnalysisRequest("nested", "service/nested",
                DeploymentProjectType.GO_SERVICE, Optional.of(goRuntime("nested", 8081)),
                List.of("service/nested/.w2l/bin/nested"), Set.of(8081), List.of(), List.of(), List.of(), Set.of(),
                true, ComponentIsolationSpecification.managed());
        ComponentAnalysisRequest previewRequest = new ComponentAnalysisRequest("scripts", "scripts",
                DeploymentProjectType.RECOGNITION_PREVIEW, Optional.empty(), List.of(), Set.of(), List.of(), List.of(),
                List.of(), Set.of(), true, ComponentIsolationSpecification.managed());

        var assessment = new MixedProjectInspector().analyze(temporaryDirectory, "mixed",
                List.of(go("service", 8080, Set.of(), List.of()), nested, previewRequest));
        Set<String> codes = assessment.issues().stream().map(ComponentIssue::code)
                .collect(java.util.stream.Collectors.toSet());

        assertEquals(DeploymentAdmissionStatus.REJECTED, assessment.admission());
        assertTrue(codes.contains("COMPONENT_ROOT_OVERLAP"));
        assertTrue(codes.contains("REQUIRED_COMPONENT_PREVIEW_ONLY"));
    }

    private ComponentAnalysisRequest go(String id, int port, Set<String> dependencies,
            List<ComponentDataPath> dataPaths) {
        return new ComponentAnalysisRequest(id, id, DeploymentProjectType.GO_SERVICE, Optional.of(goRuntime(id, port)),
                List.of(id + "/.w2l/bin/" + id), Set.of(port), List.of("PORT"), List.of("service-token"), dataPaths,
                dependencies, true, ComponentIsolationSpecification.managed());
    }

    private static DeploymentRuntimeSpecification goRuntime(String artifact, int port) {
        return new DeploymentRuntimeSpecification.GoService("1.24", artifact, "main.go",
                new HealthCheck.Tcp(port, 10, 2));
    }

    private void goProject(String relative) throws Exception {
        Path root = Files.createDirectories(temporaryDirectory.resolve(relative));
        Files.writeString(root.resolve("go.mod"),
                "module example.test/" + relative.replace('/', '-') + "\n\ngo 1.24\n");
        Files.writeString(root.resolve("go.sum"), "example.test/dependency v1.0.0 h1:fixture\n");
        Files.writeString(root.resolve("main.go"), "package main\nfunc main() {}\n");
    }
}
