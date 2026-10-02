package gold.debug.windowstolinux.mod.deployment.service.standard.deploy.support;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import gold.debug.windowstolinux.mod.deployment.service.core.model.DeploymentHealthCheck;
import gold.debug.windowstolinux.mod.deployment.service.core.model.DeploymentProjectKind;
import gold.debug.windowstolinux.mod.deployment.service.core.model.DeploymentRuntimeDefinition;
import gold.debug.windowstolinux.mod.deployment.service.core.model.capability.DeploymentEcosystemToolType;
import gold.debug.windowstolinux.mod.deployment.service.core.model.capability.DeploymentHostCapabilityStatus;
import gold.debug.windowstolinux.mod.deployment.service.core.model.capability.DeploymentLinuxCapabilityFacts;
import gold.debug.windowstolinux.mod.deployment.service.core.model.project.DeploymentBuildToolType;
import gold.debug.windowstolinux.mod.deployment.service.core.model.project.DeploymentProjectFacts;
import gold.debug.windowstolinux.mod.deployment.service.core.model.server.DeploymentCpuMicroarchitectureLevel;
import gold.debug.windowstolinux.mod.deployment.service.core.model.server.LinuxDistroType;
import gold.debug.windowstolinux.mod.deployment.service.core.model.server.security.DeploymentLinuxFirewallKind;
import gold.debug.windowstolinux.mod.deployment.service.core.model.server.security.DeploymentLinuxFirewallState;
import gold.debug.windowstolinux.mod.deployment.service.core.model.server.security.DeploymentLinuxSecurityModuleType;
import gold.debug.windowstolinux.mod.deployment.service.core.model.server.security.DeploymentLinuxSecurityPosture;
import gold.debug.windowstolinux.mod.deployment.service.core.model.server.security.DeploymentLinuxSecurityState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class DeploymentHostCompatibilityEvaluatorTest {
    @Test
    void acceptsOnlyCollectedUbuntuMatrixFactsForRuntimeValidation() {
        var runtime = new DeploymentRuntimeDefinition.NodeService(22, new DeploymentHealthCheck.Tcp(18080, 10, 1));
        var result = DeploymentHostCompatibilityEvaluator.evaluate(
            capabilities(LinuxDistroType.UBUNTU, "24.04", "apt", true, true),
            facts(DeploymentProjectKind.NODE_SERVICE, DeploymentBuildToolType.NPM), runtime);

        assertEquals(DeploymentHostCapabilityStatus.READY_FOR_RUNTIME_VALIDATION, result.support());
    }

    @Test
    void rejectsUnavailableLanguageVersionsBeforeSourceUpload() {
        DeploymentLinuxCapabilityFacts host = capabilities(LinuxDistroType.UBUNTU, "24.04", "apt", true, true);
        assertUnsupported(host, facts(DeploymentProjectKind.NODE_SERVICE, DeploymentBuildToolType.NPM),
            new DeploymentRuntimeDefinition.NodeService(20, new DeploymentHealthCheck.Tcp(18080, 10, 1)));
        assertUnsupported(host, facts(DeploymentProjectKind.PYTHON_SERVICE, DeploymentBuildToolType.PIP_LOCKED),
            new DeploymentRuntimeDefinition.PythonService("3.11", "demo",
                new DeploymentHealthCheck.Tcp(18081, 10, 1)));
        assertUnsupported(host, facts(DeploymentProjectKind.JAVA_JAR, DeploymentBuildToolType.JAVA),
            new DeploymentRuntimeDefinition.JavaJar("app.jar", "example.Main", "17", java.util.List.of(),
                java.util.List.of(), new DeploymentHealthCheck.Tcp(18082, 10, 1)));
    }

    @Test
    void keepsCentosStreamTenCpuAssessmentHostSpecific() {
        var runtime = new DeploymentRuntimeDefinition.NodeService(22, new DeploymentHealthCheck.Tcp(18080, 10, 1));
        var facts = facts(DeploymentProjectKind.NODE_SERVICE, DeploymentBuildToolType.NPM);
        var result = DeploymentHostCompatibilityEvaluator
            .evaluate(capabilities(LinuxDistroType.CENTOS_STREAM, "10", "dnf", true, true), facts, runtime);

        assertEquals(DeploymentHostCapabilityStatus.REQUIRES_CPU_REVIEW, result.support());

        DeploymentLinuxCapabilityFacts ready = capabilities(LinuxDistroType.CENTOS_STREAM, "10", "dnf", true, true,
            true, DeploymentCpuMicroarchitectureLevel.X86_64_V3,
            Set.of("avx", "avx2", "bmi1", "bmi2", "f16c", "fma", "movbe", "xsave"));
        assertEquals(DeploymentHostCapabilityStatus.READY_FOR_RUNTIME_VALIDATION,
            DeploymentHostCompatibilityEvaluator.evaluate(ready, facts, runtime).support());
    }

    @ParameterizedTest
    @CsvSource({"9, UNKNOWN, REQUIRES_CPU_REVIEW", "9, X86_64_V1, REQUIRES_CPU_REVIEW",
        "9, X86_64_V2, READY_FOR_RUNTIME_VALIDATION", "9, X86_64_V3, READY_FOR_RUNTIME_VALIDATION",
        "9, X86_64_V4, READY_FOR_RUNTIME_VALIDATION", "10, UNKNOWN, REQUIRES_CPU_REVIEW",
        "10, X86_64_V1, REQUIRES_CPU_REVIEW", "10, X86_64_V2, REQUIRES_CPU_REVIEW",
        "10, X86_64_V3, READY_FOR_RUNTIME_VALIDATION", "10, X86_64_V4, READY_FOR_RUNTIME_VALIDATION"})
    void enforcesCentosStreamCpuBaselines(String version, DeploymentCpuMicroarchitectureLevel cpu,
                                          DeploymentHostCapabilityStatus expected) {
        var host = capabilities(LinuxDistroType.CENTOS_STREAM, version, "dnf", true, true, true, cpu,
            Set.of("sse4_2", "popcnt"));
        var runtime = new DeploymentRuntimeDefinition.NodeService(22, new DeploymentHealthCheck.Tcp(18080, 10, 1));

        assertEquals(expected,
            DeploymentHostCompatibilityEvaluator
                .evaluate(host, facts(DeploymentProjectKind.NODE_SERVICE, DeploymentBuildToolType.NPM), runtime)
                .support());
    }

    @Test
    void appliesIndependentMaintainedDistributionVersionAndCpuPolicies() {
        var facts = facts(DeploymentProjectKind.NODE_SERVICE, DeploymentBuildToolType.NPM);
        var runtime = new DeploymentRuntimeDefinition.NodeService(22, new DeploymentHealthCheck.Tcp(18080, 10, 1));

        assertReady(capabilities(LinuxDistroType.DEBIAN, "13", "apt", true, true), facts, runtime);
        assertReady(capabilities(LinuxDistroType.ROCKY_LINUX, "9.8", "dnf", true, true), facts, runtime);
        assertReady(capabilities(LinuxDistroType.ROCKY_LINUX, "10.2", "dnf", true, true, true,
            DeploymentCpuMicroarchitectureLevel.X86_64_V3, Set.of()), facts, runtime);
        assertReady(capabilities(LinuxDistroType.ALMALINUX, "9.8", "dnf", true, true), facts, runtime);
        assertReady(capabilities(LinuxDistroType.ALMALINUX, "10.2", "dnf", true, true, true,
            DeploymentCpuMicroarchitectureLevel.X86_64_V3, Set.of()), facts, runtime);
        assertReady(capabilities(LinuxDistroType.ORACLE_LINUX, "9.7", "dnf", true, true), facts, runtime);
        assertReady(capabilities(LinuxDistroType.ORACLE_LINUX, "10.2", "dnf", true, true, true,
            DeploymentCpuMicroarchitectureLevel.X86_64_V3, Set.of()), facts, runtime);

        assertUnsupported(capabilities(LinuxDistroType.DEBIAN, "12", "apt", true, true), facts, runtime);
        assertUnsupported(capabilities(LinuxDistroType.ROCKY_LINUX, "9.7", "dnf", true, true), facts, runtime);
        assertUnsupported(capabilities(LinuxDistroType.ALMALINUX, "10.1", "dnf", true, true), facts, runtime);
        assertUnsupported(capabilities(LinuxDistroType.ORACLE_LINUX, "9.6", "dnf", true, true), facts, runtime);
    }

    @Test
    void keepsAlmaV2DependenciesAndEnterpriseSecurityExplicitlyReviewable() {
        var facts = facts(DeploymentProjectKind.NODE_SERVICE, DeploymentBuildToolType.NPM);
        var runtime = new DeploymentRuntimeDefinition.NodeService(22, new DeploymentHealthCheck.Tcp(18080, 10, 1));
        DeploymentLinuxCapabilityFacts almaV2 = capabilities(LinuxDistroType.ALMALINUX, "10.2", "dnf", "x86_64_v2",
            DeploymentCpuMicroarchitectureLevel.X86_64_V2, enforcingSelinux());
        assertEquals(DeploymentHostCapabilityStatus.REQUIRES_CPU_REVIEW,
            DeploymentHostCompatibilityEvaluator.evaluate(almaV2, facts, runtime).support());

        DeploymentLinuxCapabilityFacts permissiveRocky = capabilities(LinuxDistroType.ROCKY_LINUX, "9.8", "dnf",
            "x86_64", DeploymentCpuMicroarchitectureLevel.X86_64_V1,
            new DeploymentLinuxSecurityPosture(DeploymentLinuxSecurityModuleType.SELINUX,
                DeploymentLinuxSecurityState.PERMISSIVE, DeploymentLinuxFirewallKind.FIREWALLD,
                DeploymentLinuxFirewallState.ACTIVE));
        assertEquals(DeploymentHostCapabilityStatus.REQUIRES_SECURITY_REVIEW,
            DeploymentHostCompatibilityEvaluator.evaluate(permissiveRocky, facts, runtime).support());
    }

    @Test
    void requiresTheMatchingContainerRuntimeAndPreservesLegacyWarning() {
        var docker = new DeploymentRuntimeDefinition.Container(DeploymentRuntimeDefinition.ContainerEngineType.DOCKER,
            Map.of(18080, 8080), java.util.List.of(), new DeploymentHealthCheck.Tcp(18080, 10, 1));
        assertEquals(DeploymentHostCapabilityStatus.UNSUPPORTED,
            DeploymentHostCompatibilityEvaluator.evaluate(
                capabilities(LinuxDistroType.UBUNTU, "22.04", "apt", false, false),
                facts(DeploymentProjectKind.DOCKERFILE_CONTAINER, DeploymentBuildToolType.CONTAINER_BUILD),
                docker).support());
        assertEquals(DeploymentHostCapabilityStatus.LEGACY_RISK_CONFIRMATION_REQUIRED,
            DeploymentHostCompatibilityEvaluator.evaluate(
                    capabilities(LinuxDistroType.LEGACY_CENTOS, "7", "yum", false, false),
                    facts(DeploymentProjectKind.NODE_SERVICE, DeploymentBuildToolType.NPM),
                    new DeploymentRuntimeDefinition.NodeService(22, new DeploymentHealthCheck.Tcp(18080, 10, 1)))
                .support());
    }

    @Test
    void requiresMavenOnlyForTheSystemMavenSpringBootBuild() {
        DeploymentLinuxCapabilityFacts noMaven = capabilities(LinuxDistroType.UBUNTU, "24.04", "apt", true, true,
            false);
        var runtime = new DeploymentRuntimeDefinition.SpringBoot(new DeploymentHealthCheck.Tcp(18080, 10, 1));

        assertUnsupported(noMaven, facts(DeploymentProjectKind.SPRING_BOOT, DeploymentBuildToolType.MAVEN), runtime);
        assertEquals(DeploymentHostCapabilityStatus.READY_FOR_RUNTIME_VALIDATION,
            DeploymentHostCompatibilityEvaluator.evaluate(noMaven,
                    facts(DeploymentProjectKind.SPRING_BOOT, DeploymentBuildToolType.MAVEN_WRAPPER), runtime)
                .support());
        assertEquals(DeploymentHostCapabilityStatus.READY_FOR_RUNTIME_VALIDATION,
            DeploymentHostCompatibilityEvaluator.evaluate(noMaven,
                    facts(DeploymentProjectKind.SPRING_BOOT, DeploymentBuildToolType.GRADLE_WRAPPER), runtime)
                .support());
    }

    private static DeploymentLinuxCapabilityFacts capabilities(LinuxDistroType distro, String version, String manager,
                                                               boolean docker, boolean podman) {
        return capabilities(distro, version, manager, docker, podman, true);
    }

    private static DeploymentLinuxCapabilityFacts capabilities(LinuxDistroType distro, String version, String manager,
                                                               boolean docker, boolean podman, boolean maven) {
        return capabilities(distro, version, manager, docker, podman, maven,
            DeploymentCpuMicroarchitectureLevel.X86_64_V1, Set.of("sse4_2", "popcnt"));
    }

    private static DeploymentLinuxCapabilityFacts capabilities(LinuxDistroType distro, String version, String manager,
                                                               boolean docker, boolean podman, boolean maven, DeploymentCpuMicroarchitectureLevel cpu,
                                                               Set<String> cpuFlags) {
        String packageArchitecture = distro == LinuxDistroType.UBUNTU || distro == LinuxDistroType.DEBIAN
            ? "amd64"
            : "x86_64";
        DeploymentLinuxSecurityPosture security = switch (distro) {
            case CENTOS_STREAM, ROCKY_LINUX, ALMALINUX, ORACLE_LINUX -> enforcingSelinux();
            default -> new DeploymentLinuxSecurityPosture(DeploymentLinuxSecurityModuleType.APPARMOR,
                DeploymentLinuxSecurityState.ENABLED, DeploymentLinuxFirewallKind.UFW,
                DeploymentLinuxFirewallState.ACTIVE);
        };
        return capabilities(distro, version, manager, packageArchitecture, cpu, security, docker, podman, maven,
            cpuFlags);
    }

    private static DeploymentLinuxCapabilityFacts capabilities(LinuxDistroType distro, String version, String manager,
                                                               String packageArchitecture, DeploymentCpuMicroarchitectureLevel cpu,
                                                               DeploymentLinuxSecurityPosture security) {
        return capabilities(distro, version, manager, packageArchitecture, cpu, security, true, true, true,
            Set.of("sse4_2", "popcnt"));
    }

    private static DeploymentLinuxCapabilityFacts capabilities(LinuxDistroType distro, String version, String manager,
                                                               String packageArchitecture, DeploymentCpuMicroarchitectureLevel cpu,
                                                               DeploymentLinuxSecurityPosture security, boolean docker, boolean podman, boolean maven,
                                                               Set<String> cpuFlags) {
        return new DeploymentLinuxCapabilityFacts(distro, version, "x86_64", manager, packageArchitecture, true, docker,
            podman, podman, Set.of(21), Set.of(22), true, maven, Set.of("3.12"), true, Map.of(),
            Map.of(DeploymentEcosystemToolType.NPM, Set.of("10.9.2")), docker, podman, cpu, cpuFlags, security,
            "test evidence");
    }

    private static DeploymentProjectFacts facts(DeploymentProjectKind type, DeploymentBuildToolType tool) {
        return new DeploymentProjectFacts(Path.of("."), "demo", type, tool, List.of(), List.of(), List.of());
    }

    private static void assertUnsupported(DeploymentLinuxCapabilityFacts host, DeploymentProjectFacts facts,
                                          DeploymentRuntimeDefinition runtime) {
        assertEquals(DeploymentHostCapabilityStatus.UNSUPPORTED,
            DeploymentHostCompatibilityEvaluator.evaluate(host, facts, runtime).support());
    }

    private static void assertReady(DeploymentLinuxCapabilityFacts host, DeploymentProjectFacts facts,
                                    DeploymentRuntimeDefinition runtime) {
        assertEquals(DeploymentHostCapabilityStatus.READY_FOR_RUNTIME_VALIDATION,
            DeploymentHostCompatibilityEvaluator.evaluate(host, facts, runtime).support());
    }

    private static DeploymentLinuxSecurityPosture enforcingSelinux() {
        return new DeploymentLinuxSecurityPosture(DeploymentLinuxSecurityModuleType.SELINUX,
            DeploymentLinuxSecurityState.ENFORCING, DeploymentLinuxFirewallKind.FIREWALLD,
            DeploymentLinuxFirewallState.ACTIVE);
    }
}
