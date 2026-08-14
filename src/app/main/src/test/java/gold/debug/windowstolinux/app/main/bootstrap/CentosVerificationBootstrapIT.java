package gold.debug.windowstolinux.app.main.bootstrap;

import gold.debug.windowstolinux.shared.linux.sshd.connection.SshCommandExecutor;
import gold.debug.windowstolinux.shared.model.server.LinuxCapabilities;
import gold.debug.windowstolinux.shared.model.server.LinuxDistro;
import gold.debug.windowstolinux.shared.model.server.LinuxSecurityState;
import gold.debug.windowstolinux.shared.model.server.ServerIdentity;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.auth.keyboard.UserAuthKeyboardInteractiveFactory;
import org.apache.sshd.client.auth.password.UserAuthPasswordFactory;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.keyprovider.KeyIdentityProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Performs an explicitly requested, fixed testbed bootstrap for one disabled CentOS Stream 9 target. It is not a
 * product deployment path: it first obtains the product-verified host fingerprint, permits no caller-provided command,
 * and changes only the SELinux enablement setting before scheduling one reboot.
 *
 * <p>对一个 SELinux 已禁用的 CentOS Stream 9 目标执行显式请求的固定测试环境引导。它不是产品部署路径：先取得产品已验证的主机指纹，
 * 不允许调用方提供命令，并且只修改 SELinux 启用设置后安排一次重启。
 */
@EnabledIfSystemProperty(named = "managed.runtime.centos-bootstrap", matches = "inspect|enable")
class CentosVerificationBootstrapIT {
    private static final Duration CONNECTION_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration CLOSE_TIMEOUT = Duration.ofSeconds(5);
    private static final Set<String> INSPECTION_FIELDS = Set.of(
            "SELINUX_RUNTIME", "SELINUX_CONFIG", "KERNEL_SELINUX_DISABLED", "GRUBBY");
    @TempDir
    Path temporaryDirectory;

    @Test
    void inspectsOrEnablesOnlyTheExplicitCentosVerificationTarget() throws Exception {
        String mode = System.getProperty("managed.runtime.centos-bootstrap");
        assertTrue("inspect".equals(mode) || "enable".equals(mode), "unsupported CentOS bootstrap mode");
        assertEquals("root", System.getProperty("managed.ssh.user", "root"),
                "the fixed CentOS testbed bootstrap requires the explicit root test profile");

        try (LiveTypedDeploymentContext context = new LiveTypedDeploymentContext(temporaryDirectory.resolve("centos-bootstrap"))) {
            LinuxCapabilities capabilities = context.inspectDeploymentCapabilities();
            assertEquals(LinuxDistro.CENTOS_STREAM, capabilities.distro(), "only CentOS Stream is eligible");
            assertEquals("9", capabilities.version(), "only CentOS Stream 9 is eligible");
            assertEquals(LinuxSecurityState.DISABLED, capabilities.securityPosture().state(),
                    "this bootstrap only applies to a target observed as SELinux Disabled");

            ServerIdentity trusted = context.trustedServer();
            SelinuxInspection inspection = inspect(trusted);
            System.out.printf("CENTOS_TESTBED_SELINUX runtime=%s config=%s kernelDisableArgument=%s grubby=%s%n",
                    inspection.runtime(), inspection.config(), inspection.kernelDisableArgument(), inspection.grubby());

            if ("inspect".equals(mode)) {
                return;
            }

            assertEquals("Disabled", inspection.runtime(), "the fixed enablement requires a disabled runtime");
            assertEquals("disabled", inspection.config(), "the fixed enablement requires SELINUX=disabled");
            Map<String, String> result = fixedCommand(trusted, enablementAndRebootScript());
            assertEquals(Set.of("SELINUX_CONFIG_UPDATED", "REBOOT_SCHEDULED"), result.keySet(),
                    "unexpected CentOS bootstrap result");
            assertEquals("1", result.get("SELINUX_CONFIG_UPDATED"));
            assertEquals("1", result.get("REBOOT_SCHEDULED"));
            System.out.println("CENTOS_TESTBED_SELINUX enablement=applied reboot=scheduled");
        }
    }

    private static SelinuxInspection inspect(ServerIdentity trusted) throws Exception {
        Map<String, String> fields = fixedCommand(trusted, inspectionScript());
        assertEquals(INSPECTION_FIELDS, fields.keySet(), "unexpected CentOS SELinux inspection result");
        return new SelinuxInspection(fields.get("SELINUX_RUNTIME"), fields.get("SELINUX_CONFIG"),
                fields.get("KERNEL_SELINUX_DISABLED"), fields.get("GRUBBY"));
    }

    private static Map<String, String> fixedCommand(ServerIdentity trusted, String script) throws Exception {
        char[] password = requiredEnvironment("WINDOWSTOLINUX_TEST_SSH_PASSWORD").toCharArray();
        SshClient client = SshClient.setUpDefaultClient();
        client.setKeyIdentityProvider(KeyIdentityProvider.EMPTY_KEYS_PROVIDER);
        client.setUserAuthFactories(List.of(UserAuthPasswordFactory.INSTANCE, UserAuthKeyboardInteractiveFactory.INSTANCE));
        client.setServerKeyVerifier((session, remote, key) -> trusted.hostKeySha256().equals(fingerprint(key)));
        try {
            client.start();
            try (ClientSession session = client.connect("root", trusted.host(), trusted.sshPort())
                    .verify(CONNECTION_TIMEOUT).getSession()) {
                session.addPasswordIdentity(new String(password));
                session.auth().verify(CONNECTION_TIMEOUT);
                SshCommandExecutor.CommandResult result = new SshCommandExecutor(session)
                        .exec(script, COMMAND_TIMEOUT, true);
                assertTrue(result.succeeded(), () -> "fixed CentOS testbed command failed: " + result.failureEvidence());
                return SshCommandExecutor.lines(result.output());
            }
        } finally {
            Arrays.fill(password, '\0');
            closeClient(client);
        }
    }

    private static void closeClient(SshClient client) {
        try {
            if (!client.close(false).await(CLOSE_TIMEOUT)) {
                client.close(true).await(CLOSE_TIMEOUT);
            }
            client.stop();
        } catch (Exception ignored) {
            // A testbed connection-close error cannot alter the recorded remote command result. / 测试环境连接关闭错误不能改变已记录的远端命令结果。
        }
    }

    private static String fingerprint(PublicKey key) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(key.getEncoded());
            return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JDK SHA-256 is unavailable", exception);
        }
    }

    private static String inspectionScript() {
        return """
                set -eu
                test "$(id -u)" = 0
                test -r /etc/selinux/config
                printf 'SELINUX_RUNTIME='; getenforce 2>/dev/null || printf 'UNAVAILABLE'; printf '\n'
                printf 'SELINUX_CONFIG='; awk -F= '/^[[:space:]]*SELINUX=/ { value=$2; gsub(/[[:space:]]/, "", value); print tolower(value); found=1; exit } END { if (!found) print "missing" }' /etc/selinux/config
                printf 'KERNEL_SELINUX_DISABLED='; if grep -Eq '(^|[[:space:]])selinux=0([[:space:]]|$)' /proc/cmdline; then printf 'yes'; else printf 'no'; fi; printf '\n'
                printf 'GRUBBY='; if command -v grubby >/dev/null 2>&1; then printf 'available'; else printf 'unavailable'; fi; printf '\n'
                """;
    }

    private static String enablementAndRebootScript() {
        return """
                set -eu
                test "$(id -u)" = 0
                test -f /etc/selinux/config
                grep -Eq '^[[:space:]]*SELINUX=disabled[[:space:]]*$' /etc/selinux/config
                sed -ri 's/^[[:space:]]*SELINUX=.*/SELINUX=enforcing/' /etc/selinux/config
                grep -Eq '^[[:space:]]*SELINUX=enforcing[[:space:]]*$' /etc/selinux/config
                if command -v grubby >/dev/null 2>&1; then
                  grubby --update-kernel=ALL --remove-args=selinux=0
                elif grep -Eq '(^|[[:space:]])selinux=0([[:space:]]|$)' /proc/cmdline; then
                  exit 24
                fi
                shutdown -r +1 "WindowsToLinux CentOS verification SELinux enablement"
                printf 'SELINUX_CONFIG_UPDATED=1\n'
                printf 'REBOOT_SCHEDULED=1\n'
                """;
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        assertTrue(value != null && !value.isBlank(), name + " is required");
        return value;
    }

    private record SelinuxInspection(String runtime, String config, String kernelDisableArgument, String grubby) {
    }
}
