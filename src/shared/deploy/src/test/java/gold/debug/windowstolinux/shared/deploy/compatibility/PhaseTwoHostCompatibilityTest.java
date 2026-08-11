package gold.debug.windowstolinux.shared.deploy.compatibility;

import gold.debug.windowstolinux.shared.deploy.plan.PhaseTwoRuntimeSpecification;
import gold.debug.windowstolinux.shared.model.health.HealthCheck;
import gold.debug.windowstolinux.shared.model.server.PhaseTwoLinuxCapabilities;
import gold.debug.windowstolinux.shared.model.server.PhaseTwoLinuxDistro;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PhaseTwoHostCompatibilityTest {
    @Test
    void acceptsOnlyCollectedUbuntuMatrixFactsForRuntimeValidation() {
        var result = PhaseTwoHostCompatibility.evaluate(capabilities(PhaseTwoLinuxDistro.UBUNTU, "24.04", "apt", true, true),
                new PhaseTwoRuntimeSpecification.NodeService(22, new HealthCheck.Tcp(18080, 10, 1)));

        assertEquals(PhaseTwoHostSupport.READY_FOR_RUNTIME_VALIDATION, result.support());
    }

    @Test
    void keepsCentosStreamTenCpuAssessmentHostSpecific() {
        var result = PhaseTwoHostCompatibility.evaluate(capabilities(PhaseTwoLinuxDistro.CENTOS_STREAM, "10", "dnf", true, true),
                new PhaseTwoRuntimeSpecification.NodeService(22, new HealthCheck.Tcp(18080, 10, 1)));

        assertEquals(PhaseTwoHostSupport.REQUIRES_CPU_REVIEW, result.support());
    }

    @Test
    void requiresTheMatchingContainerRuntimeAndPreservesLegacyWarning() {
        var docker = new PhaseTwoRuntimeSpecification.Container(PhaseTwoRuntimeSpecification.ContainerEngine.DOCKER,
                Map.of(18080, 8080), java.util.List.of(), new HealthCheck.Tcp(18080, 10, 1));
        assertEquals(PhaseTwoHostSupport.UNSUPPORTED,
                PhaseTwoHostCompatibility.evaluate(capabilities(PhaseTwoLinuxDistro.UBUNTU, "22.04", "apt", false, false), docker).support());
        assertEquals(PhaseTwoHostSupport.LEGACY_RISK_CONFIRMATION_REQUIRED,
                PhaseTwoHostCompatibility.evaluate(capabilities(PhaseTwoLinuxDistro.LEGACY_CENTOS, "7", "yum", false, false),
                        new PhaseTwoRuntimeSpecification.NodeService(22, new HealthCheck.Tcp(18080, 10, 1))).support());
    }

    private static PhaseTwoLinuxCapabilities capabilities(PhaseTwoLinuxDistro distro, String version, String manager,
                                                           boolean docker, boolean podman) {
        return new PhaseTwoLinuxCapabilities(distro, version, "x86_64", manager, true, docker, podman, podman,
                Set.of("sse4_2", "popcnt"), "test evidence");
    }
}
