package gold.debug.windowstolinux.shared.linux.sshd.build;

import gold.debug.windowstolinux.shared.linux.transfer.RemoteWorkspace;
import gold.debug.windowstolinux.shared.model.deployment.BuildLimits;
import gold.debug.windowstolinux.shared.model.health.HealthCheck;
import gold.debug.windowstolinux.shared.model.message.LocalizedMessage;
import gold.debug.windowstolinux.shared.model.analysis.AnalysisEvidence;
import gold.debug.windowstolinux.shared.model.analysis.EvidenceConfidence;
import gold.debug.windowstolinux.shared.model.project.DeploymentBuildTool;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectFacts;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectType;
import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSpecification;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeploymentBuildSupportTest {
    private static final String SHA = "a".repeat(64);
    @TempDir Path temporaryDirectory;

    @Test
    void rendersOnlyFixedNodeAndPythonEntrypoints() {
        String node = render(DeploymentProjectType.NODE_SERVICE, DeploymentBuildTool.PNPM,
                new DeploymentRuntimeSpecification.NodeService(22, new HealthCheck.Tcp(8080, 10, 1)));
        String python = render(DeploymentProjectType.PYTHON_SERVICE, DeploymentBuildTool.PYTHON_VENV,
                new DeploymentRuntimeSpecification.PythonService("3.12", "demo.main", new HealthCheck.Tcp(8080, 10, 1)));

        assertTrue(node.contains("pnpm install --frozen-lockfile --ignore-scripts"));
        assertTrue(node.contains("node --version | grep -Eq '^v22\\.'"));
        assertTrue(node.contains("run pnpm run build"));
        assertTrue(!node.contains("run npm run build"));
        assertTrue(python.contains("'python3.12' -m venv ./.venv"));
        assertTrue(python.contains("pip install --disable-pip-version-check --require-hashes -r ./requirements.lock"));
        assertTrue(python.contains("poetry install --only main --sync --no-root"));
        assertTrue(python.contains("uv sync --frozen --no-dev"));
        assertTrue(python.contains("pipenv sync --deploy"));
    }

    @Test
    void rendersAllRemainingTypesWithAnArtifactBoundary() {
        assertTrue(render(DeploymentProjectType.GRADLE_SPRING_BOOT, DeploymentBuildTool.GRADLE_WRAPPER,
                new DeploymentRuntimeSpecification.GradleSpringBoot(new HealthCheck.Tcp(8080, 10, 1)))
                .contains("./gradlew --no-daemon -x test bootJar"));
        assertTrue(render(DeploymentProjectType.JAVA_JAR, DeploymentBuildTool.JAVA,
                new DeploymentRuntimeSpecification.JavaJar("server.jar", "demo.Main", "21", List.of(), List.of(),
                        new HealthCheck.Tcp(8080, 10, 1))).contains("test -f \"$artifact\""));
        assertTrue(render(DeploymentProjectType.STATIC_SITE, DeploymentBuildTool.STATIC_SITE_BUILD,
                new DeploymentRuntimeSpecification.StaticSite("public", new HealthCheck.Http(java.net.URI.create("http://127.0.0.1:8080/"), 200, 10)))
                .contains("test -d './public'"));
        assertTrue(render(DeploymentProjectType.DOCKERFILE_CONTAINER, DeploymentBuildTool.CONTAINER_BUILD,
                new DeploymentRuntimeSpecification.Container(DeploymentRuntimeSpecification.ContainerEngine.PODMAN,
                        Map.of(8080, 8080), List.of(), new HealthCheck.Tcp(8080, 10, 1)))
                .contains("build --pull=false"));
    }

    @Test
    void rejectsATypeAndRuntimeMismatchBeforeRenderingAnyCommand() {
        assertThrows(IllegalArgumentException.class, () -> DeploymentBuildSupport.render(
                facts(DeploymentProjectType.NODE_SERVICE, DeploymentBuildTool.NPM),
                new DeploymentRuntimeSpecification.PythonService("3.12", "demo.main", new HealthCheck.Tcp(8080, 10, 1)),
                new RemoteWorkspace("demo", SHA), BuildLimits.defaultNonRoot()));
    }

    private String render(DeploymentProjectType type, DeploymentBuildTool tool, DeploymentRuntimeSpecification runtime) {
        return DeploymentBuildSupport.render(facts(type, tool), runtime, new RemoteWorkspace("demo", SHA),
                BuildLimits.defaultNonRoot());
    }

    private DeploymentProjectFacts facts(DeploymentProjectType type, DeploymentBuildTool tool) {
        return new DeploymentProjectFacts(temporaryDirectory, "demo", type, tool,
                List.of(new AnalysisEvidence(LocalizedMessage.of("test.evidence"), "fixture",
                        LocalizedMessage.of("test.detected"), EvidenceConfidence.HIGH)), List.of(), List.of());
    }
}
