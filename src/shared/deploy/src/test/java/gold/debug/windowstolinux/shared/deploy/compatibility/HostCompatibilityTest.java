package gold.debug.windowstolinux.shared.deploy.compatibility;

import gold.debug.windowstolinux.shared.deploy.plan.DeploymentRuntimeSpecification;
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
    void keepsCentosStreamTenCpuAssessmentHostSpecific() {
        var result = HostCompatibility.evaluate(capabilities(LinuxDistro.CENTOS_STREAM, "10", "dnf", true, true),
                new DeploymentRuntimeSpecification.NodeService(22, new HealthCheck.Tcp(18080, 10, 1)));

        assertEquals(HostSupport.REQUIRES_CPU_REVIEW, result.support());
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
        return new LinuxCapabilities(distro, version, "x86_64", manager, true, docker, podman, podman,
                Set.of("sse4_2", "popcnt"), "test evidence");
    }
}
