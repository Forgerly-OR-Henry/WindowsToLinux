package gold.debug.windowstolinux.mod.deployment.service.standard.deploy.support.distro;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.Map;
import java.util.Set;

import gold.debug.windowstolinux.mod.deployment.service.core.model.capability.DeploymentHostSupportStatus;
import gold.debug.windowstolinux.mod.deployment.service.core.model.capability.DeploymentLinuxCapabilityFacts;
import gold.debug.windowstolinux.mod.deployment.service.core.model.server.DeploymentCpuMicroarchitectureLevel;
import gold.debug.windowstolinux.mod.deployment.service.core.model.server.LinuxDistroType;
import gold.debug.windowstolinux.mod.deployment.service.core.model.server.security.DeploymentLinuxFirewallKind;
import gold.debug.windowstolinux.mod.deployment.service.core.model.server.security.DeploymentLinuxFirewallState;
import gold.debug.windowstolinux.mod.deployment.service.core.model.server.security.DeploymentLinuxSecurityModuleType;
import gold.debug.windowstolinux.mod.deployment.service.core.model.server.security.DeploymentLinuxSecurityPosture;
import gold.debug.windowstolinux.mod.deployment.service.core.model.server.security.DeploymentLinuxSecurityState;
import org.junit.jupiter.api.Test;

class DeploymentDistributionSupportEvaluatorTest {
    @Test
    void preservesDistributionStatusAndEvidenceOrdering() {
        var evidence = new ArrayList<String>();

        DeploymentHostSupportStatus support = DeploymentDistributionSupportEvaluator
                .evaluate(capabilities(LinuxDistroType.UBUNTU), evidence);

        assertEquals(DeploymentHostSupportStatus.READY_FOR_RUNTIME_VALIDATION, support);
        assertEquals("package-architecture=amd64; cpu=X86_64_V1; security=APPARMOR/ENABLED; firewall=UFW/ACTIVE",
                evidence.get(0));
        assertEquals("Ubuntu CPU baseline satisfied: x86-64-v1", evidence.get(1));
    }

    @Test
    void keepsUnknownDistributionsOutsideTheTypedMatrix() {
        var evidence = new ArrayList<String>();

        DeploymentHostSupportStatus support = DeploymentDistributionSupportEvaluator
                .evaluate(capabilities(LinuxDistroType.OTHER), evidence);

        assertEquals(DeploymentHostSupportStatus.UNSUPPORTED, support);
        assertEquals("distribution is outside the typed deployment matrix", evidence.get(1));
    }

    private static DeploymentLinuxCapabilityFacts capabilities(LinuxDistroType distro) {
        return new DeploymentLinuxCapabilityFacts(distro, "24.04", "x86_64", "apt", "amd64", true, true, true, true,
                Set.of(21), Set.of(22), true, true, Set.of("3.12"), true, Map.of(), Map.of(), true, true,
                DeploymentCpuMicroarchitectureLevel.X86_64_V1, Set.of("sse4_2", "popcnt"),
                new DeploymentLinuxSecurityPosture(DeploymentLinuxSecurityModuleType.APPARMOR,
                        DeploymentLinuxSecurityState.ENABLED, DeploymentLinuxFirewallKind.UFW,
                        DeploymentLinuxFirewallState.ACTIVE),
                "test evidence");
    }
}
