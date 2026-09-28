package gold.debug.windowstolinux.mod.deployment.linux;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import gold.debug.windowstolinux.mod.deployment.linux.DeploymentPlatformCapabilityProbe;
import gold.debug.windowstolinux.mod.deployment.service.core.model.DeploymentProjectKind;
import gold.debug.windowstolinux.mod.deployment.service.core.model.capability.DeploymentEcosystemToolType;
import gold.debug.windowstolinux.mod.deployment.service.core.model.server.DeploymentCpuMicroarchitectureLevel;
import gold.debug.windowstolinux.mod.deployment.service.core.model.server.LinuxDistroType;
import gold.debug.windowstolinux.mod.deployment.service.core.model.server.security.DeploymentLinuxFirewallKind;
import gold.debug.windowstolinux.mod.deployment.service.core.model.server.security.DeploymentLinuxFirewallState;
import gold.debug.windowstolinux.mod.deployment.service.core.model.server.security.DeploymentLinuxSecurityModuleType;
import gold.debug.windowstolinux.mod.deployment.service.core.model.server.security.DeploymentLinuxSecurityState;
import org.junit.jupiter.api.Test;

class DeploymentPlatformCapabilityParserTest {
    @Test
    void readsUfwRulesInsteadOfTheOneshotServiceState() throws Exception {
        String bash = System.getProperty("managed.test.bash", "/bin/bash");
        org.junit.jupiter.api.Assumptions.assumeTrue(java.nio.file.Files.isExecutable(java.nio.file.Path.of(bash)));
        for (String scenario : java.util.List.of("active", "inactive", "denied", "unexpected", "sudo")) {
            String script = """
                    set -eu
                    export LC_ALL=C
                    command() { [ "$1" = -v ] && [ "$2" = ufw ]; }
                    systemctl() { return 0; }
                    ufw() {
                      case "$scenario" in
                        denied|sudo) return 1 ;;
                        unexpected) printf 'unrecognized output\\n' ;;
                        *) printf 'Status: %s\\n' "$scenario" ;;
                      esac
                    }
                    sudo() { [ "$scenario" = sudo ] && printf 'Status: inactive\\n'; }
                    """ + "scenario='" + scenario + "'\n" + DeploymentPlatformCapabilityProbe.firewallProbe();
            Process process = new ProcessBuilder(bash, "-s").redirectErrorStream(true).start();
            try {
                try (var input = process.getOutputStream()) {
                    input.write(script.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
                assertTrue(process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS));
                String output = new String(process.getInputStream().readAllBytes(),
                        java.nio.charset.StandardCharsets.UTF_8);
                assertEquals(0, process.exitValue(), output);
                String expected = switch (scenario) {
                    case "active" -> "active";
                    case "inactive", "sudo" -> "inactive";
                    default -> "unknown";
                };
                assertTrue(output.contains("FIREWALL=ufw\n"), output);
                assertTrue(output.contains("FIREWALL_STATE=" + expected + "\n"), output);
            } finally {
                if (process.isAlive())
                    process.destroyForcibly().waitFor();
            }
        }
    }

    @Test
    void parsesOnlyFixedNonSecretProbeFacts() {
        var capabilities = DeploymentPlatformCapabilityParser.fromValues(Map.ofEntries(Map.entry("DISTRO_ID", "centos"),
                Map.entry("DISTRO_VARIANT", "stream"), Map.entry("VERSION", "10"), Map.entry("ARCH", "x86_64"),
                Map.entry("PACKAGE_MANAGER", "dnf"), Map.entry("PACKAGE_ARCH", "x86_64"), Map.entry("SYSTEMD", "1"),
                Map.entry("DOCKER_CLIENT", "0"), Map.entry("DOCKER_OPERATIONAL", "0"), Map.entry("PODMAN_CLIENT", "1"),
                Map.entry("PODMAN_OPERATIONAL", "1"), Map.entry("PODMAN_QUADLET", "1"), Map.entry("JAVA_MAJORS", "21"),
                Map.entry("NODE_MAJORS", "22"), Map.entry("NPM", "1"), Map.entry("PYTHON3", "1"),
                Map.entry("PYTHON_VERSIONS", "3.11,3.12"), Map.entry("SERVICE_GO", "1.24"),
                Map.entry("SERVICE_RUST", "1.89.0"), Map.entry("SERVICE_DOTNET", "8.0.408"),
                Map.entry("SERVICE_KOTLIN", "21"), Map.entry("SERVICE_PHP", "8.3"), Map.entry("SERVICE_RUBY", "3.3.5"),
                Map.entry("TOOL_JAVAC", "21.0.8"), Map.entry("TOOL_JAR", "21.0.8"), Map.entry("TOOL_PNPM", "10.15.1"),
                Map.entry("TOOL_UV", "0.8.12"), Map.entry("TOOL_KOTLINC", "2.0.21"), Map.entry("TOOL_CMAKE", "3.31.6"),
                Map.entry("TOOL_NINJA", "1.12.1"), Map.entry("TOOL_C_COMPILER", "14.2.1"),
                Map.entry("TOOL_CPP_COMPILER", "14.2.1"), Map.entry("CPU_LEVEL", "x86-64-v3"),
                Map.entry("CPU_FLAGS", "sse4_2,popcnt"), Map.entry("SECURITY_MODULE", "selinux"),
                Map.entry("SECURITY_STATE", "enforcing"), Map.entry("FIREWALL", "firewalld"),
                Map.entry("FIREWALL_STATE", "active")), "SHA256:host");

        assertEquals(LinuxDistroType.CENTOS_STREAM, capabilities.distro());
        assertTrue(capabilities.podmanQuadletAvailable());
        assertTrue(capabilities.podmanOperational());
        assertEquals(java.util.Set.of(21), capabilities.javaMajorVersions());
        assertEquals(java.util.Set.of(22), capabilities.nodeMajorVersions());
        assertEquals(java.util.Set.of("3.11", "3.12"), capabilities.pythonVersions());
        assertTrue(capabilities.python3Available());
        assertTrue(capabilities.cpuFlags().contains("sse4_2"));
        assertEquals("x86_64", capabilities.packageArchitecture());
        assertEquals(DeploymentCpuMicroarchitectureLevel.X86_64_V3, capabilities.cpuMicroarchitecture());
        assertEquals(DeploymentLinuxSecurityModuleType.SELINUX, capabilities.securityPosture().module());
        assertEquals(DeploymentLinuxSecurityState.ENFORCING, capabilities.securityPosture().state());
        assertEquals(DeploymentLinuxFirewallKind.FIREWALLD, capabilities.securityPosture().firewall());
        assertEquals(DeploymentLinuxFirewallState.ACTIVE, capabilities.securityPosture().firewallState());
        assertEquals(java.util.Set.of("1.24"),
                capabilities.serviceRuntimeVersions().get(DeploymentProjectKind.GO_SERVICE));
        assertEquals(java.util.Set.of("3.3.5"),
                capabilities.serviceRuntimeVersions().get(DeploymentProjectKind.RUBY_SERVICE));
        assertEquals(java.util.Set.of("21.0.8"),
                capabilities.ecosystemToolVersions().get(DeploymentEcosystemToolType.JAVAC));
        assertEquals(java.util.Set.of("10.15.1"),
                capabilities.ecosystemToolVersions().get(DeploymentEcosystemToolType.PNPM));
        assertEquals(java.util.Set.of("2.0.21"),
                capabilities.ecosystemToolVersions().get(DeploymentEcosystemToolType.KOTLINC));
        assertEquals(java.util.Set.of("1.12.1"),
                capabilities.ecosystemToolVersions().get(DeploymentEcosystemToolType.NINJA));
        assertEquals(java.util.Set.of("14.2.1"),
                capabilities.ecosystemToolVersions().get(DeploymentEcosystemToolType.CPP_COMPILER));
        assertTrue(DeploymentPlatformCapabilityProbe.render().contains("PODMAN_QUADLET"));
        assertTrue(DeploymentPlatformCapabilityProbe.render().contains("SERVICE_DOTNET"));
        assertTrue(DeploymentPlatformCapabilityProbe.render().contains("TOOL_KOTLINC"));
        assertTrue(DeploymentPlatformCapabilityProbe.render().contains("TOOL_CMAKE"));
        assertTrue(DeploymentPlatformCapabilityProbe.render().contains("TOOL_NINJA"));
        assertTrue(DeploymentPlatformCapabilityProbe.render().contains("printf(\"%d.%d\""));
        assertTrue(!DeploymentPlatformCapabilityProbe.render().contains("printf(\"%%d.%%d\""));
        assertTrue(DeploymentPlatformCapabilityProbe.render().contains("SECURITY_MODULE"));
        assertTrue(DeploymentPlatformCapabilityProbe.render().contains("FIREWALL_STATE"));
        assertTrue(DeploymentPlatformCapabilityProbe.render().contains("nft list ruleset"));
        assertTrue(DeploymentPlatformCapabilityProbe.render()
                .contains("managed_java='/usr/local/lib/windowstolinux/java-21'"));
        assertTrue(DeploymentPlatformCapabilityProbe.render().contains("\"$managed_java\" -XshowSettings:properties"));
        assertTrue(DeploymentPlatformCapabilityProbe.render().contains("PACKAGE_ARCH"));
        assertTrue(DeploymentPlatformCapabilityProbe.render().contains("CPU_LEVEL"));
        assertTrue(!DeploymentPlatformCapabilityProbe.render().contains("setenforce"));
        assertTrue(!DeploymentPlatformCapabilityProbe.render().contains("systemctl stop"));
        assertTrue(!DeploymentPlatformCapabilityProbe.render().contains("systemctl disable"));
    }

    @Test
    void parsesAnObservedInactiveNftablesRuleset() {
        var capabilities = DeploymentPlatformCapabilityParser
                .fromValues(
                        Map.of("DISTRO_ID", "centos", "DISTRO_VARIANT", "", "VERSION", "9", "ARCH", "x86_64",
                                "PACKAGE_MANAGER", "dnf", "FIREWALL", "nftables", "FIREWALL_STATE", "inactive"),
                        "SHA256:fixture");

        assertEquals(DeploymentLinuxFirewallKind.NFTABLES, capabilities.securityPosture().firewall());
        assertEquals(DeploymentLinuxFirewallState.INACTIVE, capabilities.securityPosture().firewallState());
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
        return DeploymentPlatformCapabilityParser.fromValues(Map.of("DISTRO_ID", id, "DISTRO_VARIANT", variant,
                "VERSION", version, "ARCH", "x86_64", "PACKAGE_MANAGER", "dnf"), "SHA256:fixture").distro();
    }
}
