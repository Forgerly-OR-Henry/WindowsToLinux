package gold.debug.windowstolinux.shared.linux.sshd.connection;

import gold.debug.windowstolinux.shared.linux.sshd.command.SshCommandExecutor;

import gold.debug.windowstolinux.shared.linux.connection.HostKeyDecision;
import gold.debug.windowstolinux.shared.linux.error.LinuxOperationException;
import gold.debug.windowstolinux.shared.linux.connection.SshCredential;
import gold.debug.windowstolinux.shared.linux.connection.SshEndpoint;
import gold.debug.windowstolinux.shared.linux.protocol.ManagedHelperProtocol;
import gold.debug.windowstolinux.shared.linux.sshd.distro.setup.apt.ubuntu.UbuntuSetup;
import gold.debug.windowstolinux.shared.linux.sshd.distro.setup.dnf.almalinux.AlmaLinuxSetup;
import gold.debug.windowstolinux.shared.linux.sshd.distro.setup.dnf.centosstream.CentosStreamSetup;
import gold.debug.windowstolinux.shared.linux.sshd.distro.setup.apt.debian.DebianSetup;
import gold.debug.windowstolinux.shared.linux.sshd.distro.setup.dnf.oraclelinux.OracleLinuxSetup;
import gold.debug.windowstolinux.shared.linux.sshd.distro.setup.dnf.rocky.RockyLinuxSetup;
import gold.debug.windowstolinux.shared.linux.sshd.protocol.helper.ManagedHelperBundle;
import gold.debug.windowstolinux.shared.linux.sshd.runtime.systemd.SystemdUnitRenderer;
import gold.debug.windowstolinux.shared.linux.transfer.RemoteWorkspace;

import gold.debug.windowstolinux.shared.model.managed.ManagedApplication;
import gold.debug.windowstolinux.shared.model.server.ServerIdentity;
import gold.debug.windowstolinux.shared.model.deployment.BuildLimits;
import gold.debug.windowstolinux.shared.model.server.ServerCapabilities;
import gold.debug.windowstolinux.shared.model.health.HealthCheck;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.common.keyprovider.KeyIdentityProvider;
import org.apache.sshd.core.CoreModuleProperties;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.time.Duration;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SshdLinuxGatewayTest {
    @Test
    void rendersTheCanonicalUnitWithExactlyOneTrailingNewline() {
        ManagedApplication application = ManagedApplication.forManaged("managed-hello",
                new ServerIdentity("ubuntu-managed", "192.0.2.1", 22, "SHA256:abc123456789"),
                "a".repeat(64));

        String unit = SystemdUnitRenderer.render("ubuntu", application);

        assertTrue(unit.endsWith("WantedBy=multi-user.target\n"));
        assertFalse(unit.endsWith("\n\n"));
        assertEquals("""
                [Unit]
                Description=WindowsToLinux managed managed-hello
                After=network.target

                [Service]
                Type=simple
                User=ubuntu
                WorkingDirectory=/var/lib/windowstolinux/apps/managed-hello/current
                ExecStart=/usr/local/lib/windowstolinux/java-21 -jar /var/lib/windowstolinux/apps/managed-hello/current/app.jar
                Restart=on-failure
                RestartSec=5
                SuccessExitStatus=143

                [Install]
                WantedBy=multi-user.target
                """, unit);
    }

    @Test
    void keepsBothSanitizedOutputChannelsInAFailedCommandEvidence() {
        SshCommandExecutor.CommandResult result = new SshCommandExecutor.CommandResult(
                false, false, "stage output", "stage output", "diagnostic error", 1);

        assertEquals("exitCode=1, error=diagnostic error, output=stage output", result.failureEvidence());
    }

    @Test
    void requiresTarForManagedCapabilities() {
        HealthCheck.Tcp health = new HealthCheck.Tcp(8080, 5, 1);

        assertTrue(capabilities(true).supportsManagedDeployment(false, health));
        assertFalse(capabilities(false).supportsManagedDeployment(false, health));
    }

    @Test
    void transportFailureDoesNotExposeOrRetainThePassword() {
        String secret = "managed-deployment-test-secret";
        SshCredential.Password credential = new SshCredential.Password(secret.toCharArray());

        LinuxOperationException failure = assertThrows(LinuxOperationException.class, () ->
                new SshdLinuxGateway().connect(
                        new SshEndpoint("local-test", "127.0.0.1", 1, "nobody"), credential,
                        (endpoint, fingerprint) -> HostKeyDecision.REJECT
                )
        );

        assertEquals("linux.error.connectionFailed", failure.userMessage().key());
        assertFalse(failure.diagnostic().contains(secret));
        char[] remaining = credential.copy();
        try {
            for (char value : remaining) {
                assertEquals('\0', value);
            }
        } finally {
            Arrays.fill(remaining, '\0');
        }
    }

    @Test
    void retriesOnlyTimeoutShapedConnectionFailures() {
        LinuxOperationException timeout = LinuxOperationException.localized("linux.error.authenticationFailed",
                "authentication timed out", new TimeoutException("timed out"));
        LinuxOperationException rejected = LinuxOperationException.localized("linux.error.authenticationFailed",
                "authentication rejected");

        assertTrue(SshdLinuxGateway.isTransientConnectionFailure(timeout));
        assertFalse(SshdLinuxGateway.isTransientConnectionFailure(rejected));
    }

    @Test
    void recognizesOnlyTimeoutShapedTransportFailures() {
        LinuxOperationException timeout = LinuxOperationException.localized("test", "fixture",
                new java.util.concurrent.TimeoutException("fixture"));
        LinuxOperationException other = LinuxOperationException.localized("test", "fixture",
                new IllegalStateException("fixture"));

        assertTrue(SshCommandExecutor.isTransientTransportFailure(timeout));
        assertFalse(SshCommandExecutor.isTransientTransportFailure(other));
    }

    @Test
    void passwordAuthenticationDoesNotOfferAmbientDesktopKeysFirst() throws Exception {
        SshCredential.Password credential = new SshCredential.Password("managed-deployment-test-secret".toCharArray());
        SshClient client = SshdLinuxGateway.credentialScopedClient(credential);
        try {
            assertEquals(List.of("password", "keyboard-interactive"),
                    client.getUserAuthFactories().stream().map(factory -> factory.getName()).toList());
            assertSame(KeyIdentityProvider.EMPTY_KEYS_PROVIDER, client.getKeyIdentityProvider());
            assertEquals(Duration.ofSeconds(30), CoreModuleProperties.HEARTBEAT_INTERVAL.getRequired(client));
            assertEquals(3, CoreModuleProperties.HEARTBEAT_NO_REPLY_MAX.getRequired(client));
        } finally {
            credential.clear();
            client.close(true);
        }
    }

    @Test
    void sudoersGrantsOnlyTheConstrainedRootOwnedHelper() {
        assertEquals(List.of(
                "openjdk-21-jdk-headless", "maven", "curl", "sudo", "tar", "gzip", "iproute2", "coreutils",
                        "util-linux", "findutils", "gawk", "nodejs", "npm", "python3", "python3-venv", "python3-pip", "docker.io", "podman"
                ), UbuntuSetup.PACKAGES);
        assertEquals("""
                # Managed by WindowsToLinux managed deployment; only the constrained helper is granted.
                deployer ALL=(root) NOPASSWD: /usr/local/lib/windowstolinux/managed-helper
                """, UbuntuSetup.renderSudoers("deployer"));
        String sudoers = UbuntuSetup.renderSudoers("deployer");
        for (String unsafeBinary : List.of("/usr/bin/install", "/usr/bin/tee", "/usr/bin/systemctl", "/usr/bin/ln", "/usr/bin/rm", "/usr/bin/cp")) {
            assertFalse(sudoers.contains(unsafeBinary));
        }
        assertThrows(IllegalArgumentException.class, () -> UbuntuSetup.renderSudoers("root;evil"));
    }

    @Test
    void helperAcceptsOnlyFixedHighLevelVerbsAndDerivedControlledPaths() {
        String helper = ManagedHelperBundle.renderScript();

        assertTrue(helper.contains("candidate-create) create_candidate \"$@\""));
        assertTrue(helper.contains("rollback-deployment) rollback_deployment \"$@\""));
        assertFalse(helper.contains("rollback-previous)"));
        assertTrue(helper.contains("inspect-runtime) inspect_managed_runtime \"$@\""));
        assertTrue(helper.contains("case \"$action\" in\n    start|stop|restart|enable|disable)"));
        assertTrue(helper.contains("require_candidate \"$app\" \"$candidate_id\""));
        assertTrue(helper.contains("candidate=\"$(candidate_root \"$candidate_id\")\""));
        assertTrue(helper.contains("seal_candidate_artifact"));
        assertTrue(helper.indexOf("printf 'SNAPSHOT_TOKEN=%s\\n' \"$token\"")
                        < helper.indexOf("printf 'PREVIOUS=1\\n'"),
                "snapshot protocol must emit the token before the previous-release marker");
        assertTrue(helper.contains("install -o root -g root -m 555 -- \"$sealed_artifact\" \"$release/app.jar\""));
        assertTrue(helper.contains("for attempt in {1..20}; do"));
        assertTrue(helper.contains("systemctl show --value --property MainPID"));
        assertTrue(helper.contains("reject stop-incomplete"));
        assertFalse(helper.contains("eval "));
        assertFalse(helper.contains("exec \"$@\""));
        assertFalse(helper.contains("/bin/bash -c \"$@\""));
        assertFalse(helper.contains("\"$engine\" inspect"));
        assertFalse(helper.contains("Substring"));
        assertFalse(helper.contains("FullyQualifiedErrorId"));
        assertTrue(helper.stripTrailing().endsWith("esac"), "helper resource must end at the allowlisted verb switch");
        assertFalse(helper.contains("sudo -n"));
    }

    @Test
    void rootPreparationPathDoesNotRequirePreinstalledSudoAndNonRootPathDoes() {
        String script = UbuntuSetup.renderScript("root");

        int firstAptMutation = script.indexOf("/usr/bin/apt-get -o DPkg::Lock::Timeout=" + UbuntuSetup.APT_LOCK_TIMEOUT_SECONDS + " update");
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
        assertTrue(script.contains("test -x /usr/bin/apt-get"));
        assertTrue(script.contains("/usr/bin/apt-get -o DPkg::Lock::Timeout=" + UbuntuSetup.APT_LOCK_TIMEOUT_SECONDS + " update"));
        assertTrue(script.contains("/usr/bin/apt-get -o DPkg::Lock::Timeout=" + UbuntuSetup.APT_LOCK_TIMEOUT_SECONDS + " install -y --no-install-recommends openjdk-21-jdk-headless maven curl sudo"));
        assertTrue(script.contains("/usr/bin/sudo -n /usr/bin/apt-get -o DPkg::Lock::Timeout=" + UbuntuSetup.APT_LOCK_TIMEOUT_SECONDS + " update"));
        assertTrue(script.contains("/usr/bin/sudo -n /usr/bin/apt-get -o DPkg::Lock::Timeout=" + UbuntuSetup.APT_LOCK_TIMEOUT_SECONDS + " install -y --no-install-recommends openjdk-21-jdk-headless maven curl sudo"));
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
    void rendersIndependentDistributionPreparationPathsBeforeAnyMutation() {
        String ubuntu = UbuntuSetup.renderScript("deployer", "22.04");
        String debian = DebianSetup.renderScript("deployer", "13");
        String centos = CentosStreamSetup.renderScript("deployer", "9");
        String rocky = RockyLinuxSetup.renderScript("deployer", "10.2");
        String alma = AlmaLinuxSetup.renderScript("deployer", "9.8", "x86_64");
        String oracle = OracleLinuxSetup.renderScript("deployer", "10.2");

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
        assertThrows(IllegalArgumentException.class, () -> UbuntuSetup.renderScript("deployer", "20.04"));
        assertThrows(IllegalArgumentException.class, () -> DebianSetup.renderScript("deployer", "12"));
        assertThrows(IllegalArgumentException.class, () -> CentosStreamSetup.renderScript("deployer", "8"));
        assertThrows(IllegalArgumentException.class, () -> RockyLinuxSetup.renderScript("deployer", "9.7"));
        assertThrows(IllegalArgumentException.class,
                () -> AlmaLinuxSetup.renderScript("deployer", "10.2", "x86_64_v2"));
        assertThrows(IllegalArgumentException.class,
                () -> OracleLinuxSetup.renderScript("deployer", "9.6"));
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

    private static ServerCapabilities capabilities(boolean tarAvailable) {
        return new ServerCapabilities("Ubuntu 24.04.1 LTS", "x86_64", true, true, true, tarAvailable,
                true, true, true, true, ManagedHelperProtocol.VERSION, 1024L * 1024 * 1024, "test capabilities");
    }

    private static ServerCapabilities capabilities(String operatingSystem) {
        return new ServerCapabilities(operatingSystem, "x86_64", true, true, true, true,
                true, true, true, true, ManagedHelperProtocol.VERSION, 1024L * 1024 * 1024, "test capabilities");
    }
}
