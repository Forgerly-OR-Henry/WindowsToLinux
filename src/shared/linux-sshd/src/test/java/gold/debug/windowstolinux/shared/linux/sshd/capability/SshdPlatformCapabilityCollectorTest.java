package gold.debug.windowstolinux.shared.linux.sshd.capability;

import gold.debug.windowstolinux.shared.model.server.LinuxDistro;
import gold.debug.windowstolinux.shared.model.server.CpuMicroarchitectureLevel;
import gold.debug.windowstolinux.shared.model.server.LinuxFirewallKind;
import gold.debug.windowstolinux.shared.model.server.LinuxFirewallState;
import gold.debug.windowstolinux.shared.model.server.LinuxSecurityModule;
import gold.debug.windowstolinux.shared.model.server.LinuxSecurityState;
import gold.debug.windowstolinux.shared.model.project.AdvancedRuntimeKind;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SshdPlatformCapabilityCollectorTest {
    @Test
    void parsesOnlyFixedNonSecretProbeFacts() {
        var capabilities = SshdPlatformCapabilityCollector.fromValues(Map.ofEntries(
                Map.entry("DISTRO_ID", "centos"), Map.entry("DISTRO_VARIANT", "stream"), Map.entry("VERSION", "10"),
                Map.entry("ARCH", "x86_64"), Map.entry("PACKAGE_MANAGER", "dnf"),
                Map.entry("PACKAGE_ARCH", "x86_64"), Map.entry("SYSTEMD", "1"),
                Map.entry("DOCKER_CLIENT", "0"), Map.entry("DOCKER_OPERATIONAL", "0"), Map.entry("PODMAN_CLIENT", "1"),
                Map.entry("PODMAN_OPERATIONAL", "1"), Map.entry("PODMAN_QUADLET", "1"),
                Map.entry("JAVA_MAJORS", "21"), Map.entry("NODE_MAJORS", "22"), Map.entry("NPM", "1"),
                Map.entry("PYTHON3", "1"), Map.entry("PYTHON_VERSIONS", "3.11,3.12"),
                Map.entry("ADVANCED_GO", "1.24"), Map.entry("ADVANCED_RUST", "1.89.0"),
                Map.entry("ADVANCED_DOTNET", "8.0.408"), Map.entry("ADVANCED_KOTLIN", "21"),
                Map.entry("ADVANCED_PHP", "8.3"), Map.entry("ADVANCED_RUBY", "3.3.5"),
                Map.entry("CPU_LEVEL", "x86-64-v3"), Map.entry("CPU_FLAGS", "sse4_2,popcnt"),
                Map.entry("SECURITY_MODULE", "selinux"), Map.entry("SECURITY_STATE", "enforcing"),
                Map.entry("FIREWALL", "firewalld"), Map.entry("FIREWALL_STATE", "active")), "SHA256:host");

        assertEquals(LinuxDistro.CENTOS_STREAM, capabilities.distro());
        assertTrue(capabilities.podmanQuadletAvailable());
        assertTrue(capabilities.podmanOperational());
        assertEquals(java.util.Set.of(21), capabilities.javaMajorVersions());
        assertEquals(java.util.Set.of(22), capabilities.nodeMajorVersions());
        assertEquals(java.util.Set.of("3.11", "3.12"), capabilities.pythonVersions());
        assertTrue(capabilities.python3Available());
        assertTrue(capabilities.cpuFlags().contains("sse4_2"));
        assertEquals("x86_64", capabilities.packageArchitecture());
        assertEquals(CpuMicroarchitectureLevel.X86_64_V3, capabilities.cpuMicroarchitecture());
        assertEquals(LinuxSecurityModule.SELINUX, capabilities.securityPosture().module());
        assertEquals(LinuxSecurityState.ENFORCING, capabilities.securityPosture().state());
        assertEquals(LinuxFirewallKind.FIREWALLD, capabilities.securityPosture().firewall());
        assertEquals(LinuxFirewallState.ACTIVE, capabilities.securityPosture().firewallState());
        assertEquals(java.util.Set.of("1.24"), capabilities.advancedRuntimeVersions().get(AdvancedRuntimeKind.GO));
        assertEquals(java.util.Set.of("3.3.5"), capabilities.advancedRuntimeVersions().get(AdvancedRuntimeKind.RUBY));
        assertTrue(PlatformCapabilityProbeScript.render().contains("PODMAN_QUADLET"));
        assertTrue(PlatformCapabilityProbeScript.render().contains("ADVANCED_DOTNET"));
        assertTrue(PlatformCapabilityProbeScript.render().contains("SECURITY_MODULE"));
        assertTrue(PlatformCapabilityProbeScript.render().contains("FIREWALL_STATE"));
        assertTrue(PlatformCapabilityProbeScript.render().contains("PACKAGE_ARCH"));
        assertTrue(PlatformCapabilityProbeScript.render().contains("CPU_LEVEL"));
        assertTrue(!PlatformCapabilityProbeScript.render().contains("setenforce"));
        assertTrue(!PlatformCapabilityProbeScript.render().contains("systemctl stop"));
        assertTrue(!PlatformCapabilityProbeScript.render().contains("systemctl disable"));
    }

    @Test
    void classifiesEveryEnterpriseDistributionIndependently() {
        assertEquals(LinuxDistro.DEBIAN, classify("debian", "", "13"));
        assertEquals(LinuxDistro.ROCKY_LINUX, classify("rocky", "", "9.8"));
        assertEquals(LinuxDistro.ALMALINUX, classify("almalinux", "", "10.2"));
        assertEquals(LinuxDistro.ORACLE_LINUX, classify("ol", "", "10.2"));
    }

    private static LinuxDistro classify(String id, String variant, String version) {
        return SshdPlatformCapabilityCollector.fromValues(Map.of(
                "DISTRO_ID", id, "DISTRO_VARIANT", variant, "VERSION", version,
                "ARCH", "x86_64", "PACKAGE_MANAGER", "dnf"), "SHA256:fixture").distro();
    }
}
