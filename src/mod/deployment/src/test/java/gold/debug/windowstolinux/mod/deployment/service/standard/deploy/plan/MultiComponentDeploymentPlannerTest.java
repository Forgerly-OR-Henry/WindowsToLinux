package gold.debug.windowstolinux.mod.deployment.service.standard.deploy.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import gold.debug.windowstolinux.mod.deployment.service.core.model.DeploymentHealthCheck;
import gold.debug.windowstolinux.mod.deployment.service.core.model.DeploymentProjectKind;
import gold.debug.windowstolinux.mod.deployment.service.core.model.DeploymentRuntimeDefinition;
import gold.debug.windowstolinux.mod.deployment.service.core.model.analysis.DeploymentAdmissionStatus;
import gold.debug.windowstolinux.mod.deployment.service.core.model.assessment.MultiComponentProjectAssessment;
import gold.debug.windowstolinux.mod.deployment.service.core.model.project.DeploymentBuildToolType;
import gold.debug.windowstolinux.mod.deployment.service.core.model.project.DeploymentProjectFacts;
import gold.debug.windowstolinux.mod.deployment.service.core.model.project.component.ComponentIsolationSpecification;
import gold.debug.windowstolinux.mod.deployment.service.core.model.project.component.DeploymentComponent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Keeps the original four-component graph ordering on the new pure model closure. / 在新纯模型闭包上保留原四组件图排序。 */
class MultiComponentDeploymentPlannerTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void createsDeterministicParallelBuildWavesAndReverseStopRollbackOrder() throws Exception {
        var database = component("database", 5432, Set.of());
        var cache = component("cache", 6379, Set.of());
        var api = component("api", 8080, Set.of("cache", "database"));
        var web = component("web", 8081, Set.of("api"));
        var assessment = new MultiComponentProjectAssessment(DeploymentAdmissionStatus.READY_FOR_PLANNING, "shop",
                temporaryDirectory, List.of(web, api, database, cache), List.of());

        var plan = new MultiComponentDeploymentPlanner().plan(assessment);

        assertEquals(List.of(List.of("cache", "database"), List.of("api"), List.of("web")), plan.buildWaves());
        assertEquals(List.of("cache", "database", "api", "web"), plan.startOrder());
        assertEquals(List.of("web", "api", "database", "cache"), plan.stopOrder());
        assertEquals(plan.stopOrder(), plan.rollbackOrder());
        assertEquals("shop-api", plan.candidateNamespaces().get("api"));
    }

    private DeploymentComponent component(String id, int port, Set<String> dependencies) throws Exception {
        Path root = Files.createDirectory(temporaryDirectory.resolve(id));
        var facts = new DeploymentProjectFacts(root, "shop-" + id, DeploymentProjectKind.STATIC_SITE,
                DeploymentBuildToolType.STATIC_SITE_BUILD, List.of(), List.of(), List.of());
        var runtime = new DeploymentRuntimeDefinition.StaticSite("public",
                new DeploymentHealthCheck.Http(URI.create("http://127.0.0.1:" + port + "/"), 200, 10));
        return new DeploymentComponent(id, root, facts, Optional.of(runtime), List.of(id + "/public"), Set.of(port),
                List.of("PORT"), List.of(), List.of(), dependencies, true, ComponentIsolationSpecification.managed());
    }
}
