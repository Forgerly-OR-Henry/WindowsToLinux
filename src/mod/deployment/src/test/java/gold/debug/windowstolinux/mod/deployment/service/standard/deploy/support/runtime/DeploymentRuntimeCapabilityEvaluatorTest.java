package gold.debug.windowstolinux.mod.deployment.service.standard.deploy.support.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import gold.debug.windowstolinux.mod.deployment.service.core.model.*;
import gold.debug.windowstolinux.mod.deployment.service.core.model.DeploymentHealthCheck;
import gold.debug.windowstolinux.mod.deployment.service.core.model.DeploymentProjectKind;
import gold.debug.windowstolinux.mod.deployment.service.core.model.DeploymentRuntimeDefinition;
import gold.debug.windowstolinux.mod.deployment.service.core.model.capability.DeploymentEcosystemToolType;
import gold.debug.windowstolinux.mod.deployment.service.core.model.capability.DeploymentLinuxCapabilityFacts;
import gold.debug.windowstolinux.mod.deployment.service.core.model.language.ProjectLanguageFacts;
import gold.debug.windowstolinux.mod.deployment.service.core.model.language.SourceLanguageType;
import gold.debug.windowstolinux.mod.deployment.service.core.model.project.DeploymentBuildToolType;
import gold.debug.windowstolinux.mod.deployment.service.core.model.project.DeploymentProjectFacts;
import gold.debug.windowstolinux.mod.deployment.service.core.model.server.DeploymentCpuMicroarchitectureLevel;
import gold.debug.windowstolinux.mod.deployment.service.core.model.server.LinuxDistroType;
import gold.debug.windowstolinux.mod.deployment.service.core.model.server.security.DeploymentLinuxFirewallKind;
import gold.debug.windowstolinux.mod.deployment.service.core.model.server.security.DeploymentLinuxFirewallState;
import gold.debug.windowstolinux.mod.deployment.service.core.model.server.security.DeploymentLinuxSecurityModuleType;
import gold.debug.windowstolinux.mod.deployment.service.core.model.server.security.DeploymentLinuxSecurityPosture;
import gold.debug.windowstolinux.mod.deployment.service.core.model.server.security.DeploymentLinuxSecurityState;
import gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.*;
import org.junit.jupiter.api.Test;

class DeploymentRuntimeCapabilityEvaluatorTest {
    private static final DeploymentHealthCheck HEALTH = new DeploymentHealthCheck.Tcp(18080, 10, 1);

    @Test
    void matchesEveryExactEcosystemArchitecture() {
        List<RuntimeFixture> fixtures = List.of(
                new RuntimeFixture(new DeploymentRuntimeDefinition.JavaSource("src", "demo.Main", "21", List.of(),
                        List.of(), HEALTH), DeploymentBuildToolType.JDK),
                new RuntimeFixture(new DeploymentRuntimeDefinition.NodeService(22, HEALTH),
                        DeploymentBuildToolType.NPM),
                new RuntimeFixture(new DeploymentRuntimeDefinition.NodeService(22, HEALTH),
                        DeploymentBuildToolType.PNPM),
                new RuntimeFixture(new DeploymentRuntimeDefinition.NodeService(22, HEALTH),
                        DeploymentBuildToolType.YARN),
                new RuntimeFixture(new DeploymentRuntimeDefinition.PythonService("3.12", "demo.main", HEALTH),
                        DeploymentBuildToolType.PIP_LOCKED),
                new RuntimeFixture(new DeploymentRuntimeDefinition.PythonService("3.12", "demo.main", HEALTH),
                        DeploymentBuildToolType.PIPENV_LOCKED),
                new RuntimeFixture(new DeploymentRuntimeDefinition.PythonService("3.12", "demo.main", HEALTH),
                        DeploymentBuildToolType.POETRY_LOCKED),
                new RuntimeFixture(new DeploymentRuntimeDefinition.PythonService("3.12", "demo.main", HEALTH),
                        DeploymentBuildToolType.UV_LOCKED),
                new RuntimeFixture(new DeploymentRuntimeDefinition.GoService("1.24", "demo", "main.go", HEALTH),
                        DeploymentBuildToolType.GO_MODULE),
                new RuntimeFixture(new DeploymentRuntimeDefinition.RustService("1.89.0", "demo", "src/main.rs", HEALTH),
                        DeploymentBuildToolType.CARGO_LOCKED),
                new RuntimeFixture(new DeploymentRuntimeDefinition.DotNetService("8.0.408", "Demo", "Demo.dll", HEALTH),
                        DeploymentBuildToolType.DOTNET_LOCKED),
                new RuntimeFixture(
                        new DeploymentRuntimeDefinition.KotlinService("2.0.21", "demo", "demo.MainKt", HEALTH),
                        DeploymentBuildToolType.GRADLE_KOTLIN_WRAPPER),
                new RuntimeFixture(
                        new DeploymentRuntimeDefinition.KotlinService("2.0.21", "demo", "demo.MainKt", HEALTH),
                        DeploymentBuildToolType.KOTLINC),
                new RuntimeFixture(
                        new DeploymentRuntimeDefinition.PhpService("8.3", "public", "public/index.php", 8080, HEALTH),
                        DeploymentBuildToolType.COMPOSER_LOCKED),
                new RuntimeFixture(
                        new DeploymentRuntimeDefinition.PhpService("8.3", "public", "public/index.php", 8080, HEALTH),
                        DeploymentBuildToolType.PHP_CLI),
                new RuntimeFixture(
                        new DeploymentRuntimeDefinition.RubyService("3.3.5", "bundle", "config.ru", 8080, HEALTH),
                        DeploymentBuildToolType.BUNDLER_LOCKED),
                new RuntimeFixture(
                        new DeploymentRuntimeDefinition.RubyService("3.3.5", "source", "server.rb", 8080, HEALTH),
                        DeploymentBuildToolType.RUBY_CLI),
                new RuntimeFixture(new DeploymentRuntimeDefinition.CmakeService("w2l-release", "demo", "demo", HEALTH),
                        DeploymentBuildToolType.CMAKE));

        for (RuntimeFixture fixture : fixtures) {
            DeploymentRuntimeCapabilityDecision decision = DeploymentRuntimeCapabilityEvaluator.evaluate(capabilities(),
                    facts(fixture.runtime().projectType(), fixture.buildTool()), fixture.runtime());
            assertTrue(decision.supported(), fixture.runtime().projectType().name());
        }
    }

    @Test
    void preservesTheSharedMissingVersionRejection() {
        var runtime = new DeploymentRuntimeDefinition.GoService("1.23", "demo", "main.go", HEALTH);

        DeploymentRuntimeCapabilityDecision decision = DeploymentRuntimeCapabilityEvaluator.evaluate(capabilities(),
                facts(DeploymentProjectKind.GO_SERVICE, DeploymentBuildToolType.GO_MODULE), runtime);

        assertEquals(false, decision.supported());
        assertEquals("the selected ecosystem service runtime version is not available",
                decision.detail().orElseThrow());
    }

    @Test
    void rejectsPackageManagerVersionsThatCannotRunTheFixedCommands() {
        DeploymentLinuxCapabilityFacts base = capabilities();
        Map<DeploymentEcosystemToolType, Set<String>> tools = new java.util.EnumMap<>(base.ecosystemToolVersions());
        tools.put(DeploymentEcosystemToolType.PNPM, Set.of("7.33.7"));
        tools.put(DeploymentEcosystemToolType.YARN, Set.of("1.22.22"));
        tools.put(DeploymentEcosystemToolType.POETRY, Set.of("1.1.15"));
        tools.put(DeploymentEcosystemToolType.UV, Set.of("0.3.5"));
        DeploymentLinuxCapabilityFacts incompatible = withTools(base, tools);

        for (RuntimeFixture fixture : List.of(
                new RuntimeFixture(new DeploymentRuntimeDefinition.NodeService(22, HEALTH),
                        DeploymentBuildToolType.PNPM),
                new RuntimeFixture(new DeploymentRuntimeDefinition.NodeService(22, HEALTH),
                        DeploymentBuildToolType.YARN),
                new RuntimeFixture(new DeploymentRuntimeDefinition.PythonService("3.12", "demo.main", HEALTH),
                        DeploymentBuildToolType.POETRY_LOCKED),
                new RuntimeFixture(new DeploymentRuntimeDefinition.PythonService("3.12", "demo.main", HEALTH),
                        DeploymentBuildToolType.UV_LOCKED))) {
            assertEquals(false,
                    DeploymentRuntimeCapabilityEvaluator.evaluate(incompatible,
                            facts(fixture.runtime().projectType(), fixture.buildTool()), fixture.runtime())
                            .supported());
        }
    }

    @Test
    void rejectsCmakeWhenTheFixedToolchainIsUnavailable() {
        DeploymentLinuxCapabilityFacts base = capabilities();
        Map<DeploymentEcosystemToolType, Set<String>> tools = new java.util.EnumMap<>(base.ecosystemToolVersions());
        tools.remove(DeploymentEcosystemToolType.NINJA);
        var runtime = new DeploymentRuntimeDefinition.CmakeService("w2l-release", "demo", "demo", HEALTH);

        DeploymentRuntimeCapabilityDecision decision = DeploymentRuntimeCapabilityEvaluator.evaluate(
                withTools(base, tools), facts(DeploymentProjectKind.CMAKE_SERVICE, DeploymentBuildToolType.CMAKE),
                runtime);

        assertEquals(false, decision.supported());
        assertTrue(decision.detail().orElseThrow().contains("Ninja"));

        tools.put(DeploymentEcosystemToolType.NINJA, Set.of("1.12.1"));
        tools.put(DeploymentEcosystemToolType.CMAKE, Set.of("3.24.4"));
        DeploymentRuntimeCapabilityDecision obsoleteCmake = DeploymentRuntimeCapabilityEvaluator.evaluate(
                withTools(base, tools), facts(DeploymentProjectKind.CMAKE_SERVICE, DeploymentBuildToolType.CMAKE),
                runtime);
        assertEquals(false, obsoleteCmake.supported());
        assertTrue(obsoleteCmake.detail().orElseThrow().contains("CMake"));
    }

    private static DeploymentProjectFacts facts(DeploymentProjectKind type, DeploymentBuildToolType tool) {
        if (type == DeploymentProjectKind.CMAKE_SERVICE) {
            return new DeploymentProjectFacts(
                    Path.of("."), "demo", type, tool, new ProjectLanguageFacts(Set.of(),
                            Set.of(SourceLanguageType.C, SourceLanguageType.CPP), Map.of(), List.of()),
                    List.of(), List.of(), List.of());
        }
        return new DeploymentProjectFacts(Path.of("."), "demo", type, tool, List.of(), List.of(), List.of());
    }

    @Test
    void mixedApplicationsUseHostCompilersAlongsidePreparedLanguageTools() {
        for (var ecosystem : List.of(DeploymentToolchainType.C, DeploymentToolchainType.CPP)) {
            var rust = ToolchainRequirement.declared(DeploymentToolchainType.RUST, "1.89.0", "Cargo.toml",
                    ToolchainRequirement.PurposeType.BUILD);
            var nativeTool = ToolchainRequirement.declared(ecosystem, "17", "native/CMakeLists.txt",
                    ToolchainRequirement.PurposeType.LANGUAGE_TARGET);
            var project = facts(DeploymentProjectKind.RUST_SERVICE, DeploymentBuildToolType.CARGO_LOCKED)
                    .withToolchains(List.of(rust, nativeTool));
            var workload = new DeploymentWorkload(DeploymentWorkload.ExecutionMode.ON_DEMAND, true,
                    DeploymentCommand.primary(), "", List.of(),
                    Optional.of(new DeploymentCommand("", List.of("--help"))), "usage", Optional.empty(), List.of(),
                    List.of(new DeploymentCompanion("worker", "native", DeploymentCompanion.BuildType.CMAKE_SERVICE,
                            ".w2l/bin/worker", "NATIVE_HELPER")));
            var runtime = new DeploymentRuntimeDefinition.RustService("1.89.0", "demo", "src/main.rs", HEALTH)
                    .withWorkload(workload);
            var prepared = new DeploymentResolvedToolchains(DeploymentToolchainCatalog.defaults().revision(),
                    List.of(new DeploymentResolvedToolchains.Selection(rust,
                            DeploymentToolchainVersion.parse(DeploymentToolchainType.RUST, "1.89.0").orElseThrow(),
                            "/opt/rust", DeploymentResolvedToolchains.OriginType.MANAGED, "fixture", "a".repeat(64))));
            assertTrue(DeploymentRuntimeCapabilityEvaluator.evaluate(capabilities(), project, runtime, prepared)
                    .supported());
            var tools = new java.util.EnumMap<>(capabilities().ecosystemToolVersions());
            tools.remove(ecosystem == DeploymentToolchainType.C
                    ? DeploymentEcosystemToolType.C_COMPILER
                    : DeploymentEcosystemToolType.CPP_COMPILER);
            assertEquals(false, DeploymentRuntimeCapabilityEvaluator
                    .evaluate(withTools(capabilities(), tools), project, runtime, prepared).supported());
        }
    }

    private static DeploymentLinuxCapabilityFacts capabilities() {
        return new DeploymentLinuxCapabilityFacts(LinuxDistroType.UBUNTU, "24.04", "x86_64", "apt", "amd64", true, true,
                true, true, Set.of(21), Set.of(22), true, true, Set.of("3.12"), true,
                Map.of(DeploymentProjectKind.GO_SERVICE, Set.of("1.24"), DeploymentProjectKind.RUST_SERVICE,
                        Set.of("1.89.0"), DeploymentProjectKind.DOTNET_SERVICE, Set.of("8.0.408"),
                        DeploymentProjectKind.KOTLIN_SERVICE, Set.of("21"), DeploymentProjectKind.PHP_SERVICE,
                        Set.of("8.3"), DeploymentProjectKind.RUBY_SERVICE, Set.of("3.3.5")),
                Map.ofEntries(Map.entry(DeploymentEcosystemToolType.JAVAC, Set.of("21.0.8")),
                        Map.entry(DeploymentEcosystemToolType.JAR, Set.of("21.0.8")),
                        Map.entry(DeploymentEcosystemToolType.NPM, Set.of("10.9.2")),
                        Map.entry(DeploymentEcosystemToolType.PNPM, Set.of("10.15.1")),
                        Map.entry(DeploymentEcosystemToolType.YARN, Set.of("4.9.2")),
                        Map.entry(DeploymentEcosystemToolType.PIP, Set.of("24.0")),
                        Map.entry(DeploymentEcosystemToolType.PIPENV, Set.of("2025.0.4")),
                        Map.entry(DeploymentEcosystemToolType.POETRY, Set.of("2.1.3")),
                        Map.entry(DeploymentEcosystemToolType.UV, Set.of("0.8.12")),
                        Map.entry(DeploymentEcosystemToolType.KOTLINC, Set.of("2.0.21")),
                        Map.entry(DeploymentEcosystemToolType.COMPOSER, Set.of("2.8.10")),
                        Map.entry(DeploymentEcosystemToolType.BUNDLER, Set.of("2.6.9")),
                        Map.entry(DeploymentEcosystemToolType.CMAKE, Set.of("3.31.6")),
                        Map.entry(DeploymentEcosystemToolType.NINJA, Set.of("1.12.1")),
                        Map.entry(DeploymentEcosystemToolType.C_COMPILER, Set.of("14.2.1")),
                        Map.entry(DeploymentEcosystemToolType.CPP_COMPILER, Set.of("14.2.1"))),
                true, true, DeploymentCpuMicroarchitectureLevel.X86_64_V1, Set.of("sse4_2", "popcnt"),
                new DeploymentLinuxSecurityPosture(DeploymentLinuxSecurityModuleType.APPARMOR,
                        DeploymentLinuxSecurityState.ENABLED, DeploymentLinuxFirewallKind.UFW,
                        DeploymentLinuxFirewallState.ACTIVE),
                "test evidence");
    }

    private static DeploymentLinuxCapabilityFacts withTools(DeploymentLinuxCapabilityFacts base,
            Map<DeploymentEcosystemToolType, Set<String>> tools) {
        return new DeploymentLinuxCapabilityFacts(base.distro(), base.version(), base.architecture(),
                base.packageManager(), base.packageArchitecture(), base.systemdAvailable(), base.dockerAvailable(),
                base.podmanAvailable(), base.podmanQuadletAvailable(), base.javaMajorVersions(),
                base.nodeMajorVersions(), base.npmAvailable(), base.mavenAvailable(), base.pythonVersions(),
                base.python3Available(), base.serviceRuntimeVersions(), tools, base.dockerOperational(),
                base.podmanOperational(), base.cpuMicroarchitecture(), base.cpuFlags(), base.securityPosture(),
                base.evidence());
    }

    private record RuntimeFixture(DeploymentRuntimeDefinition runtime, DeploymentBuildToolType buildTool) {
    }
}
