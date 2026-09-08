package gold.debug.windowstolinux.shared.linux.sshd.command;

import org.apache.sshd.client.SshClient;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.apache.sshd.server.shell.ProcessShell;
import org.apache.sshd.server.shell.InvertedShellWrapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class SshCommandExecutorTest {
    @TempDir Path temporaryDirectory;

    @Test
    void streamsLargeScriptsWithoutArgumentsAndKeepsChildInputSeparate() throws Exception {
        String bash = System.getProperty("managed.test.bash", "/bin/bash");
        assumeTrue(java.nio.file.Files.isExecutable(Path.of(bash)), "Bash is required for transport execution");
        AtomicReference<String> receivedCommand = new AtomicReference<>();
        try (SshServer server = SshServer.setUpDefaultServer(); SshClient client = SshClient.setUpDefaultClient()) {
            server.setHost("127.0.0.1");
            server.setPort(0);
            server.setKeyPairProvider(new SimpleGeneratorHostKeyProvider(temporaryDirectory.resolve("host-key")));
            server.setPasswordAuthenticator((user, password, session) -> true);
            server.setCommandFactory((channel, command) -> {
                receivedCommand.set(command);
                return new InvertedShellWrapper(new ProcessShell(bash, "-lc", command));
            });
            server.start();
            client.setServerKeyVerifier((session, address, key) -> true);
            client.start();
            try (var session = client.connect("test", "127.0.0.1", server.getPort())
                    .verify(Duration.ofSeconds(10)).getSession()) {
                session.addPasswordIdentity("local-test");
                session.auth().verify(Duration.ofSeconds(10));
                var executor = new SshCommandExecutor(session);
                String script = "set -eu\n# " + "large-script-".repeat(24_000)
                        + "\ncat >/dev/null\nprintf '%s\\n' \"Unicode-中文-'literal'\"\n";
                var result = executor.execScript(script, Duration.ofSeconds(20), true);
                assertTrue(result.succeeded(), result::failureEvidence);
                assertEquals("Unicode-中文-'literal'", result.output().strip());
                assertTrue(receivedCommand.get().length() < 100);

                var rejected = executor.execScript("set -eu\nprintf 'BEFORE\\n'\nexit 37\nprintf 'AFTER\\n'",
                        Duration.ofSeconds(20), true);
                assertFalse(rejected.succeeded());
                assertEquals(37, rejected.exitStatus());
                assertEquals("BEFORE", rejected.output().strip());

                var payload = executor.execProtocolWithInput("cat", "protocol-payload\n".getBytes(StandardCharsets.UTF_8),
                        Duration.ofSeconds(20));
                assertTrue(payload.succeeded(), payload::failureEvidence);
                assertEquals("protocol-payload\n", payload.output());
                var verboseFailure = executor.execScript("set -eu\nprintf 'BEGIN\\n'\n"
                        + "printf '%s\\n' '" + "installation-log".repeat(1_000) + "'\n"
                        + "printf 'PREPARE_CHECK_FAILED=compiler-version\\nsecret=private-value\\n'\nexit 64",
                        Duration.ofSeconds(20), true);
                assertEquals(64, verboseFailure.exitStatus());
                assertTrue(verboseFailure.output().startsWith("BEGIN"));
                assertTrue(verboseFailure.output().contains("PREPARE_CHECK_FAILED=compiler-version"));
                assertFalse(verboseFailure.failureEvidence().contains("private-value"));
                assertTrue(verboseFailure.output().length() <= SshCommandExecutor.MAX_EVIDENCE_CHARS);
                gold.debug.windowstolinux.shared.linux.sshd.session.SshSessionLifecycleExecutor.closeQuietly(session);
            }
            gold.debug.windowstolinux.shared.linux.sshd.session.SshSessionLifecycleExecutor.closeQuietly(client);
            server.close(false).await(Duration.ofSeconds(10));
        }
    }
}
