package gold.debug.windowstolinux.shared.linux.sshd.distro.extension.registry;

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
import java.util.ArrayList;
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
        assertTrue(centos.contains("/usr/bin/dnf -y install java-21-openjdk-devel maven curl sudo"));
        assertTrue(centos.contains("/usr/bin/sudo -n /usr/bin/dnf -y install java-21-openjdk-devel maven curl sudo"));
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
            assertTrue(script.contains("command -v javac >/dev/null 2>&1"));
            assertTrue(script.contains("command -v cmake >/dev/null 2>&1"));
            assertTrue(script.contains("command -v ninja >/dev/null 2>&1"));
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
        List<ScriptSnapshot> scripts = List.of(
                new ScriptSnapshot(LinuxDistroType.UBUNTU, "22.04", "amd64", "e4747ca6e912f9e8c819eba6d35d1f10e4b5ef0d3beee847992e6773879badb8"),
                new ScriptSnapshot(LinuxDistroType.UBUNTU, "24.04", "amd64", "7ef14cdfebb6b3d2ce477eece0917ceb469ef4af4f12414508acbb729e3b2a62"),
                new ScriptSnapshot(LinuxDistroType.DEBIAN, "13", "amd64", "9687745e197db30fb294a115795b72a08956b225d5677534f7ce6387be2be7b0"),
                new ScriptSnapshot(LinuxDistroType.CENTOS_STREAM, "9", "x86_64", "6e03f04efba3146b7478bb5b0fb00fc521bd3bf957c29208a8c30cf06dcda9e7"),
                new ScriptSnapshot(LinuxDistroType.CENTOS_STREAM, "10", "x86_64", "ec2c22a4ec0107633fa970a8794cec43859e41828be0605d88e8ce1e329770f7"),
                new ScriptSnapshot(LinuxDistroType.ROCKY_LINUX, "9.8", "x86_64", "94431740d5a997fc64b549b39ccf2370237ff39063872f1ae3e240d84b67c680"),
                new ScriptSnapshot(LinuxDistroType.ROCKY_LINUX, "10.2", "x86_64", "e82f674da0d1227d2678f9cbfe84ee3ee61494067ed33cf44e64f5fc98cbf6a2"),
                new ScriptSnapshot(LinuxDistroType.ALMALINUX, "9.8", "x86_64", "7cea8dd76b68931de203c774eb0681fe1774550c80c914a7e417c4adef90c295"),
                new ScriptSnapshot(LinuxDistroType.ALMALINUX, "10.2", "x86_64", "aa0467f5e31b523c7fb52302ebabfcca0012ff4c21490cff965c1b41b1d5d28b"),
                new ScriptSnapshot(LinuxDistroType.ORACLE_LINUX, "9.7", "x86_64", "e454609410ea850953a0fab2ef7c7e11e0d98f3031a536b9ddeb10b009523931"),
                new ScriptSnapshot(LinuxDistroType.ORACLE_LINUX, "10.2", "x86_64", "60a66e18b1e960a2b99478b46b95f79cfd452b514a65015f4021ed6424dccae5"));
        List<String> changed = new ArrayList<>();
        for (ScriptSnapshot snapshot : scripts) {
            String actual = sha256(renderSetup(
                    snapshot.distro(), snapshot.version(), snapshot.packageArchitecture(), "deployer"));
            if (!snapshot.sha256().equals(actual)) {
                changed.add(snapshot.distro() + " " + snapshot.version() + "=" + actual);
            }
        }
        assertTrue(changed.isEmpty(), () -> "setup snapshots changed:\n" + String.join("\n", changed));

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
                Set.of(), Set.of(), false, false, Set.of(), false, Map.of(), Map.of(), false, false,
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
