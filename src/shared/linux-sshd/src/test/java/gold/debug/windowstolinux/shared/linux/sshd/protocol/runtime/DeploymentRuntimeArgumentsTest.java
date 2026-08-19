package gold.debug.windowstolinux.shared.linux.sshd.protocol.runtime;

import gold.debug.windowstolinux.shared.model.health.HealthCheck;
import gold.debug.windowstolinux.shared.model.language.ProjectLanguageFacts;
import gold.debug.windowstolinux.shared.model.language.SourceLanguageType;
import gold.debug.windowstolinux.shared.model.project.DeploymentBuildToolType;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectFacts;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectType;
import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSpecification;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.OptionalInt;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DeploymentRuntimeArgumentsTest {
    private static final HealthCheck.Tcp TCP = new HealthCheck.Tcp(8080, 10, 1);

    @Test
    void preservesExactBuildArchitectureInManagedRuntimeArguments() {
        assertEquals(List.of("javasource", ".w2l/java/app.jar", "demo.Main", "0", "0"), arguments(
                DeploymentProjectType.JAVA_SOURCE, DeploymentBuildToolType.JDK,
                new DeploymentRuntimeSpecification.JavaSource("src", "demo.Main", "21", List.of(), List.of(), TCP)));
        assertEquals(List.of("node", "22", "PNPM"), arguments(DeploymentProjectType.NODE_SERVICE,
                DeploymentBuildToolType.PNPM, new DeploymentRuntimeSpecification.NodeService(22, TCP)));
        assertEquals(List.of("python", "3.12", "demo.main", "UV_LOCKED"), arguments(
                DeploymentProjectType.PYTHON_SERVICE, DeploymentBuildToolType.UV_LOCKED,
                new DeploymentRuntimeSpecification.PythonService("3.12", "demo.main", TCP)));
        assertEquals(List.of("static", "dist", "8080", "YARN"), arguments(DeploymentProjectType.STATIC_SITE,
                DeploymentBuildToolType.YARN, new DeploymentRuntimeSpecification.StaticSite("dist",
                        OptionalInt.of(22), new HealthCheck.Http(URI.create("http://127.0.0.1:8080/"), 200, 10))));
        assertEquals(List.of("kotlin", "21", "demo", "demo.MainKt", "KOTLINC"), arguments(
                DeploymentProjectType.KOTLIN_SERVICE, DeploymentBuildToolType.KOTLINC,
                new DeploymentRuntimeSpecification.KotlinService("2.0.21", "demo", "demo.MainKt", TCP)));
        assertEquals(List.of("phpcli", "8.3", "public", "public/index.php", "8080"), arguments(
                DeploymentProjectType.PHP_SERVICE, DeploymentBuildToolType.PHP_CLI,
                new DeploymentRuntimeSpecification.PhpService("8.3", "public", "public/index.php", 8080, TCP)));
        assertEquals(List.of("rubycli", "3.3.5", "source", "server.rb", "8080"), arguments(
                DeploymentProjectType.RUBY_SERVICE, DeploymentBuildToolType.RUBY_CLI,
                new DeploymentRuntimeSpecification.RubyService("3.3.5", "source", "server.rb", 8080, TCP)));
        assertEquals(List.of("cmake", "w2l-release", "demo", "demo"), arguments(
                DeploymentProjectType.CMAKE_SERVICE, DeploymentBuildToolType.CMAKE,
                new DeploymentRuntimeSpecification.CmakeService("w2l-release", "demo", "demo", TCP)));
    }

    private static List<String> arguments(DeploymentProjectType type, DeploymentBuildToolType tool,
                                          DeploymentRuntimeSpecification runtime) {
        DeploymentProjectFacts facts = type == DeploymentProjectType.CMAKE_SERVICE
                ? new DeploymentProjectFacts(Path.of("."), "demo", type, tool,
                new ProjectLanguageFacts(Set.of(), Set.of(SourceLanguageType.C, SourceLanguageType.CPP),
                        Map.of(), List.of()), List.of(), List.of(), List.of())
                : new DeploymentProjectFacts(Path.of("."), "demo", type, tool,
                List.of(), List.of(), List.of());
        return DeploymentRuntimeArguments.from(facts, runtime);
    }
}
