package gold.debug.windowstolinux.shared.deploy.plan;

import gold.debug.windowstolinux.shared.deploy.contract.MultiComponentDeploymentPlan;

import gold.debug.windowstolinux.shared.model.analysis.DeploymentAdmissionStatus;
import gold.debug.windowstolinux.shared.model.assessment.MultiComponentProjectAssessment;
import gold.debug.windowstolinux.shared.model.health.HealthCheck;
import gold.debug.windowstolinux.shared.model.project.DeploymentBuildToolType;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectFacts;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectType;
import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSpecification;
import gold.debug.windowstolinux.shared.model.project.component.ComponentIsolationSpecification;
import gold.debug.windowstolinux.shared.model.project.component.DeploymentComponent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MultiComponentDeploymentPlannerTest {
    @TempDir Path temporaryDirectory;

    @Test
    void createsDeterministicParallelBuildWavesAndReverseStopRollbackOrder() throws Exception {
        DeploymentComponent database = component("database", 5432, Set.of());
        DeploymentComponent cache = component("cache", 6379, Set.of());
        DeploymentComponent api = component("api", 8080, Set.of("cache", "database"));
        DeploymentComponent web = component("web", 8081, Set.of("api"));
        MultiComponentProjectAssessment assessment = new MultiComponentProjectAssessment(
                DeploymentAdmissionStatus.READY_FOR_PLANNING, "shop", temporaryDirectory,
                List.of(web, api, database, cache), List.of());

        MultiComponentDeploymentPlan plan = new MultiComponentDeploymentPlanner().plan(assessment);

        assertEquals(List.of(List.of("cache", "database"), List.of("api"), List.of("web")), plan.buildWaves());
        assertEquals(List.of("cache", "database", "api", "web"), plan.startOrder());
        assertEquals(List.of("web", "api", "database", "cache"), plan.stopOrder());
        assertEquals(plan.stopOrder(), plan.rollbackOrder());
        assertEquals("shop-api", plan.candidateNamespaces().get("api"));
    }

    private DeploymentComponent component(String id, int port, Set<String> dependencies) throws Exception {
        Path root = Files.createDirectory(temporaryDirectory.resolve(id));
        DeploymentProjectFacts facts = new DeploymentProjectFacts(root, "shop-" + id,
                DeploymentProjectType.STATIC_SITE, DeploymentBuildToolType.STATIC_SITE_BUILD,
                List.of(), List.of(), List.of());
        var runtime = new DeploymentRuntimeSpecification.StaticSite("public",
                new HealthCheck.Http(URI.create("http://127.0.0.1:" + port + "/"), 200, 10));
        return new DeploymentComponent(id, root, facts, Optional.of(runtime), List.of(id + "/public"), Set.of(port),
                List.of("PORT"), List.of(), List.of(), dependencies, true,
                ComponentIsolationSpecification.managed());
    }
}
