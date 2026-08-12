package gold.debug.windowstolinux.shared.linux.sshd.connection;

import gold.debug.windowstolinux.shared.linux.connection.DeploymentRemoteSession;
import gold.debug.windowstolinux.shared.linux.connection.HostKeyDecision;
import gold.debug.windowstolinux.shared.linux.connection.SshCredential;
import gold.debug.windowstolinux.shared.linux.connection.SshEndpoint;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exercises repeated product transport shutdown against the authorized Linux target. / 针对授权 Linux 目标验证重复的产品传输关闭。 */
@EnabledIfSystemProperty(named = "managed.ssh.shutdown", matches = "true")
class UbuntuSshShutdownAcceptanceIT {
    @Test
    void repeatedCapabilitySessionsDrainAllAsynchronousCallbacksBeforeClientShutdown() throws Exception {
        String host = requiredProperty("managed.ssh.host");
        String username = System.getProperty("managed.ssh.user", "root");
        char[] password = requiredEnvironment("WINDOWSTOLINUX_TEST_SSH_PASSWORD").toCharArray();
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        List<Throwable> asynchronousFailures = new CopyOnWriteArrayList<>();
        Thread.setDefaultUncaughtExceptionHandler((thread, failure) -> asynchronousFailures.add(failure));
        try {
            for (int attempt = 0; attempt < 12; attempt++) {
                SshCredential.Password credential = new SshCredential.Password(password.clone());
                try (DeploymentRemoteSession session = new SshdLinuxGateway().connect(
                        new SshEndpoint("shutdown-live", host, 22, username), credential,
                        (endpoint, fingerprint) -> HostKeyDecision.ACCEPT_FIRST_USE)) {
                    session.collectCapabilities();
                }
            }
            Thread.sleep(1_500L);
            assertTrue(asynchronousFailures.isEmpty(), () -> "asynchronous SSH shutdown failures: "
                    + asynchronousFailures);
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous);
            Arrays.fill(password, '\0');
        }
    }

    private static String requiredProperty(String name) {
        String value = System.getProperty(name);
        assertTrue(value != null && !value.isBlank(), name + " is required");
        return value;
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        assertTrue(value != null && !value.isBlank(), name + " is required");
        return value;
    }
}
