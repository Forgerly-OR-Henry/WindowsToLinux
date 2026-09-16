package gold.debug.windowstolinux.shared.linux.sshd.command;

import org.apache.sshd.client.SshClient;
import gold.debug.windowstolinux.shared.linux.sshd.session.LoopbackSshServer;
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

@org.junit.jupiter.api.parallel.Isolated
class SshCommandExecutorTest {
    @TempDir Path temporaryDirectory;

    @Test
    void streamsLargeScriptsWithoutArgumentsAndKeepsChildInputSeparate() throws Exception {
        String bash = System.getProperty("managed.test.bash", "/bin/bash");
        assumeTrue(java.nio.file.Files.isExecutable(Path.of(bash)), "Bash is required for transport execution");
        AtomicReference<String> receivedCommand = new AtomicReference<>();
        try (var fixture = new LoopbackSshServer(temporaryDirectory, server -> {
            server.setCommandFactory((channel, command) -> {
                receivedCommand.set(command);
                return new InvertedShellWrapper(new ProcessShell(bash, "-lc", command));
            });
        }); SshClient client = SshClient.setUpDefaultClient()) {
            var server = fixture.server();
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
                var overflow = executor.execWithOutputLimit("printf 'PROTOCOL=7\\n'; head -c 200000 /dev/zero",
                        Duration.ofSeconds(10), 4096);
                assertFalse(overflow.succeeded(), "a truncated protocol must never succeed");
                assertTrue(overflow.failureEvidence().contains("byte limit"), overflow::failureEvidence);
                var following = executor.execProtocol("printf 'ALIVE=1'", Duration.ofSeconds(10), true);
                assertTrue(following.succeeded(), following::failureEvidence);
                assertEquals("ALIVE=1", following.output());
                gold.debug.windowstolinux.shared.linux.sshd.session.SshSessionLifecycleExecutor.closeQuietly(session);
            }
            gold.debug.windowstolinux.shared.linux.sshd.session.SshSessionLifecycleExecutor.closeQuietly(client);
        }
    }
}
