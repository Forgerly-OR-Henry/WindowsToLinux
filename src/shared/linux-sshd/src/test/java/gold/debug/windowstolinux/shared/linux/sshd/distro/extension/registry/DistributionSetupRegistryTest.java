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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

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
        assertTrue(script.contains("/usr/bin/sudo -n DEBIAN_FRONTEND=noninteractive NEEDRESTART_MODE=l /usr/bin/apt-get -o DPkg::Lock::Timeout=" + timeout + " update"));
        assertTrue(script.contains("/usr/bin/sudo -n DEBIAN_FRONTEND=noninteractive NEEDRESTART_MODE=l /usr/bin/apt-get -o DPkg::Lock::Timeout=" + timeout
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
        assertTrue(script.contains("grep -qx 'PROTOCOL=" + ManagedHelperProtocol.VERSION + "'"));
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

    @ParameterizedTest
    @CsvSource({"9, x86-64-v2", "10, x86-64-v3"})
    void checksCentosStreamCpuBaselineBeforePackageInstallation(String version, String requiredCpu) throws Exception {
        String script = renderSetup(LinuxDistroType.CENTOS_STREAM, version, "x86_64", "deployer");
        String cpuCheck = "\"$loader\" --help 2>/dev/null | grep -Eq '" + requiredCpu + ".*supported'";
        int cpuCheckPosition = script.indexOf(cpuCheck);

        assertTrue(cpuCheckPosition >= 0, "preparation must check the distribution CPU baseline");
        assertTrue(cpuCheckPosition < script.indexOf("/usr/bin/dnf -y install"),
                "CPU validation must precede package installation");
    }

    @Test
    void preservesEverySupportedScriptAndUnsupportedRejectionSnapshot() throws Exception {
        List<ScriptSnapshot> scripts = List.of(
                new ScriptSnapshot(LinuxDistroType.UBUNTU, "22.04", "amd64", "e726ab4118f4eea4efd6979a3ae807cd7777c9d716b7e17585d65d36ac476fda"),
                new ScriptSnapshot(LinuxDistroType.UBUNTU, "24.04", "amd64", "9d5e2a639f4558cc74662f2b5795f622cc12c7c89d6f1573ccd03738db78b1c4"),
                new ScriptSnapshot(LinuxDistroType.DEBIAN, "13", "amd64", "232967df09eba24ed9b99566c9cea9daf1e559c06915fb7dcd21a7856acf7a76"),
                new ScriptSnapshot(LinuxDistroType.CENTOS_STREAM, "9", "x86_64", "9d6622a7be770de622232010c6491bfbe79bf84d482d1f5c405d1dc45fdf1115"),
                new ScriptSnapshot(LinuxDistroType.CENTOS_STREAM, "10", "x86_64", "1571b29315ce085f8df7046040e1952fa1f56fa69b5e6630e51a8af6ea8c3e6f"),
                new ScriptSnapshot(LinuxDistroType.ROCKY_LINUX, "9.8", "x86_64", "6ca5a3d0e372951dc5a34fef18804c7496efa3a9fdb1f0894429b541ab54d66d"),
                new ScriptSnapshot(LinuxDistroType.ROCKY_LINUX, "10.2", "x86_64", "50161e3eac32b90202bf04d7eff56610e5531340ba70a2792d090e1e955f8355"),
                new ScriptSnapshot(LinuxDistroType.ALMALINUX, "9.8", "x86_64", "5aa1edb7157776dbbcadf12fec5b46730477bc034ce1b6ae9838afd73ebcf7eb"),
                new ScriptSnapshot(LinuxDistroType.ALMALINUX, "10.2", "x86_64", "73580439c75e3753c2abf903918646e3815b76a59b7866a852ab13dc123d839b"),
                new ScriptSnapshot(LinuxDistroType.ORACLE_LINUX, "9.7", "x86_64", "ac5ed3ed276357a77fb760897df81ff1b3c8120aa85aa392b2e9ac1601d558de"),
                new ScriptSnapshot(LinuxDistroType.ORACLE_LINUX, "10.2", "x86_64", "bb4610139d4ee51eaaa7343acddc9186944426c157b012b336119268a886e8cd"));
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
            assertEquals("linux.error.environmentUnsupportedDistro", failure.failure().userMessage().key());
            assertEquals("The collected distribution version is outside the managed deployment preparation matrix",
                    failure.failure().diagnostic());
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
