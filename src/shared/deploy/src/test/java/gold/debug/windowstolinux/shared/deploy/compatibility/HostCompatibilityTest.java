package gold.debug.windowstolinux.shared.deploy.compatibility;

import gold.debug.windowstolinux.shared.model.health.HealthCheck;
import gold.debug.windowstolinux.shared.model.server.LinuxCapabilities;
import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSpecification;
import gold.debug.windowstolinux.shared.model.server.LinuxDistro;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HostCompatibilityTest {
    @Test
    void acceptsOnlyCollectedUbuntuMatrixFactsForRuntimeValidation() {
        var result = HostCompatibility.evaluate(capabilities(LinuxDistro.UBUNTU, "24.04", "apt", true, true),
                new DeploymentRuntimeSpecification.NodeService(22, new HealthCheck.Tcp(18080, 10, 1)));

        assertEquals(HostSupport.READY_FOR_RUNTIME_VALIDATION, result.support());
    }

    @Test
    void rejectsUnavailableLanguageVersionsBeforeSourceUpload() {
        LinuxCapabilities host = capabilities(LinuxDistro.UBUNTU, "24.04", "apt", true, true);
        assertEquals(HostSupport.UNSUPPORTED, HostCompatibility.evaluate(host,
                new DeploymentRuntimeSpecification.NodeService(20, new HealthCheck.Tcp(18080, 10, 1))).support());
        assertEquals(HostSupport.UNSUPPORTED, HostCompatibility.evaluate(host,
                new DeploymentRuntimeSpecification.PythonService("3.11", "demo", new HealthCheck.Tcp(18081, 10, 1))).support());
        assertEquals(HostSupport.UNSUPPORTED, HostCompatibility.evaluate(host,
                new DeploymentRuntimeSpecification.JavaJar("app.jar", "example.Main", "17", java.util.List.of(),
                        java.util.List.of(), new HealthCheck.Tcp(18082, 10, 1))).support());
    }

    @Test
    void keepsCentosStreamTenCpuAssessmentHostSpecific() {
        var result = HostCompatibility.evaluate(capabilities(LinuxDistro.CENTOS_STREAM, "10", "dnf", true, true),
                new DeploymentRuntimeSpecification.NodeService(22, new HealthCheck.Tcp(18080, 10, 1)));

        assertEquals(HostSupport.REQUIRES_CPU_REVIEW, result.support());

        LinuxCapabilities ready = capabilities(LinuxDistro.CENTOS_STREAM, "10", "dnf", true, true, true,
                Set.of("avx", "avx2", "bmi1", "bmi2", "f16c", "fma", "movbe", "xsave"));
        assertEquals(HostSupport.READY_FOR_RUNTIME_VALIDATION, HostCompatibility.evaluate(ready,
                new DeploymentRuntimeSpecification.NodeService(22, new HealthCheck.Tcp(18080, 10, 1))).support());
    }

    @Test
    void requiresTheMatchingContainerRuntimeAndPreservesLegacyWarning() {
        var docker = new DeploymentRuntimeSpecification.Container(DeploymentRuntimeSpecification.ContainerEngine.DOCKER,
                Map.of(18080, 8080), java.util.List.of(), new HealthCheck.Tcp(18080, 10, 1));
        assertEquals(HostSupport.UNSUPPORTED,
                HostCompatibility.evaluate(capabilities(LinuxDistro.UBUNTU, "22.04", "apt", false, false), docker).support());
        assertEquals(HostSupport.LEGACY_RISK_CONFIRMATION_REQUIRED,
                HostCompatibility.evaluate(capabilities(LinuxDistro.LEGACY_CENTOS, "7", "yum", false, false),
                        new DeploymentRuntimeSpecification.NodeService(22, new HealthCheck.Tcp(18080, 10, 1))).support());
    }

    private static LinuxCapabilities capabilities(LinuxDistro distro, String version, String manager,
                                                           boolean docker, boolean podman) {
        return capabilities(distro, version, manager, docker, podman, false, Set.of("sse4_2", "popcnt"));
    }

    private static LinuxCapabilities capabilities(LinuxDistro distro, String version, String manager,
                                                   boolean docker, boolean podman, boolean x86_64V3, Set<String> cpuFlags) {
        return new LinuxCapabilities(distro, version, "x86_64", manager, true, docker, podman, podman,
                Set.of(21), Set.of(22), true, Set.of("3.12"), true, docker, podman,
                x86_64V3, cpuFlags, "test evidence");
    }
}
