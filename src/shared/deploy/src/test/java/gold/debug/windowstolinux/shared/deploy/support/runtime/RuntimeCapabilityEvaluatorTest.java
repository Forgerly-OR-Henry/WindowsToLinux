package gold.debug.windowstolinux.shared.deploy.support.runtime;

import gold.debug.windowstolinux.shared.model.capability.LinuxCapabilityFacts;
import gold.debug.windowstolinux.shared.model.health.HealthCheck;
import gold.debug.windowstolinux.shared.model.project.DeploymentBuildToolType;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectFacts;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectType;
import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSpecification;
import gold.debug.windowstolinux.shared.model.server.CpuMicroarchitectureLevel;
import gold.debug.windowstolinux.shared.model.server.LinuxDistroType;
import gold.debug.windowstolinux.shared.model.server.security.LinuxFirewallKind;
import gold.debug.windowstolinux.shared.model.server.security.LinuxFirewallState;
import gold.debug.windowstolinux.shared.model.server.security.LinuxSecurityModuleType;
import gold.debug.windowstolinux.shared.model.server.security.LinuxSecurityPosture;
import gold.debug.windowstolinux.shared.model.server.security.LinuxSecurityState;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeCapabilityEvaluatorTest {
    private static final HealthCheck HEALTH = new HealthCheck.Tcp(18080, 10, 1);

    @Test
    void matchesAllEcosystemServiceVersionsWithoutLanguageWrappers() {
        List<RuntimeFixture> fixtures = List.of(
                new RuntimeFixture(new DeploymentRuntimeSpecification.GoService("1.24", "demo", "main.go", HEALTH),
                        DeploymentBuildToolType.GO_MODULE),
                new RuntimeFixture(new DeploymentRuntimeSpecification.RustService("1.89.0", "demo", "src/main.rs", HEALTH),
                        DeploymentBuildToolType.CARGO_LOCKED),
                new RuntimeFixture(new DeploymentRuntimeSpecification.DotNetService("8.0.408", "Demo", "Demo.dll", HEALTH),
                        DeploymentBuildToolType.DOTNET_LOCKED),
                new RuntimeFixture(new DeploymentRuntimeSpecification.KotlinService("21", "demo", "demo.MainKt", HEALTH),
                        DeploymentBuildToolType.GRADLE_KOTLIN_WRAPPER),
                new RuntimeFixture(new DeploymentRuntimeSpecification.PhpService("8.3", "public", "public/index.php", 8080, HEALTH),
                        DeploymentBuildToolType.COMPOSER_LOCKED),
                new RuntimeFixture(new DeploymentRuntimeSpecification.RubyService("3.3.5", "bundle", "config.ru", 8080, HEALTH),
                        DeploymentBuildToolType.BUNDLER_LOCKED)
        );

        for (RuntimeFixture fixture : fixtures) {
            RuntimeCapabilityDecision decision = RuntimeCapabilityEvaluator.evaluate(capabilities(),
                    facts(fixture.runtime().projectType(), fixture.buildTool()), fixture.runtime());
            assertTrue(decision.supported(), fixture.runtime().projectType().name());
        }
    }

    @Test
    void preservesTheSharedMissingVersionRejection() {
        var runtime = new DeploymentRuntimeSpecification.GoService("1.23", "demo", "main.go", HEALTH);

        RuntimeCapabilityDecision decision = RuntimeCapabilityEvaluator.evaluate(
                capabilities(), facts(DeploymentProjectType.GO_SERVICE, DeploymentBuildToolType.GO_MODULE), runtime);

        assertEquals(false, decision.supported());
        assertEquals("the selected ecosystem service runtime version is not available", decision.detail().orElseThrow());
    }

    private static DeploymentProjectFacts facts(DeploymentProjectType type, DeploymentBuildToolType tool) {
        return new DeploymentProjectFacts(Path.of("."), "demo", type, tool, List.of(), List.of(), List.of());
    }

    private static LinuxCapabilityFacts capabilities() {
        return new LinuxCapabilityFacts(LinuxDistroType.UBUNTU, "24.04", "x86_64", "apt", "amd64",
                true, true, true, true, Set.of(21), Set.of(22), true, true, Set.of("3.12"), true,
                Map.of(
                        DeploymentProjectType.GO_SERVICE, Set.of("1.24"),
                        DeploymentProjectType.RUST_SERVICE, Set.of("1.89.0"),
                        DeploymentProjectType.DOTNET_SERVICE, Set.of("8.0.408"),
                        DeploymentProjectType.KOTLIN_SERVICE, Set.of("21"),
                        DeploymentProjectType.PHP_SERVICE, Set.of("8.3"),
                        DeploymentProjectType.RUBY_SERVICE, Set.of("3.3.5")
                ), true, true, CpuMicroarchitectureLevel.X86_64_V1, Set.of("sse4_2", "popcnt"),
                new LinuxSecurityPosture(LinuxSecurityModuleType.APPARMOR, LinuxSecurityState.ENABLED,
                        LinuxFirewallKind.UFW, LinuxFirewallState.ACTIVE), "test evidence");
    }

    private record RuntimeFixture(DeploymentRuntimeSpecification runtime, DeploymentBuildToolType buildTool) {
    }
}
