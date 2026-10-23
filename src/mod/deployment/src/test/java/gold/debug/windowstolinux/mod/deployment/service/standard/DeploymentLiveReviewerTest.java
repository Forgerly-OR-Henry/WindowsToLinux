package gold.debug.windowstolinux.mod.deployment.service.standard;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.*;

import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.mod.deployment.service.core.model.project.kind.DeploymentProjectKind;
import gold.debug.windowstolinux.mod.deployment.service.deploy.analyze.part.DiscoveredProjectPart;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.contract.DeploymentStandardInputs;
import org.junit.jupiter.api.Test;

class DeploymentLiveReviewerTest {
    private final ServerId server = new ServerId("fixture-server");

    @Test
    void rootBasedInputsPreserveModelLabelsThroughReviewAndArchiveConfirmation() throws Exception {
        var reviewer = new DeploymentStaticLiveAcceptanceTest().reviewer("generic", "fixture-app",
            Map.of("app", Map.of("timeout", "60")));
        var selected = reviewer.select(server, "revision", List.of(
            new DiscoveredProjectPart("model-label", Path.of("."), List.of(DeploymentProjectKind.RUST_SERVICE))), null);
        assertEquals(TaskResult.SUCCEEDED, selected.outcome());
        assertEquals(Set.of("model-label"), selected.value().orElseThrow().choices().keySet());
        var reviewed = reviewer.review(server, "revision", List.of(new DeploymentStandardInputs.Prompt(
            "model-label", "fixture-app", DeploymentProjectKind.RUST_SERVICE, Map.of("primary", "program"), List.of())), null);
        var answers = reviewed.value().orElseThrow();
        assertEquals("60", answers.get("model-label").values().get("timeout"));
        assertEquals("program", answers.get("model-label").values().get("primary"));
        assertEquals(TaskResult.SUCCEEDED, reviewer.confirm(server, "revision", Map.of("model-label", "a".repeat(64)),
            answers, Map.of(), Optional.empty(), null).outcome());
    }

    @Test
    void renamedComponentsKeepSourceBasedDependenciesAndHealthOwner() throws Exception {
        var reviewer = new DeploymentStaticLiveAcceptanceTest().reviewer("task-board", "fixture-app",
            Map.of("frontend", Map.of("port", "12345"), "backend", Map.of("port", "12346")));
        var selected = reviewer.select(server, "revision", List.of(
            new DiscoveredProjectPart("ui-label", Path.of("frontend/./"), List.of(DeploymentProjectKind.STATIC_SITE)),
            new DiscoveredProjectPart("api-label", Path.of("backend"), List.of(DeploymentProjectKind.SPRING_BOOT))), null);
        assertEquals(TaskResult.SUCCEEDED, selected.outcome());
        var selection = selected.value().orElseThrow();
        assertEquals(Set.of("api-label"), selection.choices().get("ui-label").dependencies());
        assertEquals(Optional.of("ui-label"), selection.healthComponent());
    }

    @Test
    void unreviewedExtraRootIsAKnownRefusalInsteadOfAnAssertionOrUnknown() throws Exception {
        var reviewer = new DeploymentStaticLiveAcceptanceTest().reviewer("generic", "fixture-app",
            Map.of("app", Map.of("timeout", "60")));
        var selected = reviewer.select(server, "revision", List.of(
            new DiscoveredProjectPart("main", Path.of(""), List.of(DeploymentProjectKind.PYTHON_SERVICE)),
            new DiscoveredProjectPart("native", Path.of("native"), List.of(DeploymentProjectKind.CMAKE_SERVICE))), null);
        assertEquals(TaskResult.FAILED, selected.outcome());
        assertTrue(selected.value().isEmpty());
        assertEquals("LIVE_COMPONENT_LAYOUT_UNREVIEWED", selected.problem().orElseThrow().eventCode());
    }
}
