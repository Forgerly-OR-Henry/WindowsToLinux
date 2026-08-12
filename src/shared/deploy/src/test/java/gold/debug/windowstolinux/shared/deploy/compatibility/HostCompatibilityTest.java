package gold.debug.windowstolinux.shared.deploy.compatibility;

import gold.debug.windowstolinux.shared.model.health.HealthCheck;
import gold.debug.windowstolinux.shared.model.project.DeploymentBuildTool;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectFacts;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectType;
import gold.debug.windowstolinux.shared.model.server.LinuxCapabilities;
import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSpecification;
import gold.debug.windowstolinux.shared.model.server.LinuxDistro;
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
                Set.of("avx", "avx2", "bmi1", "bmi2", "f16c", "fma", "movbe", "xsave"));
        assertEquals(HostSupport.READY_FOR_RUNTIME_VALIDATION,
                HostCompatibility.evaluate(ready, facts, runtime).support());
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
        return capabilities(distro, version, manager, docker, podman, maven, false, Set.of("sse4_2", "popcnt"));
    }

    private static LinuxCapabilities capabilities(LinuxDistro distro, String version, String manager,
                                                   boolean docker, boolean podman, boolean x86_64V3, Set<String> cpuFlags) {
        return capabilities(distro, version, manager, docker, podman, true, x86_64V3, cpuFlags);
    }

    private static LinuxCapabilities capabilities(LinuxDistro distro, String version, String manager,
                                                   boolean docker, boolean podman, boolean maven,
                                                   boolean x86_64V3, Set<String> cpuFlags) {
        return new LinuxCapabilities(distro, version, "x86_64", manager, true, docker, podman, podman,
                Set.of(21), Set.of(22), true, maven, Set.of("3.12"), true, docker, podman,
                x86_64V3, cpuFlags, "test evidence");
    }

    private static DeploymentProjectFacts facts(DeploymentProjectType type, DeploymentBuildTool tool) {
        return new DeploymentProjectFacts(Path.of("."), "demo", type, tool, List.of(), List.of(), List.of());
    }

    private static void assertUnsupported(LinuxCapabilities host, DeploymentProjectFacts facts,
                                          DeploymentRuntimeSpecification runtime) {
        assertEquals(HostSupport.UNSUPPORTED, HostCompatibility.evaluate(host, facts, runtime).support());
    }
}
