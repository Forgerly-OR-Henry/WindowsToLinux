package gold.debug.windowstolinux.shared.linux.sshd.distro.registry;

import gold.debug.windowstolinux.shared.linux.error.LinuxOperationException;
import gold.debug.windowstolinux.shared.linux.protocol.ManagedHelperProtocol;
import gold.debug.windowstolinux.shared.linux.sshd.distro.apt.AptSetupRenderer;
import gold.debug.windowstolinux.shared.model.capability.LinuxCapabilityFacts;
import gold.debug.windowstolinux.shared.model.capability.ServerCapabilityFacts;
import gold.debug.windowstolinux.shared.model.health.HealthCheck;
import gold.debug.windowstolinux.shared.model.server.CpuMicroarchitectureLevel;
import gold.debug.windowstolinux.shared.model.server.LinuxDistroType;
import gold.debug.windowstolinux.shared.model.server.security.LinuxFirewallKind;
import gold.debug.windowstolinux.shared.model.server.security.LinuxFirewallState;
import gold.debug.windowstolinux.shared.model.server.security.LinuxSecurityModuleType;
import gold.debug.windowstolinux.shared.model.server.security.LinuxSecurityPosture;
import gold.debug.windowstolinux.shared.model.server.security.LinuxSecurityState;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DistributionSetupRegistryTest {
    @Test
    void rootPreparationPathDoesNotRequirePreinstalledSudoAndNonRootPathDoes() throws Exception {
        String script = renderSetup(LinuxDistroType.UBUNTU, "24.04", "amd64", "root");
        int timeout = AptSetupRenderer.LOCK_TIMEOUT_SECONDS;

        int firstAptMutation = script.indexOf("/usr/bin/apt-get -o DPkg::Lock::Timeout=" + timeout + " update");
        assertTrue(firstAptMutation > script.indexOf("test \"${ID:-}\" = 'ubuntu'"));
        assertTrue(firstAptMutation > script.indexOf("test \"${VERSION_ID:-}\" = '24.04'"));
        assertTrue(firstAptMutation > script.indexOf("test \"$(uname -m)\" = x86_64"));
        assertTrue(firstAptMutation > script.indexOf("command -v systemctl >/dev/null 2>&1"));
        assertTrue(firstAptMutation > script.indexOf("test -x /usr/bin/apt-get"));
        assertTrue(script.contains("if [ \"$(id -u)\" -eq 0 ]; then\n  elevation=root"));
        assertTrue(script.contains("elif [ -x /usr/bin/sudo ] && /usr/bin/sudo -n true"));
        assertTrue(script.contains("/usr/bin/sudo -n /usr/bin/apt-get --version >/dev/null"));
        assertTrue(script.contains("/usr/bin/sudo -n /usr/sbin/visudo -V >/dev/null"));
        assertTrue(script.contains("/usr/bin/sudo -n /usr/bin/install --version >/dev/null"));
        assertTrue(script.contains("/usr/bin/apt-get -o DPkg::Lock::Timeout=" + timeout + " update"));
        assertTrue(script.contains("/usr/bin/apt-get -o DPkg::Lock::Timeout=" + timeout
                + " install -y --no-install-recommends openjdk-21-jdk-headless maven curl sudo"));
        assertTrue(script.contains("/usr/bin/sudo -n /usr/bin/apt-get -o DPkg::Lock::Timeout=" + timeout + " update"));
        assertTrue(script.contains("/usr/bin/sudo -n /usr/bin/apt-get -o DPkg::Lock::Timeout=" + timeout
                + " install -y --no-install-recommends openjdk-21-jdk-headless maven curl sudo"));
        assertTrue(script.contains("command -v tar >/dev/null 2>&1"));
        assertTrue(script.contains("command -v gzip >/dev/null 2>&1"));
        assertTrue(script.contains("node --version | grep -Eq '^v18[.]'"));
        assertTrue(script.contains("python3.12 -m venv --help"));
        assertTrue(script.contains("dotnet-sdk-8.0"));
        assertTrue(script.contains("composer --version"));
        assertTrue(script.contains("bundle --version"));
        assertTrue(script.contains("docker info >/dev/null 2>&1"));
        assertTrue(script.contains("podman info >/dev/null 2>&1"));
        assertFalse(script.contains("command -v unzip"));
        assertTrue(script.contains("/usr/bin/install -o root -g root -m 440 \"$tmp\" '/etc/sudoers.d/windowstolinux-managed'"));
        assertTrue(script.contains("/usr/bin/install -o root -g root -m 755 \"$helper_tmp\" '/usr/local/lib/windowstolinux/managed-helper'"));
        assertTrue(script.contains("helper_probe=\"$(\"/usr/bin/sudo\" -n '/usr/local/lib/windowstolinux/managed-helper' probe)\""));
        assertTrue(script.contains("grep -qx 'PROTOCOL=3'"));
        assertFalse(script.contains("sudo -S"));
        assertFalse(script.contains("/var/lib/windowstolinux/work"));
    }

    @Test
    void rendersIndependentDistributionPreparationPathsBeforeAnyMutation() throws Exception {
        String ubuntu = renderSetup(LinuxDistroType.UBUNTU, "22.04", "amd64", "deployer");
        String debian = renderSetup(LinuxDistroType.DEBIAN, "13", "amd64", "deployer");
        String centos = renderSetup(LinuxDistroType.CENTOS_STREAM, "9", "x86_64", "deployer");
        String rocky = renderSetup(LinuxDistroType.ROCKY_LINUX, "10.2", "x86_64", "deployer");
        String alma = renderSetup(LinuxDistroType.ALMALINUX, "9.8", "x86_64", "deployer");
        String oracle = renderSetup(LinuxDistroType.ORACLE_LINUX, "10.2", "x86_64", "deployer");

        assertTrue(ubuntu.contains("test \"${VERSION_ID:-}\" = '22.04'"));
        assertTrue(debian.contains("test \"${ID:-}\" = 'debian'"));
        assertTrue(debian.contains("test \"${VERSION_ID:-}\" = '13'"));
        assertTrue(centos.contains("test \"${ID:-}\" = 'centos'"));
        assertFalse(centos.contains("VARIANT_ID"));
        assertTrue(centos.contains("test \"${VERSION_ID:-}\" = '9'"));
        assertTrue(centos.indexOf("test \"$(getenforce)\" = Enforcing")
                < centos.indexOf("/usr/bin/dnf -y install"));
        assertTrue(rocky.contains("test \"${ID:-}\" = 'rocky'"));
        assertTrue(rocky.contains("test \"${VERSION_ID:-}\" = '10.2'"));
        assertTrue(rocky.contains("x86-64-v3.*supported"));
        assertTrue(alma.contains("test \"${ID:-}\" = 'almalinux'"));
        assertTrue(oracle.contains("test \"${ID:-}\" = 'ol'"));
        assertTrue(centos.contains("/usr/bin/dnf -y install java-21-openjdk-headless maven curl sudo"));
        assertTrue(centos.contains("/usr/bin/sudo -n /usr/bin/dnf -y install java-21-openjdk-headless maven curl sudo"));
        for (String script : List.of(ubuntu, debian, centos, rocky, alma, oracle)) {
            int mutation = script.contains("/usr/bin/apt-get")
                    ? script.indexOf("/usr/bin/apt-get -o DPkg::Lock::Timeout=")
                    : script.indexOf("/usr/bin/dnf -y install");
            assertTrue(mutation > script.indexOf("security_before=\"$(security_state)\""));
            assertTrue(script.contains("test \"$security_after\" = \"$security_before\""));
            assertTrue(script.contains("*:active) test \"$firewall_after\" = \"$firewall_before\""));
            assertTrue(script.contains("nft list ruleset"));
            assertTrue(script.contains("PREPARE_CHECK_FAILED"));
            assertTrue(script.contains("prepare_check=java-command"));
            assertTrue(script.contains("PREPARE_JAVA_VERSION"));
            assertTrue(script.contains("java-21-openjdk*/bin/java"));
            assertTrue(script.contains("/usr/local/lib/windowstolinux/java-21"));
            assertFalse(script.contains("alternatives --set"));
            String preparationPath = script.substring(0, script.indexOf("\nhelper="));
            assertFalse(preparationPath.contains("setenforce"));
            assertFalse(preparationPath.contains("systemctl disable"));
            assertFalse(preparationPath.contains("systemctl stop"));
        }
    }

    @Test
    void preservesEverySupportedScriptAndUnsupportedRejectionSnapshot() throws Exception {
        for (ScriptSnapshot snapshot : List.of(
                new ScriptSnapshot(LinuxDistroType.UBUNTU, "22.04", "amd64", "2f16e5265259e5fc2e4a478bd9af87f0602c42f146274bbcbac1444ba8540382"),
                new ScriptSnapshot(LinuxDistroType.UBUNTU, "24.04", "amd64", "f242bee62df9354f580f500b2c268f78f97e7980c010a5cb9633993a7df869c0"),
                new ScriptSnapshot(LinuxDistroType.DEBIAN, "13", "amd64", "5fd30978f2af2218559716ea26b11a69567450e5472cc0a46efef54bbccf1e20"),
                new ScriptSnapshot(LinuxDistroType.CENTOS_STREAM, "9", "x86_64", "d5225893189e20843474b3b43296646c9cf78661766aa29a0487bd4cd49702cb"),
                new ScriptSnapshot(LinuxDistroType.CENTOS_STREAM, "10", "x86_64", "4e4082631d75a25ff5ae897317660f658090fec9a76bf03b256ae4daaace7ec0"),
                new ScriptSnapshot(LinuxDistroType.ROCKY_LINUX, "9.8", "x86_64", "32fd6360a7bc32e3fb07732f20057bb170f8cc7ffb11b95e798d425f20b7e3f6"),
                new ScriptSnapshot(LinuxDistroType.ROCKY_LINUX, "10.2", "x86_64", "16db1d46e26127936fbbf54522e1ff2099d685c5876b7314170e197d7f4bd556"),
                new ScriptSnapshot(LinuxDistroType.ALMALINUX, "9.8", "x86_64", "4606ed8fc74f5c008a7d1ecf8bf259fc25fdc3bf44a7044120177f1c105e72e3"),
                new ScriptSnapshot(LinuxDistroType.ALMALINUX, "10.2", "x86_64", "99d378ade86a2d1869dad3fba7918a2b627c555a7f4521717aeef4a5f4d82f87"),
                new ScriptSnapshot(LinuxDistroType.ORACLE_LINUX, "9.7", "x86_64", "a2d66a93de648db00f7ee30fa0411ecb09b3d398c7fde50642e1c2b0bdb208de"),
                new ScriptSnapshot(LinuxDistroType.ORACLE_LINUX, "10.2", "x86_64", "432630c6d5f522bc3c9980f6c7f1a68d90c75f41c233bafbe9168964da655371"))) {
            assertEquals(snapshot.sha256(), sha256(renderSetup(
                    snapshot.distro(), snapshot.version(), snapshot.packageArchitecture(), "deployer")));
        }

        for (RejectedSnapshot snapshot : List.of(
                new RejectedSnapshot(LinuxDistroType.UBUNTU, "20.04", "amd64"),
                new RejectedSnapshot(LinuxDistroType.DEBIAN, "12", "amd64"),
                new RejectedSnapshot(LinuxDistroType.CENTOS_STREAM, "8", "x86_64"),
                new RejectedSnapshot(LinuxDistroType.ROCKY_LINUX, "9.7", "x86_64"),
                new RejectedSnapshot(LinuxDistroType.ALMALINUX, "10.2", "x86_64_v2"),
                new RejectedSnapshot(LinuxDistroType.ORACLE_LINUX, "9.6", "x86_64"))) {
            LinuxOperationException failure = assertThrows(LinuxOperationException.class, () -> renderSetup(
                    snapshot.distro(), snapshot.version(), snapshot.packageArchitecture(), "deployer"));
            assertEquals("linux.error.environmentUnsupportedDistro", failure.userMessage().key());
            assertEquals("The collected distribution version is outside the managed deployment preparation matrix",
                    failure.diagnostic());
        }
    }

    @Test
    void baselineCapabilityGateRecognizesOnlyFrozenPreparedDistributionNames() {
        HealthCheck.Tcp health = new HealthCheck.Tcp(8080, 5, 1);
        for (String name : List.of("Debian GNU/Linux 13", "Rocky Linux 9.8", "Rocky Linux 10.2",
                "AlmaLinux 9.8", "AlmaLinux 10.2", "Oracle Linux Server 9.7", "Oracle Linux Server 10.2")) {
            assertTrue(capabilities(name).supportsManagedDeployment(false, health), name);
        }
        assertFalse(capabilities("Rocky Linux 9.7").supportsManagedDeployment(false, health));
        assertFalse(capabilities("Oracle Linux Server 9.6").supportsManagedDeployment(false, health));
    }

    private static ServerCapabilityFacts capabilities(String operatingSystem) {
        return new ServerCapabilityFacts(operatingSystem, "x86_64", true, true, true, true,
                true, true, true, true, ManagedHelperProtocol.VERSION, 1024L * 1024 * 1024, "test capabilities");
    }

    private static String renderSetup(LinuxDistroType distro, String version, String packageArchitecture,
                                      String username) throws LinuxOperationException {
        boolean enterprise = switch (distro) {
            case CENTOS_STREAM, ROCKY_LINUX, ALMALINUX, ORACLE_LINUX -> true;
            default -> false;
        };
        LinuxSecurityPosture security = new LinuxSecurityPosture(
                enterprise ? LinuxSecurityModuleType.SELINUX : LinuxSecurityModuleType.APPARMOR,
                enterprise ? LinuxSecurityState.ENFORCING : LinuxSecurityState.ENABLED,
                LinuxFirewallKind.NONE, LinuxFirewallState.INACTIVE);
        LinuxCapabilityFacts capabilities = new LinuxCapabilityFacts(
                distro, version, "x86_64", distro == LinuxDistroType.UBUNTU || distro == LinuxDistroType.DEBIAN ? "apt" : "dnf",
                packageArchitecture, true, false, false, false,
                Set.of(), Set.of(), false, false, Set.of(), false, Map.of(), false, false,
                CpuMicroarchitectureLevel.X86_64_V3, Set.of(), security, "test capabilities");
        return DistributionSetupRegistry.defaults().render(capabilities, username);
    }

    private static String sha256(String script) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(script.getBytes(StandardCharsets.UTF_8)));
    }

    private record ScriptSnapshot(
            LinuxDistroType distro,
            String version,
            String packageArchitecture,
            String sha256
    ) {
    }

    private record RejectedSnapshot(
            LinuxDistroType distro,
            String version,
            String packageArchitecture
    ) {
    }
}
