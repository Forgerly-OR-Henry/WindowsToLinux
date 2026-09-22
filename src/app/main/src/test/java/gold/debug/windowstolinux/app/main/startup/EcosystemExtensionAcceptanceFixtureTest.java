package gold.debug.windowstolinux.app.main.startup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import gold.debug.windowstolinux.app.main.startup.EcosystemExtensionAcceptanceFixture.ArchitectureType;
import gold.debug.windowstolinux.shared.model.analysis.DeploymentAdmissionStatus;
import gold.debug.windowstolinux.shared.standard.analyze.core.DeploymentAnalysisCoordinator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Verifies fixture materialization without running source or connecting to Linux. / 在不运行源码或连接 Linux 的情况下验证夹具实例化。 */
class EcosystemExtensionAcceptanceFixtureTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void materializesHealthyAndRejectedHealthSourcesForEveryArchitecture() throws Exception {
        DeploymentAnalysisCoordinator analyzer = new DeploymentAnalysisCoordinator();
        for (ArchitectureType architecture : ArchitectureType.values()) {
            String runtime = runtimeVersion(architecture, "3.12");
            Path root = EcosystemExtensionAcceptanceFixture.create(temporaryDirectory, architecture,
                    "fixture-" + architecture.key(), runtime, toolVersion(architecture), "fixture-marker", false);
            var assessment = analyzer.analyze(root, architecture.projectType());
            assertEquals(DeploymentAdmissionStatus.READY_FOR_PLANNING, assessment.admission(), assessment.toString());
            assertEquals(architecture.buildTool(), assessment.facts().orElseThrow().buildTool());
            assertEquals("fixture-" + architecture.key(), assessment.facts().orElseThrow().applicationId());
            String content;
            try (Stream<Path> files = Files.walk(root)) {
                content = files.filter(Files::isRegularFile).map(EcosystemExtensionAcceptanceFixtureTest::read)
                        .reduce("", String::concat);
            }
            assertTrue(content.contains("fixture-marker"), architecture.key());
            assertTrue(content.contains("503"), architecture.key());
        }
    }

    @Test
    void materializesEveryPythonArchitectureForTheEnterpriseNineRuntime() throws Exception {
        DeploymentAnalysisCoordinator analyzer = new DeploymentAnalysisCoordinator();
        for (ArchitectureType architecture : new ArchitectureType[]{ArchitectureType.PYTHON_PIP,
                ArchitectureType.PYTHON_PIPENV, ArchitectureType.PYTHON_POETRY, ArchitectureType.PYTHON_UV}) {
            Path root = EcosystemExtensionAcceptanceFixture.create(temporaryDirectory, architecture,
                    "fixture-311-" + architecture.key(), "3.11", toolVersion(architecture), "python-311", true);
            var assessment = analyzer.analyze(root, architecture.projectType());
            assertEquals(DeploymentAdmissionStatus.READY_FOR_PLANNING, assessment.admission(), assessment.toString());
            assertEquals(architecture.buildTool(), assessment.facts().orElseThrow().buildTool());
            assertEquals("fixture-311-" + architecture.key(), assessment.facts().orElseThrow().applicationId());
        }
    }

    private static String runtimeVersion(ArchitectureType architecture, String python) {
        return switch (architecture) {
            case JAVA_JDK -> "21";
            case NODE_NPM, NODE_PNPM, NODE_YARN -> "18";
            case PYTHON_PIP, PYTHON_PIPENV, PYTHON_POETRY, PYTHON_UV -> python;
            case KOTLIN_KOTLINC -> "2.0.21";
            case PHP_CLI -> "8.3";
            case RUBY_CLI -> "3.3";
            case CMAKE -> "";
        };
    }

    private static String toolVersion(ArchitectureType architecture) {
        return switch (architecture) {
            case NODE_PNPM -> "10.15.1";
            case NODE_YARN -> "4.9.2";
            default -> runtimeVersion(architecture, "3.12");
        };
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (java.io.IOException error) {
            throw new java.io.UncheckedIOException(error);
        }
    }
}
