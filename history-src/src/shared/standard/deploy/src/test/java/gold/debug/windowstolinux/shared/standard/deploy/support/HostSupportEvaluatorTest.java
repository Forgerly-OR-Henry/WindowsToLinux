package gold.debug.windowstolinux.shared.standard.deploy.support;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import gold.debug.windowstolinux.shared.model.capability.EcosystemToolType;
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
import gold.debug.windowstolinux.shared.standard.deploy.contract.result.compatibility.HostSupportStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class HostSupportEvaluatorTest {
    @Test
    void acceptsOnlyCollectedUbuntuMatrixFactsForRuntimeValidation() {
        var runtime = new DeploymentRuntimeSpecification.NodeService(22, new HealthCheck.Tcp(18080, 10, 1));
        var result = HostSupportEvaluator.evaluate(capabilities(LinuxDistroType.UBUNTU, "24.04", "apt", true, true),
                facts(DeploymentProjectType.NODE_SERVICE, DeploymentBuildToolType.NPM), runtime);

        assertEquals(HostSupportStatus.READY_FOR_RUNTIME_VALIDATION, result.support());
    }

    @Test
    void rejectsUnavailableLanguageVersionsBeforeSourceUpload() {
        LinuxCapabilityFacts host = capabilities(LinuxDistroType.UBUNTU, "24.04", "apt", true, true);
        assertUnsupported(host, facts(DeploymentProjectType.NODE_SERVICE, DeploymentBuildToolType.NPM),
                new DeploymentRuntimeSpecification.NodeService(20, new HealthCheck.Tcp(18080, 10, 1)));
        assertUnsupported(host, facts(DeploymentProjectType.PYTHON_SERVICE, DeploymentBuildToolType.PIP_LOCKED),
                new DeploymentRuntimeSpecification.PythonService("3.11", "demo", new HealthCheck.Tcp(18081, 10, 1)));
        assertUnsupported(host, facts(DeploymentProjectType.JAVA_JAR, DeploymentBuildToolType.JAVA),
                new DeploymentRuntimeSpecification.JavaJar("app.jar", "example.Main", "17", java.util.List.of(),
                        java.util.List.of(), new HealthCheck.Tcp(18082, 10, 1)));
    }

    @Test
    void keepsCentosStreamTenCpuAssessmentHostSpecific() {
        var runtime = new DeploymentRuntimeSpecification.NodeService(22, new HealthCheck.Tcp(18080, 10, 1));
        var facts = facts(DeploymentProjectType.NODE_SERVICE, DeploymentBuildToolType.NPM);
        var result = HostSupportEvaluator.evaluate(capabilities(LinuxDistroType.CENTOS_STREAM, "10", "dnf", true, true),
                facts, runtime);

        assertEquals(HostSupportStatus.REQUIRES_CPU_REVIEW, result.support());

        LinuxCapabilityFacts ready = capabilities(LinuxDistroType.CENTOS_STREAM, "10", "dnf", true, true, true,
                CpuMicroarchitectureLevel.X86_64_V3,
                Set.of("avx", "avx2", "bmi1", "bmi2", "f16c", "fma", "movbe", "xsave"));
        assertEquals(HostSupportStatus.READY_FOR_RUNTIME_VALIDATION,
                HostSupportEvaluator.evaluate(ready, facts, runtime).support());
    }

    @ParameterizedTest
    @CsvSource({"9, UNKNOWN, REQUIRES_CPU_REVIEW", "9, X86_64_V1, REQUIRES_CPU_REVIEW",
            "9, X86_64_V2, READY_FOR_RUNTIME_VALIDATION", "9, X86_64_V3, READY_FOR_RUNTIME_VALIDATION",
            "9, X86_64_V4, READY_FOR_RUNTIME_VALIDATION", "10, UNKNOWN, REQUIRES_CPU_REVIEW",
            "10, X86_64_V1, REQUIRES_CPU_REVIEW", "10, X86_64_V2, REQUIRES_CPU_REVIEW",
            "10, X86_64_V3, READY_FOR_RUNTIME_VALIDATION", "10, X86_64_V4, READY_FOR_RUNTIME_VALIDATION"})
    void enforcesCentosStreamCpuBaselines(String version, CpuMicroarchitectureLevel cpu, HostSupportStatus expected) {
        var host = capabilities(LinuxDistroType.CENTOS_STREAM, version, "dnf", true, true, true, cpu,
                Set.of("sse4_2", "popcnt"));
        var runtime = new DeploymentRuntimeSpecification.NodeService(22, new HealthCheck.Tcp(18080, 10, 1));

        assertEquals(expected,
                HostSupportEvaluator
                        .evaluate(host, facts(DeploymentProjectType.NODE_SERVICE, DeploymentBuildToolType.NPM), runtime)
                        .support());
    }

    @Test
    void appliesIndependentMaintainedDistributionVersionAndCpuPolicies() {
        var facts = facts(DeploymentProjectType.NODE_SERVICE, DeploymentBuildToolType.NPM);
        var runtime = new DeploymentRuntimeSpecification.NodeService(22, new HealthCheck.Tcp(18080, 10, 1));

        assertReady(capabilities(LinuxDistroType.DEBIAN, "13", "apt", true, true), facts, runtime);
        assertReady(capabilities(LinuxDistroType.ROCKY_LINUX, "9.8", "dnf", true, true), facts, runtime);
        assertReady(capabilities(LinuxDistroType.ROCKY_LINUX, "10.2", "dnf", true, true, true,
                CpuMicroarchitectureLevel.X86_64_V3, Set.of()), facts, runtime);
        assertReady(capabilities(LinuxDistroType.ALMALINUX, "9.8", "dnf", true, true), facts, runtime);
        assertReady(capabilities(LinuxDistroType.ALMALINUX, "10.2", "dnf", true, true, true,
                CpuMicroarchitectureLevel.X86_64_V3, Set.of()), facts, runtime);
        assertReady(capabilities(LinuxDistroType.ORACLE_LINUX, "9.7", "dnf", true, true), facts, runtime);
        assertReady(capabilities(LinuxDistroType.ORACLE_LINUX, "10.2", "dnf", true, true, true,
                CpuMicroarchitectureLevel.X86_64_V3, Set.of()), facts, runtime);

        assertUnsupported(capabilities(LinuxDistroType.DEBIAN, "12", "apt", true, true), facts, runtime);
        assertUnsupported(capabilities(LinuxDistroType.ROCKY_LINUX, "9.7", "dnf", true, true), facts, runtime);
        assertUnsupported(capabilities(LinuxDistroType.ALMALINUX, "10.1", "dnf", true, true), facts, runtime);
        assertUnsupported(capabilities(LinuxDistroType.ORACLE_LINUX, "9.6", "dnf", true, true), facts, runtime);
    }

    @Test
    void keepsAlmaV2DependenciesAndEnterpriseSecurityExplicitlyReviewable() {
        var facts = facts(DeploymentProjectType.NODE_SERVICE, DeploymentBuildToolType.NPM);
        var runtime = new DeploymentRuntimeSpecification.NodeService(22, new HealthCheck.Tcp(18080, 10, 1));
        LinuxCapabilityFacts almaV2 = capabilities(LinuxDistroType.ALMALINUX, "10.2", "dnf", "x86_64_v2",
                CpuMicroarchitectureLevel.X86_64_V2, enforcingSelinux());
        assertEquals(HostSupportStatus.REQUIRES_CPU_REVIEW,
                HostSupportEvaluator.evaluate(almaV2, facts, runtime).support());

        LinuxCapabilityFacts permissiveRocky = capabilities(LinuxDistroType.ROCKY_LINUX, "9.8", "dnf", "x86_64",
                CpuMicroarchitectureLevel.X86_64_V1, new LinuxSecurityPosture(LinuxSecurityModuleType.SELINUX,
                        LinuxSecurityState.PERMISSIVE, LinuxFirewallKind.FIREWALLD, LinuxFirewallState.ACTIVE));
        assertEquals(HostSupportStatus.REQUIRES_SECURITY_REVIEW,
                HostSupportEvaluator.evaluate(permissiveRocky, facts, runtime).support());
    }

    @Test
    void requiresTheMatchingContainerRuntimeAndPreservesLegacyWarning() {
        var docker = new DeploymentRuntimeSpecification.Container(
                DeploymentRuntimeSpecification.ContainerEngineType.DOCKER, Map.of(18080, 8080), java.util.List.of(),
                new HealthCheck.Tcp(18080, 10, 1));
        assertEquals(HostSupportStatus.UNSUPPORTED,
                HostSupportEvaluator.evaluate(capabilities(LinuxDistroType.UBUNTU, "22.04", "apt", false, false),
                        facts(DeploymentProjectType.DOCKERFILE_CONTAINER, DeploymentBuildToolType.CONTAINER_BUILD),
                        docker).support());
        assertEquals(HostSupportStatus.LEGACY_RISK_CONFIRMATION_REQUIRED,
                HostSupportEvaluator
                        .evaluate(capabilities(LinuxDistroType.LEGACY_CENTOS, "7", "yum", false, false),
                                facts(DeploymentProjectType.NODE_SERVICE, DeploymentBuildToolType.NPM),
                                new DeploymentRuntimeSpecification.NodeService(22, new HealthCheck.Tcp(18080, 10, 1)))
                        .support());
    }

    @Test
    void requiresMavenOnlyForTheSystemMavenSpringBootBuild() {
        LinuxCapabilityFacts noMaven = capabilities(LinuxDistroType.UBUNTU, "24.04", "apt", true, true, false);
        var runtime = new DeploymentRuntimeSpecification.SpringBoot(new HealthCheck.Tcp(18080, 10, 1));

        assertUnsupported(noMaven, facts(DeploymentProjectType.SPRING_BOOT, DeploymentBuildToolType.MAVEN), runtime);
        assertEquals(HostSupportStatus.READY_FOR_RUNTIME_VALIDATION,
                HostSupportEvaluator.evaluate(noMaven,
                        facts(DeploymentProjectType.SPRING_BOOT, DeploymentBuildToolType.MAVEN_WRAPPER), runtime)
                        .support());
        assertEquals(HostSupportStatus.READY_FOR_RUNTIME_VALIDATION,
                HostSupportEvaluator.evaluate(noMaven,
                        facts(DeploymentProjectType.SPRING_BOOT, DeploymentBuildToolType.GRADLE_WRAPPER), runtime)
                        .support());
    }

    private static LinuxCapabilityFacts capabilities(LinuxDistroType distro, String version, String manager,
            boolean docker, boolean podman) {
        return capabilities(distro, version, manager, docker, podman, true);
    }

    private static LinuxCapabilityFacts capabilities(LinuxDistroType distro, String version, String manager,
            boolean docker, boolean podman, boolean maven) {
        return capabilities(distro, version, manager, docker, podman, maven, CpuMicroarchitectureLevel.X86_64_V1,
                Set.of("sse4_2", "popcnt"));
    }

    private static LinuxCapabilityFacts capabilities(LinuxDistroType distro, String version, String manager,
            boolean docker, boolean podman, boolean maven, CpuMicroarchitectureLevel cpu, Set<String> cpuFlags) {
        String packageArchitecture = distro == LinuxDistroType.UBUNTU || distro == LinuxDistroType.DEBIAN
                ? "amd64"
                : "x86_64";
        LinuxSecurityPosture security = switch (distro) {
            case CENTOS_STREAM, ROCKY_LINUX, ALMALINUX, ORACLE_LINUX -> enforcingSelinux();
            default -> new LinuxSecurityPosture(LinuxSecurityModuleType.APPARMOR, LinuxSecurityState.ENABLED,
                    LinuxFirewallKind.UFW, LinuxFirewallState.ACTIVE);
        };
        return capabilities(distro, version, manager, packageArchitecture, cpu, security, docker, podman, maven,
                cpuFlags);
    }

    private static LinuxCapabilityFacts capabilities(LinuxDistroType distro, String version, String manager,
            String packageArchitecture, CpuMicroarchitectureLevel cpu, LinuxSecurityPosture security) {
        return capabilities(distro, version, manager, packageArchitecture, cpu, security, true, true, true,
                Set.of("sse4_2", "popcnt"));
    }

    private static LinuxCapabilityFacts capabilities(LinuxDistroType distro, String version, String manager,
            String packageArchitecture, CpuMicroarchitectureLevel cpu, LinuxSecurityPosture security, boolean docker,
            boolean podman, boolean maven, Set<String> cpuFlags) {
        return new LinuxCapabilityFacts(distro, version, "x86_64", manager, packageArchitecture, true, docker, podman,
                podman, Set.of(21), Set.of(22), true, maven, Set.of("3.12"), true, Map.of(),
                Map.of(EcosystemToolType.NPM, Set.of("10.9.2")), docker, podman, cpu, cpuFlags, security,
                "test evidence");
    }

    private static DeploymentProjectFacts facts(DeploymentProjectType type, DeploymentBuildToolType tool) {
        return new DeploymentProjectFacts(Path.of("."), "demo", type, tool, List.of(), List.of(), List.of());
    }

    private static void assertUnsupported(LinuxCapabilityFacts host, DeploymentProjectFacts facts,
            DeploymentRuntimeSpecification runtime) {
        assertEquals(HostSupportStatus.UNSUPPORTED, HostSupportEvaluator.evaluate(host, facts, runtime).support());
    }

    private static void assertReady(LinuxCapabilityFacts host, DeploymentProjectFacts facts,
            DeploymentRuntimeSpecification runtime) {
        assertEquals(HostSupportStatus.READY_FOR_RUNTIME_VALIDATION,
                HostSupportEvaluator.evaluate(host, facts, runtime).support());
    }

    private static LinuxSecurityPosture enforcingSelinux() {
        return new LinuxSecurityPosture(LinuxSecurityModuleType.SELINUX, LinuxSecurityState.ENFORCING,
                LinuxFirewallKind.FIREWALLD, LinuxFirewallState.ACTIVE);
    }
}
