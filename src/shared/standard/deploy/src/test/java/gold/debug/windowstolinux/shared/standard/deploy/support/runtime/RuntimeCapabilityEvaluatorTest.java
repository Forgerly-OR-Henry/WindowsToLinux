package gold.debug.windowstolinux.shared.standard.deploy.support.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import gold.debug.windowstolinux.shared.model.capability.EcosystemToolType;
import gold.debug.windowstolinux.shared.model.capability.LinuxCapabilityFacts;
import gold.debug.windowstolinux.shared.model.health.HealthCheck;
import gold.debug.windowstolinux.shared.model.language.ProjectLanguageFacts;
import gold.debug.windowstolinux.shared.model.language.SourceLanguageType;
import gold.debug.windowstolinux.shared.model.project.DeploymentBuildToolType;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectFacts;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectType;
import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSpecification;
import gold.debug.windowstolinux.shared.model.project.application.*;
import gold.debug.windowstolinux.shared.model.server.CpuMicroarchitectureLevel;
import gold.debug.windowstolinux.shared.model.server.LinuxDistroType;
import gold.debug.windowstolinux.shared.model.server.security.LinuxFirewallKind;
import gold.debug.windowstolinux.shared.model.server.security.LinuxFirewallState;
import gold.debug.windowstolinux.shared.model.server.security.LinuxSecurityModuleType;
import gold.debug.windowstolinux.shared.model.server.security.LinuxSecurityPosture;
import gold.debug.windowstolinux.shared.model.server.security.LinuxSecurityState;
import gold.debug.windowstolinux.shared.model.toolchain.*;
import org.junit.jupiter.api.Test;

class RuntimeCapabilityEvaluatorTest {
    private static final HealthCheck HEALTH = new HealthCheck.Tcp(18080, 10, 1);

    @Test
    void matchesEveryExactEcosystemArchitecture() {
        List<RuntimeFixture> fixtures = List.of(
                new RuntimeFixture(new DeploymentRuntimeSpecification.JavaSource("src", "demo.Main", "21", List.of(),
                        List.of(), HEALTH), DeploymentBuildToolType.JDK),
                new RuntimeFixture(new DeploymentRuntimeSpecification.NodeService(22, HEALTH),
                        DeploymentBuildToolType.NPM),
                new RuntimeFixture(new DeploymentRuntimeSpecification.NodeService(22, HEALTH),
                        DeploymentBuildToolType.PNPM),
                new RuntimeFixture(new DeploymentRuntimeSpecification.NodeService(22, HEALTH),
                        DeploymentBuildToolType.YARN),
                new RuntimeFixture(new DeploymentRuntimeSpecification.PythonService("3.12", "demo.main", HEALTH),
                        DeploymentBuildToolType.PIP_LOCKED),
                new RuntimeFixture(new DeploymentRuntimeSpecification.PythonService("3.12", "demo.main", HEALTH),
                        DeploymentBuildToolType.PIPENV_LOCKED),
                new RuntimeFixture(new DeploymentRuntimeSpecification.PythonService("3.12", "demo.main", HEALTH),
                        DeploymentBuildToolType.POETRY_LOCKED),
                new RuntimeFixture(new DeploymentRuntimeSpecification.PythonService("3.12", "demo.main", HEALTH),
                        DeploymentBuildToolType.UV_LOCKED),
                new RuntimeFixture(new DeploymentRuntimeSpecification.GoService("1.24", "demo", "main.go", HEALTH),
                        DeploymentBuildToolType.GO_MODULE),
                new RuntimeFixture(
                        new DeploymentRuntimeSpecification.RustService("1.89.0", "demo", "src/main.rs", HEALTH),
                        DeploymentBuildToolType.CARGO_LOCKED),
                new RuntimeFixture(
                        new DeploymentRuntimeSpecification.DotNetService("8.0.408", "Demo", "Demo.dll", HEALTH),
                        DeploymentBuildToolType.DOTNET_LOCKED),
                new RuntimeFixture(
                        new DeploymentRuntimeSpecification.KotlinService("2.0.21", "demo", "demo.MainKt", HEALTH),
                        DeploymentBuildToolType.GRADLE_KOTLIN_WRAPPER),
                new RuntimeFixture(
                        new DeploymentRuntimeSpecification.KotlinService("2.0.21", "demo", "demo.MainKt", HEALTH),
                        DeploymentBuildToolType.KOTLINC),
                new RuntimeFixture(new DeploymentRuntimeSpecification.PhpService("8.3", "public", "public/index.php",
                        8080, HEALTH), DeploymentBuildToolType.COMPOSER_LOCKED),
                new RuntimeFixture(new DeploymentRuntimeSpecification.PhpService("8.3", "public", "public/index.php",
                        8080, HEALTH), DeploymentBuildToolType.PHP_CLI),
                new RuntimeFixture(
                        new DeploymentRuntimeSpecification.RubyService("3.3.5", "bundle", "config.ru", 8080, HEALTH),
                        DeploymentBuildToolType.BUNDLER_LOCKED),
                new RuntimeFixture(
                        new DeploymentRuntimeSpecification.RubyService("3.3.5", "source", "server.rb", 8080, HEALTH),
                        DeploymentBuildToolType.RUBY_CLI),
                new RuntimeFixture(
                        new DeploymentRuntimeSpecification.CmakeService("w2l-release", "demo", "demo", HEALTH),
                        DeploymentBuildToolType.CMAKE));

        for (RuntimeFixture fixture : fixtures) {
            RuntimeCapabilityDecision decision = RuntimeCapabilityEvaluator.evaluate(capabilities(),
                    facts(fixture.runtime().projectType(), fixture.buildTool()), fixture.runtime());
            assertTrue(decision.supported(), fixture.runtime().projectType().name());
        }
    }

    @Test
    void preservesTheSharedMissingVersionRejection() {
        var runtime = new DeploymentRuntimeSpecification.GoService("1.23", "demo", "main.go", HEALTH);

        RuntimeCapabilityDecision decision = RuntimeCapabilityEvaluator.evaluate(capabilities(),
                facts(DeploymentProjectType.GO_SERVICE, DeploymentBuildToolType.GO_MODULE), runtime);

        assertEquals(false, decision.supported());
        assertEquals("the selected ecosystem service runtime version is not available",
                decision.detail().orElseThrow());
    }

    @Test
    void rejectsPackageManagerVersionsThatCannotRunTheFixedCommands() {
        LinuxCapabilityFacts base = capabilities();
        Map<EcosystemToolType, Set<String>> tools = new java.util.EnumMap<>(base.ecosystemToolVersions());
        tools.put(EcosystemToolType.PNPM, Set.of("7.33.7"));
        tools.put(EcosystemToolType.YARN, Set.of("1.22.22"));
        tools.put(EcosystemToolType.POETRY, Set.of("1.1.15"));
        tools.put(EcosystemToolType.UV, Set.of("0.3.5"));
        LinuxCapabilityFacts incompatible = withTools(base, tools);

        for (RuntimeFixture fixture : List.of(
                new RuntimeFixture(new DeploymentRuntimeSpecification.NodeService(22, HEALTH),
                        DeploymentBuildToolType.PNPM),
                new RuntimeFixture(new DeploymentRuntimeSpecification.NodeService(22, HEALTH),
                        DeploymentBuildToolType.YARN),
                new RuntimeFixture(new DeploymentRuntimeSpecification.PythonService("3.12", "demo.main", HEALTH),
                        DeploymentBuildToolType.POETRY_LOCKED),
                new RuntimeFixture(new DeploymentRuntimeSpecification.PythonService("3.12", "demo.main", HEALTH),
                        DeploymentBuildToolType.UV_LOCKED))) {
            assertEquals(false,
                    RuntimeCapabilityEvaluator.evaluate(incompatible,
                            facts(fixture.runtime().projectType(), fixture.buildTool()), fixture.runtime())
                            .supported());
        }
    }

    @Test
    void rejectsCmakeWhenTheFixedToolchainIsUnavailable() {
        LinuxCapabilityFacts base = capabilities();
        Map<EcosystemToolType, Set<String>> tools = new java.util.EnumMap<>(base.ecosystemToolVersions());
        tools.remove(EcosystemToolType.NINJA);
        var runtime = new DeploymentRuntimeSpecification.CmakeService("w2l-release", "demo", "demo", HEALTH);

        RuntimeCapabilityDecision decision = RuntimeCapabilityEvaluator.evaluate(withTools(base, tools),
                facts(DeploymentProjectType.CMAKE_SERVICE, DeploymentBuildToolType.CMAKE), runtime);

        assertEquals(false, decision.supported());
        assertTrue(decision.detail().orElseThrow().contains("Ninja"));

        tools.put(EcosystemToolType.NINJA, Set.of("1.12.1"));
        tools.put(EcosystemToolType.CMAKE, Set.of("3.24.4"));
        RuntimeCapabilityDecision obsoleteCmake = RuntimeCapabilityEvaluator.evaluate(withTools(base, tools),
                facts(DeploymentProjectType.CMAKE_SERVICE, DeploymentBuildToolType.CMAKE), runtime);
        assertEquals(false, obsoleteCmake.supported());
        assertTrue(obsoleteCmake.detail().orElseThrow().contains("CMake"));
    }

    private static DeploymentProjectFacts facts(DeploymentProjectType type, DeploymentBuildToolType tool) {
        if (type == DeploymentProjectType.CMAKE_SERVICE) {
            return new DeploymentProjectFacts(
                    Path.of("."), "demo", type, tool, new ProjectLanguageFacts(Set.of(),
                            Set.of(SourceLanguageType.C, SourceLanguageType.CPP), Map.of(), List.of()),
                    List.of(), List.of(), List.of());
        }
        return new DeploymentProjectFacts(Path.of("."), "demo", type, tool, List.of(), List.of(), List.of());
    }

    @Test
    void mixedApplicationsUseHostCompilersAlongsidePreparedLanguageTools() {
        for (var ecosystem : List.of(ToolchainEcosystemType.C, ToolchainEcosystemType.CPP)) {
            var rust = ToolchainRequirement.declared(ToolchainEcosystemType.RUST, "1.89.0", "Cargo.toml",
                    ToolchainRequirement.PurposeType.BUILD);
            var nativeTool = ToolchainRequirement.declared(ecosystem, "17", "native/CMakeLists.txt",
                    ToolchainRequirement.PurposeType.LANGUAGE_TARGET);
            var project = facts(DeploymentProjectType.RUST_SERVICE, DeploymentBuildToolType.CARGO_LOCKED)
                    .withToolchains(List.of(rust, nativeTool));
            var workload = new ApplicationWorkload(ApplicationWorkload.ExecutionMode.ON_DEMAND, true,
                    ApplicationCommand.primary(), "", List.of(),
                    Optional.of(new ApplicationCommand("", List.of("--help"))), "usage", Optional.empty(), List.of(),
                    List.of(new ApplicationCompanion("worker", "native", ApplicationCompanion.BuildType.CMAKE_SERVICE,
                            ".w2l/bin/worker", "NATIVE_HELPER")));
            var runtime = new DeploymentRuntimeSpecification.RustService("1.89.0", "demo", "src/main.rs", HEALTH)
                    .withWorkload(workload);
            var prepared = new ResolvedToolchainSet(ToolchainSupportCatalog.defaults().revision(),
                    List.of(new ResolvedToolchainSet.Selection(rust,
                            ToolchainVersion.parse(ToolchainEcosystemType.RUST, "1.89.0").orElseThrow(), "/opt/rust",
                            ResolvedToolchainSet.OriginType.MANAGED, "fixture", "a".repeat(64))));
            assertTrue(RuntimeCapabilityEvaluator.evaluate(capabilities(), project, runtime, prepared).supported());
            var tools = new java.util.EnumMap<>(capabilities().ecosystemToolVersions());
            tools.remove(ecosystem == ToolchainEcosystemType.C
                    ? EcosystemToolType.C_COMPILER
                    : EcosystemToolType.CPP_COMPILER);
            assertEquals(false, RuntimeCapabilityEvaluator
                    .evaluate(withTools(capabilities(), tools), project, runtime, prepared).supported());
        }
    }

    private static LinuxCapabilityFacts capabilities() {
        return new LinuxCapabilityFacts(LinuxDistroType.UBUNTU, "24.04", "x86_64", "apt", "amd64", true, true, true,
                true, Set.of(21), Set.of(22), true, true, Set.of("3.12"), true,
                Map.of(DeploymentProjectType.GO_SERVICE, Set.of("1.24"), DeploymentProjectType.RUST_SERVICE,
                        Set.of("1.89.0"), DeploymentProjectType.DOTNET_SERVICE, Set.of("8.0.408"),
                        DeploymentProjectType.KOTLIN_SERVICE, Set.of("21"), DeploymentProjectType.PHP_SERVICE,
                        Set.of("8.3"), DeploymentProjectType.RUBY_SERVICE, Set.of("3.3.5")),
                Map.ofEntries(Map.entry(EcosystemToolType.JAVAC, Set.of("21.0.8")),
                        Map.entry(EcosystemToolType.JAR, Set.of("21.0.8")),
                        Map.entry(EcosystemToolType.NPM, Set.of("10.9.2")),
                        Map.entry(EcosystemToolType.PNPM, Set.of("10.15.1")),
                        Map.entry(EcosystemToolType.YARN, Set.of("4.9.2")),
                        Map.entry(EcosystemToolType.PIP, Set.of("24.0")),
                        Map.entry(EcosystemToolType.PIPENV, Set.of("2025.0.4")),
                        Map.entry(EcosystemToolType.POETRY, Set.of("2.1.3")),
                        Map.entry(EcosystemToolType.UV, Set.of("0.8.12")),
                        Map.entry(EcosystemToolType.KOTLINC, Set.of("2.0.21")),
                        Map.entry(EcosystemToolType.COMPOSER, Set.of("2.8.10")),
                        Map.entry(EcosystemToolType.BUNDLER, Set.of("2.6.9")),
                        Map.entry(EcosystemToolType.CMAKE, Set.of("3.31.6")),
                        Map.entry(EcosystemToolType.NINJA, Set.of("1.12.1")),
                        Map.entry(EcosystemToolType.C_COMPILER, Set.of("14.2.1")),
                        Map.entry(EcosystemToolType.CPP_COMPILER, Set.of("14.2.1"))),
                true, true, CpuMicroarchitectureLevel.X86_64_V1, Set.of("sse4_2", "popcnt"),
                new LinuxSecurityPosture(LinuxSecurityModuleType.APPARMOR, LinuxSecurityState.ENABLED,
                        LinuxFirewallKind.UFW, LinuxFirewallState.ACTIVE),
                "test evidence");
    }

    private static LinuxCapabilityFacts withTools(LinuxCapabilityFacts base,
            Map<EcosystemToolType, Set<String>> tools) {
        return new LinuxCapabilityFacts(base.distro(), base.version(), base.architecture(), base.packageManager(),
                base.packageArchitecture(), base.systemdAvailable(), base.dockerAvailable(), base.podmanAvailable(),
                base.podmanQuadletAvailable(), base.javaMajorVersions(), base.nodeMajorVersions(), base.npmAvailable(),
                base.mavenAvailable(), base.pythonVersions(), base.python3Available(), base.serviceRuntimeVersions(),
                tools, base.dockerOperational(), base.podmanOperational(), base.cpuMicroarchitecture(), base.cpuFlags(),
                base.securityPosture(), base.evidence());
    }

    private record RuntimeFixture(DeploymentRuntimeSpecification runtime, DeploymentBuildToolType buildTool) {
    }
}
