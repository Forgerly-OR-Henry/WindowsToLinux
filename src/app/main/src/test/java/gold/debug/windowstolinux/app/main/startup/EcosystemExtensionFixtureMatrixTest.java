package gold.debug.windowstolinux.app.main.startup;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import gold.debug.windowstolinux.shared.model.analysis.DeploymentAdmissionStatus;
import gold.debug.windowstolinux.shared.model.language.SourceLanguageType;
import gold.debug.windowstolinux.shared.model.project.DeploymentArchitectureType;
import gold.debug.windowstolinux.shared.model.project.DeploymentBuildToolType;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectType;
import gold.debug.windowstolinux.shared.model.project.DeploymentSupportCatalog;
import gold.debug.windowstolinux.shared.standard.analyze.core.DeploymentAnalysisCoordinator;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/** Checks language/tool coverage and each scenario's actual admission result. / 检查语言工具覆盖及各场景的实际准入结果。 */
class EcosystemExtensionFixtureMatrixTest {
    private static final Set<String> SCENARIOS = Set.of("success-deployment-smoke", "success-json-api",
            "success-runtime-config", "failure-health-rollback", "failure-configuration-rejected");

    @TestFactory
    Stream<DynamicTest> allScenariosHaveTheExpectedAdmissionAndLanguage() throws Exception {
        Path repository = repositoryRoot();
        return fixtures().stream().flatMap(fixture -> SCENARIOS.stream().sorted()
                .map(scenario -> DynamicTest.dynamicTest(fixture.path() + "/" + scenario, () -> {
                    Path root = repository.resolve("test/single-language").resolve(fixture.path()).resolve(scenario);
                    var result = new DeploymentAnalysisCoordinator().analyze(root, fixture.projectType());
                    if (scenario.equals("failure-configuration-rejected")) {
                        assertTrue(Set.of(DeploymentAdmissionStatus.REJECTED, DeploymentAdmissionStatus.REQUIRES_INPUT)
                                .contains(result.admission()), result.toString());
                    } else {
                        assertEquals(DeploymentAdmissionStatus.READY_FOR_PLANNING, result.admission(),
                                result.toString());
                        var facts = result.facts().orElseThrow();
                        assertEquals(fixture.buildTool(), facts.buildTool());
                        assertTrue(facts.languageFacts().sourceLanguages().contains(fixture.language()),
                                () -> fixture.path() + ": " + facts.languageFacts());
                        assertTrue(result.runtimeSuggestion().isPresent());
                    }
                })));
    }

    @Test
    void everySourceBuildArchitectureHasExactlyThreeSuccessAndTwoFailureGroups() throws Exception {
        List<Fixture> fixtures = fixtures();
        assertEquals(26, fixtures.size());
        assertEquals(12, fixtures.stream().map(Fixture::language).distinct().count());
        assertEquals(fixtures.size(), fixtures.stream().map(Fixture::path).distinct().count());
        Set<DeploymentArchitectureType> sourceArchitectures = DeploymentSupportCatalog.deployableArchitectures()
                .stream()
                .filter(architecture -> !Set.of(DeploymentProjectType.JAVA_JAR, DeploymentProjectType.STATIC_SITE,
                        DeploymentProjectType.DOCKERFILE_CONTAINER).contains(architecture.projectType()))
                .collect(Collectors.toSet());
        assertEquals(sourceArchitectures,
                fixtures.stream()
                        .map(fixture -> new DeploymentArchitectureType(fixture.projectType(), fixture.buildTool()))
                        .collect(Collectors.toSet()));
        Set<String> actualPaths = new HashSet<>();
        Path testRoot = repositoryRoot().resolve("test/single-language");
        try (var paths = Files.walk(testRoot, 3)) {
            paths.filter(Files::isDirectory).filter(path -> testRoot.relativize(path).getNameCount() == 3)
                    .forEach(path -> actualPaths.add(testRoot.relativize(path).toString().replace('\\', '/')));
        }
        assertEquals(fixtures.stream().map(Fixture::path).collect(Collectors.toSet()), actualPaths);
        for (Fixture fixture : fixtures) {
            try (var groups = Files.list(testRoot.resolve(fixture.path()))) {
                assertEquals(SCENARIOS, groups.filter(Files::isDirectory).map(path -> path.getFileName().toString())
                        .collect(Collectors.toSet()), fixture.path());
            }
        }
        for (String language : List.of("JAVASCRIPT", "TYPESCRIPT")) {
            assertEquals(
                    Set.of(DeploymentBuildToolType.NPM, DeploymentBuildToolType.PNPM, DeploymentBuildToolType.YARN),
                    fixtures.stream().filter(fixture -> fixture.language().name().equals(language))
                            .map(Fixture::buildTool).collect(Collectors.toSet()));
        }
    }

    private static List<Fixture> fixtures() throws Exception {
        JsonNode entries = new ObjectMapper()
                .readTree(repositoryRoot().resolve("test/single-language/matrix.json").toFile());
        assertTrue(entries.isArray());
        java.util.ArrayList<Fixture> result = new java.util.ArrayList<>();
        for (JsonNode entry : entries) {
            result.add(new Fixture(entry.required("path").asText(),
                    SourceLanguageType.valueOf(entry.required("language").asText()),
                    DeploymentProjectType.valueOf(entry.required("projectType").asText()),
                    DeploymentBuildToolType.valueOf(entry.required("buildTool").asText())));
        }
        return List.copyOf(result);
    }

    @Test
    void allScenariosContainRealSourceModulesAndNativeHeaders() throws Exception {
        Path root = repositoryRoot().resolve("test/single-language");
        for (Fixture fixture : fixtures()) {
            String extension = switch (fixture.language()) {
                case C -> ".c";
                case CPP -> ".cpp";
                case CSHARP -> ".cs";
                case GO -> ".go";
                case JAVA -> ".java";
                case KOTLIN -> ".kt";
                case JAVASCRIPT -> ".js";
                case TYPESCRIPT -> ".ts";
                case PHP -> ".php";
                case PYTHON -> ".py";
                case RUBY -> ".rb";
                case RUST -> ".rs";
                default -> throw new AssertionError(fixture.language());
            };
            for (String scenario : SCENARIOS) {
                Path project = root.resolve(fixture.path()).resolve(scenario);
                List<Path> files;
                try (var paths = Files.walk(project)) {
                    files = paths.filter(Files::isRegularFile).toList();
                }
                var implementations = files.stream().filter(path -> path.toString().endsWith(extension))
                        .filter(path -> !Set.of("build.js", "__init__.py").contains(path.getFileName().toString()))
                        .filter(path -> !path.toString().replace('\\', '/').contains("/src/test/"))
                        .filter(path -> !path.getFileName().toString().endsWith(".d.ts")).toList();
                assertTrue(implementations.size() >= 4, fixture.path() + "/" + scenario);
                for (Path implementation : implementations)
                    assertFalse(Files.readString(implementation).isBlank(), implementation.toString());
                if (fixture.language() == SourceLanguageType.C || fixture.language() == SourceLanguageType.CPP) {
                    assertTrue(
                            files.stream()
                                    .anyMatch(path -> path.toString()
                                            .endsWith(fixture.language() == SourceLanguageType.C ? ".h" : ".hpp")),
                            project.toString());
                    String cmake = Files.readString(project.resolve("CMakeLists.txt"));
                    for (Path implementation : implementations)
                        assertTrue(cmake.contains(project.relativize(implementation).toString().replace('\\', '/')));
                }
            }
        }
    }

    private static Path repositoryRoot() {
        for (Path current = Path.of("").toAbsolutePath().normalize(); current != null; current = current.getParent()) {
            if (Files.isRegularFile(current.resolve("test/single-language/matrix.json"))
                    && Files.isRegularFile(current.resolve("pom.xml")))
                return current;
        }
        throw new IllegalStateException("WindowsToLinux repository root was not found");
    }

    private record Fixture(String path, SourceLanguageType language, DeploymentProjectType projectType,
            DeploymentBuildToolType buildTool) {
    }
}
