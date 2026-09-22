package gold.debug.windowstolinux.shared.standard.deploy.input;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;

import gold.debug.windowstolinux.shared.model.project.application.ApplicationWorkload;
import gold.debug.windowstolinux.shared.standard.analyze.component.ProjectComponentDiscovery;
import gold.debug.windowstolinux.shared.standard.analyze.core.DeploymentAnalysisCoordinator;
import org.junit.jupiter.api.Test;

class ApplicationBundleTest {
    @Test
    void mixedConsoleFixturesAreSingleInstalledApplicationsWithCompanions() throws Exception {
        Path repo = Path.of("").toAbsolutePath();
        while (!Files.isDirectory(repo.resolve("test/multi-language")))
            repo = repo.getParent();
        for (String name : java.util.List.of("success-log-analyzer", "success-directory-diff",
                "success-binary-inspector")) {
            Path source = repo.resolve("test/multi-language/" + name);
            var components = new ProjectComponentDiscovery().discover(source);
            assertEquals(1, components.size(), name);
            var type = components.getFirst().types().getFirst();
            var assessment = new DeploymentAnalysisCoordinator().analyzeForDatabaseReview(source, type);
            assertTrue(assessment.facts().isPresent(), () -> name + ": " + assessment.rejections());
            var facts = assessment.facts().orElseThrow();
            assertTrue(facts.conflicts().isEmpty(), () -> name + ": " + facts.conflicts());
            assertEquals("cli", facts.buildDirectory());
            var resolver = new AutomaticRuntimeResolver();
            var values = resolver.defaults(type, assessment.runtimeSuggestion().orElseThrow(), source);
            assertTrue(resolver.missing("app", type, values).isEmpty(),
                    () -> name + ": " + resolver.missing("app", type, values));
            var runtime = resolver.runtime(type, values);
            assertDoesNotThrow(() -> ApplicationDeclaration.verifyBuildOwnership(facts, runtime));
            var changed = new java.util.HashMap<>(values);
            String companionId = runtime.workload().companions().getFirst().id();
            changed.put("application.companion." + companionId + ".artifactPath", "replaced-tool");
            assertThrows(IllegalArgumentException.class,
                    () -> ApplicationDeclaration.verifyBuildOwnership(facts, resolver.runtime(type, changed)));
            assertEquals(ApplicationWorkload.ExecutionMode.ON_DEMAND, runtime.workload().mode());
            assertEquals(ApplicationWorkload.CategoryType.APP, runtime.workload().category());
            assertEquals(1, runtime.workload().companions().size());
            assertTrue(
                    runtime.healthCheck() instanceof gold.debug.windowstolinux.shared.model.health.HealthCheck.Command);
        }
    }
}
