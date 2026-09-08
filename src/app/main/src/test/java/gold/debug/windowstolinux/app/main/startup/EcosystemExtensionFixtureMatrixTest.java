package gold.debug.windowstolinux.app.main.startup;

import gold.debug.windowstolinux.shared.analyze.core.DeploymentAnalysisCoordinator;
import gold.debug.windowstolinux.shared.model.analysis.DeploymentAdmissionStatus;
import gold.debug.windowstolinux.shared.model.project.DeploymentBuildToolType;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectType;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Audits one independent checked-in fixture for every changed Phase Three architecture. / 审计每个三期变更架构的独立入库夹具。 */
class EcosystemExtensionFixtureMatrixTest {
    @Test
    void everyChangedArchitectureHasAPlanningReadyIndependentFixture() {
        DeploymentAnalysisCoordinator analyzer = new DeploymentAnalysisCoordinator();
        Path repository = repositoryRoot();
        List<Fixture> fixtures = List.of(
                fixture("java/jdk", DeploymentProjectType.JAVA_SOURCE, DeploymentBuildToolType.JDK),
                fixture("node/npm", DeploymentProjectType.NODE_SERVICE, DeploymentBuildToolType.NPM),
                fixture("node/pnpm", DeploymentProjectType.NODE_SERVICE, DeploymentBuildToolType.PNPM),
                fixture("node/yarn", DeploymentProjectType.NODE_SERVICE, DeploymentBuildToolType.YARN),
                fixture("python/pip", DeploymentProjectType.PYTHON_SERVICE, DeploymentBuildToolType.PIP_LOCKED),
                fixture("python/pipenv", DeploymentProjectType.PYTHON_SERVICE, DeploymentBuildToolType.PIPENV_LOCKED),
                fixture("python/poetry", DeploymentProjectType.PYTHON_SERVICE, DeploymentBuildToolType.POETRY_LOCKED),
                fixture("python/uv", DeploymentProjectType.PYTHON_SERVICE, DeploymentBuildToolType.UV_LOCKED),
                fixture("kotlin/kotlinc", DeploymentProjectType.KOTLIN_SERVICE, DeploymentBuildToolType.KOTLINC),
                fixture("php/phpcli", DeploymentProjectType.PHP_SERVICE, DeploymentBuildToolType.PHP_CLI),
                fixture("ruby/rubycli", DeploymentProjectType.RUBY_SERVICE, DeploymentBuildToolType.RUBY_CLI),
                fixture("c/cmake", DeploymentProjectType.CMAKE_SERVICE, DeploymentBuildToolType.CMAKE),
                new Fixture(Path.of("test/c/cmake/cpp-service/phase3-ready"), DeploymentProjectType.CMAKE_SERVICE,
                        DeploymentBuildToolType.CMAKE),
                new Fixture(Path.of("test/node/npm/typescript-service/phase3-ready"), DeploymentProjectType.NODE_SERVICE,
                        DeploymentBuildToolType.NPM));

        assertEquals(fixtures.size(), fixtures.stream().map(Fixture::relativePath).distinct().count());
        for (Fixture fixture : fixtures) {
            Path root = repository.resolve(fixture.relativePath());
            assertTrue(Files.isDirectory(root), () -> "missing architecture fixture: " + root);
            var assessment = analyzer.analyze(root, fixture.projectType());
            assertEquals(DeploymentAdmissionStatus.READY_FOR_PLANNING, assessment.admission(),
                    () -> fixture.relativePath() + ": " + assessment);
            assertEquals(fixture.buildTool(), assessment.facts().orElseThrow().buildTool(),
                    fixture.relativePath().toString());
            assertTrue(assessment.runtimeSuggestion().isPresent(), fixture.relativePath().toString());
            if (fixture.relativePath().toString().contains("cpp-service")) {
                assertTrue(assessment.facts().orElseThrow().languageFacts().sourceLanguages()
                        .contains(gold.debug.windowstolinux.shared.model.language.SourceLanguageType.CPP));
            }
            if (fixture.relativePath().toString().contains("typescript-service")) {
                assertTrue(assessment.facts().orElseThrow().languageFacts().sourceLanguages()
                        .contains(gold.debug.windowstolinux.shared.model.language.SourceLanguageType.TYPESCRIPT));
            }
        }
    }

    private static Fixture fixture(String architecture, DeploymentProjectType projectType,
                                   DeploymentBuildToolType buildTool) {
        return new Fixture(Path.of("test", architecture, "http-service", "phase3-ready"), projectType, buildTool);
    }

    private static Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("pom.xml"))
                    && Files.isRegularFile(current.resolve("docs/development/PHASE-3-SUPPLEMENT-ECOSYSTEM.md"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("WindowsToLinux repository root was not found");
    }

    private record Fixture(Path relativePath, DeploymentProjectType projectType, DeploymentBuildToolType buildTool) { }
}
