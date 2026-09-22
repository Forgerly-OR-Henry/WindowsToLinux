package gold.debug.windowstolinux.shared.linux.sshd.session;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.EnumSet;

import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.channel.ClientChannel;
import org.apache.sshd.client.channel.ClientChannelEvent;
import org.apache.sshd.client.session.ClientSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Opt-in, read-only diagnostics for a disposable Ubuntu managed-deployment acceptance VM. This does not deploy, build, upload, alter processes, or use sudo.
 *
 * <p>针对一次性 Ubuntu 受管部署验收虚拟机的可选只读诊断。它不会部署、构建、上传、修改进程或使用 sudo。
 */
@EnabledIfSystemProperty(named = "managed.diagnostics", matches = "true")
class UbuntuManagedDiagnosticsAcceptanceTest {
    @Test
    void reportsProcessHeadroomWithoutChangingTheTarget() throws Exception {
        String host = System.getProperty("managed.ssh.host");
        String password = System.getenv("WINDOWSTOLINUX_TEST_SSH_PASSWORD");
        assertEquals(false, host == null || host.isBlank(), "managed.ssh.host is required");
        assertEquals(false, password == null || password.isBlank(), "WINDOWSTOLINUX_TEST_SSH_PASSWORD is required");

        char[] passwordChars = password.toCharArray();
        SshClient client = SshClient.setUpDefaultClient();
        try {
            client.start();
            try (ClientSession session = client.connect("ubuntu", host, 22).verify(Duration.ofSeconds(30))
                    .getSession()) {
                session.addPasswordIdentity(new String(passwordChars));
                session.auth().verify(Duration.ofSeconds(30));
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                try (ClientChannel channel = session.createExecChannel(diagnosticScript())) {
                    channel.setOut(output);
                    channel.open().verify(Duration.ofSeconds(30));
                    var events = channel.waitFor(EnumSet.of(ClientChannelEvent.CLOSED), Duration.ofSeconds(30));
                    assertEquals(true, events.contains(ClientChannelEvent.CLOSED),
                            "read-only Ubuntu diagnostics timed out");
                    assertEquals(0, channel.getExitStatus(), () -> output.toString(StandardCharsets.UTF_8));
                }
                System.out.println("MANAGED_UBUNTU_DIAGNOSTICS=" + output.toString(StandardCharsets.UTF_8).trim());
            }
        } finally {
            Arrays.fill(passwordChars, '\0');
            client.close(true);
        }
    }

    private static String diagnosticScript() {
        return """
                set -eu
                printf 'NPROC_LIMIT='; ulimit -u
                printf 'USER_PROCESS_COUNT='; ps -u ubuntu --no-headers | wc -l
                printf 'SYSTEM_PROCESS_COUNT='; ps -e --no-headers | wc -l
                printf 'JAVA_OR_MAVEN_PROCESS_COUNT='; ps -u ubuntu -o comm= | awk '$1 ~ /^(java|mvn)$/ { count++ } END { print count + 0 }'
                printf 'MEM_AVAILABLE_KIB='; awk '/MemAvailable:/ { print $2 }' /proc/meminfo
                """;
    }
}
