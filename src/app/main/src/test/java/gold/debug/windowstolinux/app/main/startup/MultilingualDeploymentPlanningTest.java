package gold.debug.windowstolinux.app.main.startup;

import gold.debug.windowstolinux.shared.analyze.component.ProjectComponentDiscovery;
import gold.debug.windowstolinux.shared.analyze.core.DeploymentAnalysisCoordinator;
import gold.debug.windowstolinux.shared.deploy.input.AutomaticRuntimeResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Keeps the real multilingual fixtures connected to automatic discovery and analysis. / 将真实多语言夹具接入自动发现与分析回归。 */
class MultilingualDeploymentPlanningTest {
    static final List<String> PROJECTS = List.of("task-board", "file-transfer", "asset-lending", "csv-inspector",
            "survey-scoring", "log-analyzer", "directory-diff", "binary-inspector");

    @Test
    void everyDiscoveredComponentReachesRuntimeInputReview() throws Exception {
        List<Executable> checks = new ArrayList<>();
        for (String project : PROJECTS) {
            Path root = repositoryRoot().resolve("test/multi-language/success-" + project);
            var components = new ProjectComponentDiscovery().discover(root);
            var expected = switch (project) {
                case "task-board" -> java.util.Set.of("frontend", "backend");
                case "file-transfer", "asset-lending" -> java.util.Set.of("web", "backend");
                case "csv-inspector" -> java.util.Set.of("web", "analyzer");
                case "survey-scoring" -> java.util.Set.of("frontend", "backend", "scorer");
                default -> java.util.Set.of("app");
            };
            assertEquals(expected, components.stream().map(c -> c.id()).collect(java.util.stream.Collectors.toSet()), project);
            for (var component : components) {
                checks.add(() -> {
                    assertEquals(1, component.types().size(), project + "/" + component.id());
                    var type = component.types().getFirst();
                    Path source = root.resolve(component.relativeRoot());
                    var assessment = new DeploymentAnalysisCoordinator().analyzeForDatabaseReview(source, type);
                    System.out.println("POLYGLOT_ANALYSIS " + project + "/" + component.id() + " type=" + type
                            + " admission=" + assessment.admission() + " rejections=" + assessment.rejections());
                    assertTrue(assessment.facts().isPresent(), assessment::toString);
                    var facts = assessment.facts().orElseThrow();
                    assertTrue(facts.conflicts().isEmpty(), facts.conflicts()::toString);
                    assertTrue(facts.missingInformation().stream().allMatch(m -> m.key().equals("analysis.db.reviewRequired")),
                            facts.missingInformation()::toString);
                    var resolver = new AutomaticRuntimeResolver();
                    var values = resolver.defaults(type, assessment.runtimeSuggestion().orElse(null), source);
                    System.out.println("POLYGLOT_INPUTS " + project + "/" + component.id() + " values=" + values
                            + " missing=" + resolver.missing(component.id(), type, values));
                });
            }
        }
        assertAll(checks);
    }

    static Path repositoryRoot() {
        for (Path path = Path.of("").toAbsolutePath(); path != null; path = path.getParent()) {
            if (Files.isRegularFile(path.resolve("test/multi-language/matrix.json"))) return path;
        }
        throw new IllegalStateException("repository root not found");
    }
}
