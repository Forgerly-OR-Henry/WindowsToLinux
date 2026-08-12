package gold.debug.windowstolinux.shared.deploy.compatibility;

import gold.debug.windowstolinux.shared.model.health.HealthCheck;
import gold.debug.windowstolinux.shared.model.project.DeploymentBuildTool;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectFacts;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectType;
import gold.debug.windowstolinux.shared.model.server.LinuxCapabilities;
import gold.debug.windowstolinux.shared.model.server.CpuMicroarchitectureLevel;
import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSpecification;
import gold.debug.windowstolinux.shared.model.server.LinuxDistro;
import gold.debug.windowstolinux.shared.model.server.LinuxFirewallKind;
import gold.debug.windowstolinux.shared.model.server.LinuxFirewallState;
import gold.debug.windowstolinux.shared.model.server.LinuxSecurityModule;
import gold.debug.windowstolinux.shared.model.server.LinuxSecurityPosture;
import gold.debug.windowstolinux.shared.model.server.LinuxSecurityState;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.List;
import java.util.Set;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HostCompatibilityTest {
    @Test
    void acceptsOnlyCollectedUbuntuMatrixFactsForRuntimeValidation() {
        var runtime = new DeploymentRuntimeSpecification.NodeService(22, new HealthCheck.Tcp(18080, 10, 1));
        var result = HostCompatibility.evaluate(capabilities(LinuxDistro.UBUNTU, "24.04", "apt", true, true),
                facts(DeploymentProjectType.NODE_SERVICE, DeploymentBuildTool.NPM), runtime);

        assertEquals(HostSupport.READY_FOR_RUNTIME_VALIDATION, result.support());
    }

    @Test
    void rejectsUnavailableLanguageVersionsBeforeSourceUpload() {
        LinuxCapabilities host = capabilities(LinuxDistro.UBUNTU, "24.04", "apt", true, true);
        assertUnsupported(host, facts(DeploymentProjectType.NODE_SERVICE, DeploymentBuildTool.NPM),
                new DeploymentRuntimeSpecification.NodeService(20, new HealthCheck.Tcp(18080, 10, 1)));
        assertUnsupported(host, facts(DeploymentProjectType.PYTHON_SERVICE, DeploymentBuildTool.PYTHON_VENV),
                new DeploymentRuntimeSpecification.PythonService("3.11", "demo", new HealthCheck.Tcp(18081, 10, 1)));
        assertUnsupported(host, facts(DeploymentProjectType.JAVA_JAR, DeploymentBuildTool.JAVA),
                new DeploymentRuntimeSpecification.JavaJar("app.jar", "example.Main", "17", java.util.List.of(),
                        java.util.List.of(), new HealthCheck.Tcp(18082, 10, 1)));
    }

    @Test
    void keepsCentosStreamTenCpuAssessmentHostSpecific() {
        var runtime = new DeploymentRuntimeSpecification.NodeService(22, new HealthCheck.Tcp(18080, 10, 1));
        var facts = facts(DeploymentProjectType.NODE_SERVICE, DeploymentBuildTool.NPM);
        var result = HostCompatibility.evaluate(capabilities(LinuxDistro.CENTOS_STREAM, "10", "dnf", true, true),
                facts, runtime);

        assertEquals(HostSupport.REQUIRES_CPU_REVIEW, result.support());

        LinuxCapabilities ready = capabilities(LinuxDistro.CENTOS_STREAM, "10", "dnf", true, true, true,
                CpuMicroarchitectureLevel.X86_64_V3, Set.of("avx", "avx2", "bmi1", "bmi2", "f16c", "fma", "movbe", "xsave"));
        assertEquals(HostSupport.READY_FOR_RUNTIME_VALIDATION,
                HostCompatibility.evaluate(ready, facts, runtime).support());
    }

    @Test
    void appliesIndependentMaintainedDistributionVersionAndCpuPolicies() {
        var facts = facts(DeploymentProjectType.NODE_SERVICE, DeploymentBuildTool.NPM);
        var runtime = new DeploymentRuntimeSpecification.NodeService(22, new HealthCheck.Tcp(18080, 10, 1));

        assertReady(capabilities(LinuxDistro.DEBIAN, "13", "apt", true, true), facts, runtime);
        assertReady(capabilities(LinuxDistro.ROCKY_LINUX, "9.8", "dnf", true, true), facts, runtime);
        assertReady(capabilities(LinuxDistro.ROCKY_LINUX, "10.2", "dnf", true, true, true,
                CpuMicroarchitectureLevel.X86_64_V3, Set.of()), facts, runtime);
        assertReady(capabilities(LinuxDistro.ALMALINUX, "9.8", "dnf", true, true), facts, runtime);
        assertReady(capabilities(LinuxDistro.ALMALINUX, "10.2", "dnf", true, true, true,
                CpuMicroarchitectureLevel.X86_64_V3, Set.of()), facts, runtime);
        assertReady(capabilities(LinuxDistro.ORACLE_LINUX, "9.7", "dnf", true, true), facts, runtime);
        assertReady(capabilities(LinuxDistro.ORACLE_LINUX, "10.2", "dnf", true, true, true,
                CpuMicroarchitectureLevel.X86_64_V3, Set.of()), facts, runtime);

        assertUnsupported(capabilities(LinuxDistro.DEBIAN, "12", "apt", true, true), facts, runtime);
        assertUnsupported(capabilities(LinuxDistro.ROCKY_LINUX, "9.7", "dnf", true, true), facts, runtime);
        assertUnsupported(capabilities(LinuxDistro.ALMALINUX, "10.1", "dnf", true, true), facts, runtime);
        assertUnsupported(capabilities(LinuxDistro.ORACLE_LINUX, "9.6", "dnf", true, true), facts, runtime);
    }

    @Test
    void keepsAlmaV2DependenciesAndEnterpriseSecurityExplicitlyReviewable() {
        var facts = facts(DeploymentProjectType.NODE_SERVICE, DeploymentBuildTool.NPM);
        var runtime = new DeploymentRuntimeSpecification.NodeService(22, new HealthCheck.Tcp(18080, 10, 1));
        LinuxCapabilities almaV2 = capabilities(LinuxDistro.ALMALINUX, "10.2", "dnf", "x86_64_v2",
                CpuMicroarchitectureLevel.X86_64_V2, enforcingSelinux());
        assertEquals(HostSupport.REQUIRES_CPU_REVIEW,
                HostCompatibility.evaluate(almaV2, facts, runtime).support());

        LinuxCapabilities permissiveRocky = capabilities(LinuxDistro.ROCKY_LINUX, "9.8", "dnf", "x86_64",
                CpuMicroarchitectureLevel.X86_64_V1,
                new LinuxSecurityPosture(LinuxSecurityModule.SELINUX, LinuxSecurityState.PERMISSIVE,
                        LinuxFirewallKind.FIREWALLD, LinuxFirewallState.ACTIVE));
        assertEquals(HostSupport.REQUIRES_SECURITY_REVIEW,
                HostCompatibility.evaluate(permissiveRocky, facts, runtime).support());
    }

    @Test
    void requiresTheMatchingContainerRuntimeAndPreservesLegacyWarning() {
        var docker = new DeploymentRuntimeSpecification.Container(DeploymentRuntimeSpecification.ContainerEngine.DOCKER,
                Map.of(18080, 8080), java.util.List.of(), new HealthCheck.Tcp(18080, 10, 1));
        assertEquals(HostSupport.UNSUPPORTED,
                HostCompatibility.evaluate(capabilities(LinuxDistro.UBUNTU, "22.04", "apt", false, false),
                        facts(DeploymentProjectType.DOCKERFILE_CONTAINER, DeploymentBuildTool.CONTAINER_BUILD), docker).support());
        assertEquals(HostSupport.LEGACY_RISK_CONFIRMATION_REQUIRED,
                HostCompatibility.evaluate(capabilities(LinuxDistro.LEGACY_CENTOS, "7", "yum", false, false),
                        facts(DeploymentProjectType.NODE_SERVICE, DeploymentBuildTool.NPM),
                        new DeploymentRuntimeSpecification.NodeService(22, new HealthCheck.Tcp(18080, 10, 1))).support());
    }

    @Test
    void requiresMavenOnlyForTheSystemMavenSpringBootBuild() {
        LinuxCapabilities noMaven = capabilities(LinuxDistro.UBUNTU, "24.04", "apt", true, true, false);
        var runtime = new DeploymentRuntimeSpecification.SpringBoot(new HealthCheck.Tcp(18080, 10, 1));

        assertUnsupported(noMaven, facts(DeploymentProjectType.SPRING_BOOT, DeploymentBuildTool.MAVEN), runtime);
        assertEquals(HostSupport.READY_FOR_RUNTIME_VALIDATION,
                HostCompatibility.evaluate(noMaven,
                        facts(DeploymentProjectType.SPRING_BOOT, DeploymentBuildTool.MAVEN_WRAPPER), runtime).support());
        assertEquals(HostSupport.READY_FOR_RUNTIME_VALIDATION,
                HostCompatibility.evaluate(noMaven,
                        facts(DeploymentProjectType.SPRING_BOOT, DeploymentBuildTool.GRADLE_WRAPPER), runtime).support());
    }

    private static LinuxCapabilities capabilities(LinuxDistro distro, String version, String manager,
                                                            boolean docker, boolean podman) {
        return capabilities(distro, version, manager, docker, podman, true);
    }

    private static LinuxCapabilities capabilities(LinuxDistro distro, String version, String manager,
                                                   boolean docker, boolean podman, boolean maven) {
        return capabilities(distro, version, manager, docker, podman, maven,
                CpuMicroarchitectureLevel.X86_64_V1, Set.of("sse4_2", "popcnt"));
    }

    private static LinuxCapabilities capabilities(LinuxDistro distro, String version, String manager,
                                                   boolean docker, boolean podman, boolean maven,
                                                   CpuMicroarchitectureLevel cpu, Set<String> cpuFlags) {
        String packageArchitecture = distro == LinuxDistro.UBUNTU || distro == LinuxDistro.DEBIAN
                ? "amd64" : "x86_64";
        LinuxSecurityPosture security = switch (distro) {
            case CENTOS_STREAM, ROCKY_LINUX, ALMALINUX, ORACLE_LINUX -> enforcingSelinux();
            default -> new LinuxSecurityPosture(LinuxSecurityModule.APPARMOR, LinuxSecurityState.ENABLED,
                    LinuxFirewallKind.UFW, LinuxFirewallState.ACTIVE);
        };
        return capabilities(distro, version, manager, packageArchitecture, cpu, security,
                docker, podman, maven, cpuFlags);
    }

    private static LinuxCapabilities capabilities(LinuxDistro distro, String version, String manager,
                                                   String packageArchitecture, CpuMicroarchitectureLevel cpu,
                                                   LinuxSecurityPosture security) {
        return capabilities(distro, version, manager, packageArchitecture, cpu, security,
                true, true, true, Set.of("sse4_2", "popcnt"));
    }

    private static LinuxCapabilities capabilities(LinuxDistro distro, String version, String manager,
                                                   String packageArchitecture, CpuMicroarchitectureLevel cpu,
                                                   LinuxSecurityPosture security, boolean docker, boolean podman,
                                                   boolean maven, Set<String> cpuFlags) {
        return new LinuxCapabilities(distro, version, "x86_64", manager, packageArchitecture,
                true, docker, podman, podman, Set.of(21), Set.of(22), true, maven, Set.of("3.12"), true,
                Map.of(), docker, podman, cpu, cpuFlags, security, "test evidence");
    }

    private static DeploymentProjectFacts facts(DeploymentProjectType type, DeploymentBuildTool tool) {
        return new DeploymentProjectFacts(Path.of("."), "demo", type, tool, List.of(), List.of(), List.of());
    }

    private static void assertUnsupported(LinuxCapabilities host, DeploymentProjectFacts facts,
                                          DeploymentRuntimeSpecification runtime) {
        assertEquals(HostSupport.UNSUPPORTED, HostCompatibility.evaluate(host, facts, runtime).support());
    }

    private static void assertReady(LinuxCapabilities host, DeploymentProjectFacts facts,
                                    DeploymentRuntimeSpecification runtime) {
        assertEquals(HostSupport.READY_FOR_RUNTIME_VALIDATION,
                HostCompatibility.evaluate(host, facts, runtime).support());
    }

    private static LinuxSecurityPosture enforcingSelinux() {
        return new LinuxSecurityPosture(LinuxSecurityModule.SELINUX, LinuxSecurityState.ENFORCING,
                LinuxFirewallKind.FIREWALLD, LinuxFirewallState.ACTIVE);
    }
}
