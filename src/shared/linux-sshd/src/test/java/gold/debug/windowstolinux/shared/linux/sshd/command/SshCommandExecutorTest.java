package gold.debug.windowstolinux.shared.linux.sshd.command;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import gold.debug.windowstolinux.shared.linux.sshd.session.LoopbackSshServer;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.server.shell.InvertedShellWrapper;
import org.apache.sshd.server.shell.ProcessShell;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@org.junit.jupiter.api.parallel.Isolated
class SshCommandExecutorTest {
    @TempDir
    Path temporaryDirectory;

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
            try (var session = client.connect("test", "127.0.0.1", server.getPort()).verify(Duration.ofSeconds(10))
                    .getSession()) {
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

                var payload = executor.execProtocolWithInput("cat",
                        "protocol-payload\n".getBytes(StandardCharsets.UTF_8), Duration.ofSeconds(20));
                assertTrue(payload.succeeded(), payload::failureEvidence);
                assertEquals("protocol-payload\n", payload.output());
                var verboseFailure = executor.execScript(
                        "set -eu\nprintf 'BEGIN\\n'\n" + "printf '%s\\n' '" + "installation-log".repeat(1_000) + "'\n"
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
                var reviewed = new java.util.ArrayList<gold.debug.windowstolinux.shared.linux.command.RemoteCommandRequest>();
                var outcomes = new java.util.concurrent.atomic.AtomicInteger();
                var deny = new java.util.concurrent.atomic.AtomicBoolean();
                var audit = new gold.debug.windowstolinux.shared.linux.command.RemoteCommandAudit() {
                    public void before(gold.debug.windowstolinux.shared.linux.command.RemoteCommandRequest request) {
                        reviewed.add(request);
                        if (deny.get())
                            throw new SecurityException("fixture denial");
                    }

                    public void dispatching(
                            gold.debug.windowstolinux.shared.linux.command.RemoteCommandRequest request) {
                        assertSame(request, reviewed.getLast());
                    }

                    public void after(gold.debug.windowstolinux.shared.linux.command.RemoteCommandRequest request,
                            gold.debug.windowstolinux.shared.linux.command.RemoteCommandResult result) {
                        assertTrue(result.known());
                        outcomes.incrementAndGet();
                    }

                    public void unknown(gold.debug.windowstolinux.shared.linux.command.RemoteCommandRequest request) {
                        fail("unexpected unknown transport result");
                    }
                };
                try (var scope = new gold.debug.windowstolinux.shared.linux.command.CommandExecutionScope("task",
                        "unscoped", () -> "revision", audit)) {
                    assertTrue(executor.execScript("printf 'AUDITED'", Duration.ofSeconds(10), true).succeeded());
                    assertTrue(reviewed.getLast().script().contains("printf 'AUDITED'"));
                    byte[] bytes = "bounded-stream".getBytes(StandardCharsets.UTF_8);
                    String digest = java.util.HexFormat.of()
                            .formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
                    var output = new java.io.ByteArrayOutputStream();
                    assertTrue(executor.execProtocolStreaming("cat", new java.io.ByteArrayInputStream(bytes), output,
                            Duration.ofSeconds(10), digest, bytes.length, 4096).succeeded());
                    assertArrayEquals(bytes, output.toByteArray());
                    assertEquals(digest, reviewed.getLast().inputDigest());
                    assertEquals(2, outcomes.get());
                    String last = receivedCommand.get();
                    deny.set(true);
                    assertThrows(SecurityException.class,
                            () -> executor.execProtocol("printf 'MUST_NOT_EXECUTE'", Duration.ofSeconds(10), true));
                    assertEquals(last, receivedCommand.get(),
                            "denial must happen before opening the execution channel");
                    assertEquals(2, outcomes.get());
                }
                gold.debug.windowstolinux.shared.linux.sshd.session.SshSessionLifecycleExecutor.closeQuietly(session);
            }
            gold.debug.windowstolinux.shared.linux.sshd.session.SshSessionLifecycleExecutor.closeQuietly(client);
        }
    }
}
