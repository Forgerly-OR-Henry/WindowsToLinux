package gold.debug.windowstolinux.shared.linux.sshd.distro.extension.registry;

import gold.debug.windowstolinux.shared.linux.error.LinuxOperationException;
import gold.debug.windowstolinux.shared.linux.protocol.ManagedHelperProtocol;
import gold.debug.windowstolinux.shared.linux.sshd.distro.apt.AptSetupRenderer;
import gold.debug.windowstolinux.shared.linux.sshd.distro.DistributionSetupRenderer;
import gold.debug.windowstolinux.shared.linux.sshd.distro.apt.DebianSetupRenderer;
import gold.debug.windowstolinux.shared.linux.sshd.distro.apt.UbuntuSetupRenderer;
import gold.debug.windowstolinux.shared.linux.sshd.distro.dnf.AlmaLinuxSetupRenderer;
import gold.debug.windowstolinux.shared.linux.sshd.distro.dnf.CentosStreamSetupRenderer;
import gold.debug.windowstolinux.shared.linux.sshd.distro.dnf.OracleLinuxSetupRenderer;
import gold.debug.windowstolinux.shared.linux.sshd.distro.dnf.RockyLinuxSetupRenderer;
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
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DistributionSetupRegistryTest {
    @Test
    void registersExactlyTheSixNamedDistributionImplementations() {
        assertEquals(Map.of(
                LinuxDistroType.UBUNTU, UbuntuSetupRenderer.class,
                LinuxDistroType.DEBIAN, DebianSetupRenderer.class,
                LinuxDistroType.CENTOS_STREAM, CentosStreamSetupRenderer.class,
                LinuxDistroType.ROCKY_LINUX, RockyLinuxSetupRenderer.class,
                LinuxDistroType.ALMALINUX, AlmaLinuxSetupRenderer.class,
                LinuxDistroType.ORACLE_LINUX, OracleLinuxSetupRenderer.class),
                DistributionSetupCatalog.defaults().stream().collect(Collectors.toMap(
                        DistributionSetupRenderer::distro, Object::getClass)));
    }

    @Test
    void everyEnterpriseImplementationRejectsMissingEnforcingEvidence() {
        for (DistributionSetupRenderer renderer : List.of(new CentosStreamSetupRenderer(),
                new RockyLinuxSetupRenderer(), new AlmaLinuxSetupRenderer(), new OracleLinuxSetupRenderer())) {
            for (LinuxSecurityPosture security : List.of(
                    new LinuxSecurityPosture(LinuxSecurityModuleType.SELINUX, LinuxSecurityState.PERMISSIVE,
                            LinuxFirewallKind.NONE, LinuxFirewallState.INACTIVE),
                    new LinuxSecurityPosture(LinuxSecurityModuleType.NONE, LinuxSecurityState.DISABLED,
                            LinuxFirewallKind.NONE, LinuxFirewallState.INACTIVE))) {
                String version = renderer.distro() == LinuxDistroType.CENTOS_STREAM ? "10" : "10.2";
                LinuxCapabilityFacts facts = facts(renderer.distro(), version, "x86_64", security);
                LinuxOperationException failure = assertThrows(LinuxOperationException.class,
                        () -> renderer.render(facts, "deployer"));
                assertEquals("Enterprise Linux automatic preparation requires collected SELinux enforcing evidence",
                        failure.failure().diagnostic());
            }
        }
    }

    @Test
    void rootPreparationRejectsOtherIdentitiesBeforeInstallation() throws Exception {
        String script = renderSetup(LinuxDistroType.UBUNTU, "24.04", "amd64", "root");
        int timeout = AptSetupRenderer.LOCK_TIMEOUT_SECONDS;

        int firstAptMutation = script.indexOf("/usr/bin/apt-get -o DPkg::Lock::Timeout=" + timeout + " update");
        assertTrue(firstAptMutation > script.indexOf("test \"${ID:-}\" = 'ubuntu'"));
        assertTrue(firstAptMutation > script.indexOf("test \"${VERSION_ID:-}\" = '24.04'"));
        assertTrue(firstAptMutation > script.indexOf("test \"$(uname -m)\" = x86_64"));
        assertTrue(firstAptMutation > script.indexOf("command -v systemctl >/dev/null 2>&1"));
        assertTrue(firstAptMutation > script.indexOf("test -x /usr/bin/apt-get"));
        assertTrue(script.contains("root-management-required"));
        assertTrue(script.indexOf("root-management-required") < firstAptMutation);
        assertFalse(script.contains("sudo -n"));
        assertTrue(script.contains("/usr/bin/apt-get -o DPkg::Lock::Timeout=" + timeout + " update"));
        assertTrue(script.contains("/usr/bin/apt-get -o DPkg::Lock::Timeout=" + timeout
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
        assertFalse(script.contains("NOPASSWD"));
        assertTrue(script.contains("/usr/bin/install -o root -g root -m 755 \"$helper_tmp\" '/usr/local/lib/windowstolinux/managed-helper'"));
        assertTrue(script.contains("helper_probe=\"$('/usr/local/lib/windowstolinux/managed-helper' probe)\""));
        assertTrue(script.contains("grep -qx 'PROTOCOL=" + ManagedHelperProtocol.VERSION + "'"));
        assertFalse(script.contains("sudo -S"));
        assertFalse(script.substring(0, script.indexOf("\nhelper_tmp=")).contains("candidate-create"));
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
                < centos.indexOf("/usr/bin/dnf --enablerepo=crb -y install"));
        assertTrue(rocky.contains("test \"${ID:-}\" = 'rocky'"));
        assertTrue(rocky.contains("test \"${VERSION_ID:-}\" = '10.2'"));
        assertTrue(rocky.contains("x86-64-v3.*supported"));
        assertTrue(alma.contains("test \"${ID:-}\" = 'almalinux'"));
        assertTrue(oracle.contains("test \"${ID:-}\" = 'ol'"));
        assertTrue(centos.contains("/usr/bin/dnf --enablerepo=crb -y install java-21-openjdk-devel maven curl sudo"));
        assertFalse(centos.contains("sudo -n"));
        for (String script : List.of(ubuntu, debian, centos, rocky, alma, oracle)) {
            String preparationPath = script.substring(0, script.indexOf("\nhelper_tmp="));
            int mutation = preparationPath.contains("/usr/bin/apt-get")
                    ? script.indexOf("/usr/bin/apt-get -o DPkg::Lock::Timeout=")
                    : script.indexOf("/usr/bin/dnf ");
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
        assertTrue(cpuCheckPosition < script.indexOf("/usr/bin/dnf --enablerepo=crb -y install"),
                "CPU validation must precede package installation");
        assertFalse(script.contains("config-manager"), "CRB must not be permanently enabled");
    }

    @Test
    void preservesEverySupportedScriptAndUnsupportedRejectionSnapshot() throws Exception {
        List<ScriptSnapshot> scripts = List.of(
                new ScriptSnapshot(LinuxDistroType.UBUNTU, "22.04", "amd64", "a354930ecb4be13d5f3402491dec3b48ce1eeecc20caf8e68d53cb1245932ffa"),
                new ScriptSnapshot(LinuxDistroType.UBUNTU, "24.04", "amd64", "85a9e5a1c6893d5e5c76dc4284b81aff07178842dee1f4b0e6b1ca6467ece274"),
                new ScriptSnapshot(LinuxDistroType.DEBIAN, "13", "amd64", "ab68ca1ae738534d9bede17e6b3353c7fc908ce1e2d828d3fc2b032a12ab159b"),
                new ScriptSnapshot(LinuxDistroType.CENTOS_STREAM, "9", "x86_64", "f6b7e5129d601c75d86d7b2482940582dc4ddd8a938472227d9929a05b7e2aee"),
                new ScriptSnapshot(LinuxDistroType.CENTOS_STREAM, "10", "x86_64", "1689fad01bbbe5882bfd4ef80d4f50cd03744716e6500b722a9dc52a5bbeaeab"),
                new ScriptSnapshot(LinuxDistroType.ROCKY_LINUX, "9.8", "x86_64", "d619496855288b01157e4c5ae014bad8468a38b9a5034b4b944f271e70ee83dd"),
                new ScriptSnapshot(LinuxDistroType.ROCKY_LINUX, "10.2", "x86_64", "418cd55645de429c0c3957c1775cbe1d2c5080b0e8a6958b606891e5cf4f57dc"),
                new ScriptSnapshot(LinuxDistroType.ALMALINUX, "9.8", "x86_64", "3f68765368f1ae659ddd9707c05cc609a09c29370d1220d1929f1d8630fa2321"),
                new ScriptSnapshot(LinuxDistroType.ALMALINUX, "10.2", "x86_64", "1bad053aa23a76b1d7dd54e9614725a938ec86343cee799bb9b4de618fb6a826"),
                new ScriptSnapshot(LinuxDistroType.ORACLE_LINUX, "9.7", "x86_64", "cff40e42dff60b1bc7149b50a26dacc70b8448a497bf2d617183ef53daa57123"),
                new ScriptSnapshot(LinuxDistroType.ORACLE_LINUX, "10.2", "x86_64", "659151d43231ade75964ab04128153b529cb465581d04af72e039f6b9e256e1b"));
        List<String> changed = new ArrayList<>();
        for (ScriptSnapshot snapshot : scripts) {
            String actual = sha256(renderSetup(
                    snapshot.distro(), snapshot.version(), snapshot.packageArchitecture(), "root"));
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
                    snapshot.distro(), snapshot.version(), snapshot.packageArchitecture(), "root"));
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
        return DistributionSetupRegistry.defaults().render(facts(distro, version, packageArchitecture, security), username);
    }

    private static LinuxCapabilityFacts facts(LinuxDistroType distro, String version, String packageArchitecture,
                                               LinuxSecurityPosture security) {
        return new LinuxCapabilityFacts(
                distro, version, "x86_64", distro == LinuxDistroType.UBUNTU || distro == LinuxDistroType.DEBIAN ? "apt" : "dnf",
                packageArchitecture, true, false, false, false,
                Set.of(), Set.of(), false, false, Set.of(), false, Map.of(), Map.of(), false, false,
                CpuMicroarchitectureLevel.X86_64_V3, Set.of(), security, "test capabilities");
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
