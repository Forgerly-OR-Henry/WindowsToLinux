package gold.debug.windowstolinux.shared.standard.deploy.support.distro;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.Map;
import java.util.Set;

import gold.debug.windowstolinux.shared.model.capability.LinuxCapabilityFacts;
import gold.debug.windowstolinux.shared.model.server.CpuMicroarchitectureLevel;
import gold.debug.windowstolinux.shared.model.server.LinuxDistroType;
import gold.debug.windowstolinux.shared.model.server.security.LinuxFirewallKind;
import gold.debug.windowstolinux.shared.model.server.security.LinuxFirewallState;
import gold.debug.windowstolinux.shared.model.server.security.LinuxSecurityModuleType;
import gold.debug.windowstolinux.shared.model.server.security.LinuxSecurityPosture;
import gold.debug.windowstolinux.shared.model.server.security.LinuxSecurityState;
import gold.debug.windowstolinux.shared.standard.deploy.contract.result.compatibility.HostSupportStatus;
import org.junit.jupiter.api.Test;

class DistributionSupportEvaluatorTest {
    @Test
    void preservesDistributionStatusAndEvidenceOrdering() {
        var evidence = new ArrayList<String>();

        HostSupportStatus support = DistributionSupportEvaluator.evaluate(capabilities(LinuxDistroType.UBUNTU),
                evidence);

        assertEquals(HostSupportStatus.READY_FOR_RUNTIME_VALIDATION, support);
        assertEquals("package-architecture=amd64; cpu=X86_64_V1; security=APPARMOR/ENABLED; firewall=UFW/ACTIVE",
                evidence.get(0));
        assertEquals("Ubuntu CPU baseline satisfied: x86-64-v1", evidence.get(1));
    }

    @Test
    void keepsUnknownDistributionsOutsideTheTypedMatrix() {
        var evidence = new ArrayList<String>();

        HostSupportStatus support = DistributionSupportEvaluator.evaluate(capabilities(LinuxDistroType.OTHER),
                evidence);

        assertEquals(HostSupportStatus.UNSUPPORTED, support);
        assertEquals("distribution is outside the typed deployment matrix", evidence.get(1));
    }

    private static LinuxCapabilityFacts capabilities(LinuxDistroType distro) {
        return new LinuxCapabilityFacts(distro, "24.04", "x86_64", "apt", "amd64", true, true, true, true, Set.of(21),
                Set.of(22), true, true, Set.of("3.12"), true, Map.of(), Map.of(), true, true,
                CpuMicroarchitectureLevel.X86_64_V1, Set.of("sse4_2", "popcnt"),
                new LinuxSecurityPosture(LinuxSecurityModuleType.APPARMOR, LinuxSecurityState.ENABLED,
                        LinuxFirewallKind.UFW, LinuxFirewallState.ACTIVE),
                "test evidence");
    }
}
