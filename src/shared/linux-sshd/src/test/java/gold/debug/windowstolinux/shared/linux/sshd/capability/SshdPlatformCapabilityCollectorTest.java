package gold.debug.windowstolinux.shared.linux.sshd.capability;

import gold.debug.windowstolinux.shared.linux.sshd.capability.ManagedPlatformCapabilityProbe;
import gold.debug.windowstolinux.shared.model.capability.EcosystemToolType;
import gold.debug.windowstolinux.shared.model.server.LinuxDistroType;
import gold.debug.windowstolinux.shared.model.server.CpuMicroarchitectureLevel;
import gold.debug.windowstolinux.shared.model.server.security.LinuxFirewallKind;
import gold.debug.windowstolinux.shared.model.server.security.LinuxFirewallState;
import gold.debug.windowstolinux.shared.model.server.security.LinuxSecurityModuleType;
import gold.debug.windowstolinux.shared.model.server.security.LinuxSecurityState;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectType;
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
                Map.entry("SERVICE_GO", "1.24"), Map.entry("SERVICE_RUST", "1.89.0"),
                Map.entry("SERVICE_DOTNET", "8.0.408"), Map.entry("SERVICE_KOTLIN", "21"),
                Map.entry("SERVICE_PHP", "8.3"), Map.entry("SERVICE_RUBY", "3.3.5"),
                Map.entry("TOOL_JAVAC", "21.0.8"), Map.entry("TOOL_JAR", "21.0.8"),
                Map.entry("TOOL_PNPM", "10.15.1"), Map.entry("TOOL_UV", "0.8.12"),
                Map.entry("TOOL_KOTLINC", "2.0.21"), Map.entry("TOOL_CMAKE", "3.31.6"),
                Map.entry("TOOL_NINJA", "1.12.1"),
                Map.entry("TOOL_C_COMPILER", "14.2.1"), Map.entry("TOOL_CPP_COMPILER", "14.2.1"),
                Map.entry("CPU_LEVEL", "x86-64-v3"), Map.entry("CPU_FLAGS", "sse4_2,popcnt"),
                Map.entry("SECURITY_MODULE", "selinux"), Map.entry("SECURITY_STATE", "enforcing"),
                Map.entry("FIREWALL", "firewalld"), Map.entry("FIREWALL_STATE", "active")), "SHA256:host");

        assertEquals(LinuxDistroType.CENTOS_STREAM, capabilities.distro());
        assertTrue(capabilities.podmanQuadletAvailable());
        assertTrue(capabilities.podmanOperational());
        assertEquals(java.util.Set.of(21), capabilities.javaMajorVersions());
        assertEquals(java.util.Set.of(22), capabilities.nodeMajorVersions());
        assertEquals(java.util.Set.of("3.11", "3.12"), capabilities.pythonVersions());
        assertTrue(capabilities.python3Available());
        assertTrue(capabilities.cpuFlags().contains("sse4_2"));
        assertEquals("x86_64", capabilities.packageArchitecture());
        assertEquals(CpuMicroarchitectureLevel.X86_64_V3, capabilities.cpuMicroarchitecture());
        assertEquals(LinuxSecurityModuleType.SELINUX, capabilities.securityPosture().module());
        assertEquals(LinuxSecurityState.ENFORCING, capabilities.securityPosture().state());
        assertEquals(LinuxFirewallKind.FIREWALLD, capabilities.securityPosture().firewall());
        assertEquals(LinuxFirewallState.ACTIVE, capabilities.securityPosture().firewallState());
        assertEquals(java.util.Set.of("1.24"), capabilities.serviceRuntimeVersions().get(DeploymentProjectType.GO_SERVICE));
        assertEquals(java.util.Set.of("3.3.5"), capabilities.serviceRuntimeVersions().get(DeploymentProjectType.RUBY_SERVICE));
        assertEquals(java.util.Set.of("21.0.8"), capabilities.ecosystemToolVersions().get(EcosystemToolType.JAVAC));
        assertEquals(java.util.Set.of("10.15.1"), capabilities.ecosystemToolVersions().get(EcosystemToolType.PNPM));
        assertEquals(java.util.Set.of("2.0.21"), capabilities.ecosystemToolVersions().get(EcosystemToolType.KOTLINC));
        assertEquals(java.util.Set.of("1.12.1"), capabilities.ecosystemToolVersions().get(EcosystemToolType.NINJA));
        assertEquals(java.util.Set.of("14.2.1"), capabilities.ecosystemToolVersions().get(EcosystemToolType.CPP_COMPILER));
        assertTrue(ManagedPlatformCapabilityProbe.render().contains("PODMAN_QUADLET"));
        assertTrue(ManagedPlatformCapabilityProbe.render().contains("SERVICE_DOTNET"));
        assertTrue(ManagedPlatformCapabilityProbe.render().contains("TOOL_KOTLINC"));
        assertTrue(ManagedPlatformCapabilityProbe.render().contains("TOOL_CMAKE"));
        assertTrue(ManagedPlatformCapabilityProbe.render().contains("TOOL_NINJA"));
        assertTrue(ManagedPlatformCapabilityProbe.render().contains("printf(\"%d.%d\""));
        assertTrue(!ManagedPlatformCapabilityProbe.render().contains("printf(\"%%d.%%d\""));
        assertTrue(ManagedPlatformCapabilityProbe.render().contains("SECURITY_MODULE"));
        assertTrue(ManagedPlatformCapabilityProbe.render().contains("FIREWALL_STATE"));
        assertTrue(ManagedPlatformCapabilityProbe.render().contains("nft list ruleset"));
        assertTrue(ManagedPlatformCapabilityProbe.render().contains("managed_java='/usr/local/lib/windowstolinux/java-21'"));
        assertTrue(ManagedPlatformCapabilityProbe.render().contains("\"$managed_java\" -XshowSettings:properties"));
        assertTrue(ManagedPlatformCapabilityProbe.render().contains("PACKAGE_ARCH"));
        assertTrue(ManagedPlatformCapabilityProbe.render().contains("CPU_LEVEL"));
        assertTrue(!ManagedPlatformCapabilityProbe.render().contains("setenforce"));
        assertTrue(!ManagedPlatformCapabilityProbe.render().contains("systemctl stop"));
        assertTrue(!ManagedPlatformCapabilityProbe.render().contains("systemctl disable"));
    }

    @Test
    void parsesAnObservedInactiveNftablesRuleset() {
        var capabilities = SshdPlatformCapabilityCollector.fromValues(Map.of(
                "DISTRO_ID", "centos", "DISTRO_VARIANT", "", "VERSION", "9", "ARCH", "x86_64",
                "PACKAGE_MANAGER", "dnf", "FIREWALL", "nftables", "FIREWALL_STATE", "inactive"),
                "SHA256:fixture");

        assertEquals(LinuxFirewallKind.NFTABLES, capabilities.securityPosture().firewall());
        assertEquals(LinuxFirewallState.INACTIVE, capabilities.securityPosture().firewallState());
    }

    @Test
    void classifiesEveryEnterpriseDistributionIndependently() {
        assertEquals(LinuxDistroType.DEBIAN, classify("debian", "", "13"));
        assertEquals(LinuxDistroType.CENTOS_STREAM, classify("centos", "", "9"));
        assertEquals(LinuxDistroType.CENTOS_STREAM, classify("centos", "stream", "10"));
        assertEquals(LinuxDistroType.LEGACY_CENTOS, classify("centos", "", "8"));
        assertEquals(LinuxDistroType.ROCKY_LINUX, classify("rocky", "", "9.8"));
        assertEquals(LinuxDistroType.ALMALINUX, classify("almalinux", "", "10.2"));
        assertEquals(LinuxDistroType.ORACLE_LINUX, classify("ol", "", "10.2"));
    }

    private static LinuxDistroType classify(String id, String variant, String version) {
        return SshdPlatformCapabilityCollector.fromValues(Map.of(
                "DISTRO_ID", id, "DISTRO_VARIANT", variant, "VERSION", version,
                "ARCH", "x86_64", "PACKAGE_MANAGER", "dnf"), "SHA256:fixture").distro();
    }
}
