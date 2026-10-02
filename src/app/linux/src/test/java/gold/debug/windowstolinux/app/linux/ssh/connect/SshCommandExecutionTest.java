package gold.debug.windowstolinux.app.linux.ssh.connect;

import gold.debug.windowstolinux.appapi.task.ApiException;
import gold.debug.windowstolinux.appapi.task.Outcome;
import gold.debug.windowstolinux.appapi.task.ProblemCode;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import gold.debug.windowstolinux.appapi.linux.*;

import org.apache.sshd.server.*;
import org.apache.sshd.server.channel.ChannelSession;
import org.apache.sshd.server.command.Command;
import org.apache.sshd.server.shell.ProcessShellFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class SshCommandExecutionTest {
    @TempDir
    Path root;

    @Test
    void realBashReceivesLargeUnicodeScriptAndExactBinaryStdinThroughOneSshChannel() throws Exception {
        Path bash = Path.of(System.getProperty("managed.test.bash", "/bin/bash"));
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.isRegularFile(bash),
            "Set managed.test.bash to validate the native protocol");
        var wireCommand = new AtomicReference<String>();
        try (var server = new LinuxLoopbackSshServer(root, ssh -> ssh.setCommandFactory((channel, command) -> {
            wireCommand.set(command);
            // Windows ProcessBuilder/MSYS quote conversion is not the remote SSH shell parser.
            Path entry = root.resolve("wire-command.sh");
            Files.writeString(entry, command, StandardCharsets.UTF_8);
            return new ProcessShellFactory("fixture",
                List.of(bash.toString(), "--noprofile", "--norc", entry.toString())) {
                @Override
                protected List<String> resolveEffectiveCommand(ChannelSession c, String ignored,
                                                               List<String> arguments) {
                    return arguments;
                }
            }.createShell(channel);
        })); var connector = new SshConnector(); var connection = connect(connector, server)) {
            byte[] input = new byte[512 * 1024];
            for (int i = 0; i < input.length; i++)
                input[i] = (byte) i;
            String script = "# 中文脚本 " + "x".repeat(200000) + "\n/usr/bin/sha256sum\nbuiltin printf 'warning' >&2\n";
            var result = connection.execute(new CommandRequest(script, input, Duration.ofSeconds(10), 4096),
                () -> false);
            assertEquals(Outcome.SUCCEEDED, result.outcome(), result.toString());
            assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input)),
                result.value().orElseThrow().stdout().split(" ")[0]);
            assertEquals("warning", result.value().orElseThrow().stderr());
            assertEquals(SshCommandFrame.COMMAND, wireCommand.get());
            assertTrue(wireCommand.get().length() < 1024);
            assertFalse(wireCommand.get().contains("中文脚本"));
            var failed = connection.execute(request("builtin printf 'observed'; exit 7", 1024), () -> false);
            assertEquals(Outcome.FAILED, failed.outcome());
            assertEquals(7, failed.value().orElseThrow().exitCode().orElseThrow());
            assertEquals("observed", failed.value().orElseThrow().stdout());
            var ignoredInput = connection.execute(new CommandRequest("", input, Duration.ofSeconds(5), 10),
                () -> false);
            assertEquals(Outcome.SUCCEEDED, ignoredInput.outcome());
            assertEquals("", ignoredInput.value().orElseThrow().stdout());
        }
    }

    @Test
    void outputIsDrainedWithinOneCombinedBudgetWithoutStoppingSuccessfulWork() throws Exception {
        var executed = new AtomicInteger();
        try (var fixture = new WireFixture(root, command -> {
            command.input.readAllBytes();
            command.output.write("A".repeat(100000).getBytes(StandardCharsets.UTF_8));
            command.error.write("B".repeat(100000).getBytes(StandardCharsets.UTF_8));
            executed.incrementAndGet();
            command.exit.onExit(0);
        }); var connector = new SshConnector(); var connection = connect(connector, fixture.server)) {
            for (long limit : new long[]{65, 0}) {
                var result = connection.execute(request("fixture", limit), () -> false);
                assertEquals(Outcome.SUCCEEDED, result.outcome());
                var captured = result.value().orElseThrow();
                assertTrue(captured.truncated());
                assertEquals(limit, captured.stdout().getBytes(StandardCharsets.UTF_8).length
                    + captured.stderr().getBytes(StandardCharsets.UTF_8).length);
            }
            assertEquals(2, executed.get());
        }
    }

    @Test
    void invalidBudgetsAndEncodingAreRejectedBeforeAnyCommandDispatch() throws Exception {
        try (var fixture = new WireFixture(root, command -> {
            throw new AssertionError("Rejected input was dispatched");
        }); var connector = new SshConnector(); var connection = connect(connector, fixture.server)) {
            var invalid = List.of(request("\uD800", 10), request("bad\0script", 10),
                request("fixture", SshCommandFrame.MAX_OUTPUT + 1L),
                new CommandRequest("fixture", new byte[SshCommandFrame.MAX_INPUT + 1], Duration.ofSeconds(3), 10),
                new CommandRequest("fixture", new byte[0], Duration.ofSeconds(Long.MAX_VALUE), 10));
            for (var request : invalid)
                assertEquals(ProblemCode.INVALID_INPUT,
                    assertThrows(ApiException.class, () -> connection.execute(request, () -> false)).problem()
                        .code());
            assertEquals(0, fixture.calls.get());
        }
    }

    @Test
    void cancellationBeforeDispatchIsKnownAndSendsNoCommand() throws Exception {
        try (var fixture = new WireFixture(root, command -> {
            throw new AssertionError("Cancelled work was dispatched");
        }); var connector = new SshConnector(); var connection = connect(connector, fixture.server)) {
            assertEquals(Outcome.CANCELLED, connection.execute(request("fixture", 10), () -> true).outcome());
            assertEquals(0, fixture.calls.get());
        }
    }

    @Test
    void cancellationAfterDispatchRetainsUnknownAndNeverReplaysTheSideEffect() throws Exception {
        var effect = new AtomicInteger();
        var entered = new CountDownLatch(1);
        var cancelled = new AtomicBoolean();
        try (var fixture = new WireFixture(root, command -> {
            command.input.readAllBytes();
            effect.incrementAndGet();
            entered.countDown();
            command.destroyed.await();
        });
             var connector = new SshConnector();
             var connection = connect(connector, fixture.server);
             var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var pending = workers.submit(() -> connection.execute(request("write once", 100), cancelled::get));
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            assertEquals(ProblemCode.BUSY, assertThrows(ApiException.class, connection::close).problem().code());
            cancelled.set(true);
            var result = pending.get(8, TimeUnit.SECONDS);
            assertEquals(Outcome.UNKNOWN, result.outcome());
            assertEquals("SSH_COMMAND_CANCELLED", result.problem().orElseThrow().eventCode());
            assertEquals(ProblemCode.BUSY,
                assertThrows(ApiException.class, () -> connection.execute(request("write again", 100), () -> false))
                    .problem().code());
            assertEquals(1, effect.get());
            assertEquals(1, connector.retainedConnections());
        }
    }

    @Test
    void timeoutAndMissingExitAreUncertainEvenAfterLocalChannelClose() throws Exception {
        for (boolean timeout : new boolean[]{true, false}) {
            Path directory = Files.createDirectories(root.resolve(Boolean.toString(timeout)));
            try (var fixture = new WireFixture(directory, command -> {
                command.input.readAllBytes();
                command.output.write("observed prefix".getBytes(StandardCharsets.UTF_8));
                command.output.flush();
                if (timeout)
                    command.destroyed.await();
                else {
                    var packet = command.channel.getSession()
                        .createBuffer(org.apache.sshd.common.SshConstants.SSH_MSG_CHANNEL_CLOSE);
                    packet.putUInt(command.channel.getRecipient());
                    command.channel.getSession().writePacket(packet);
                }
            }); var connector = new SshConnector(); var connection = connect(connector, fixture.server)) {
                var result = connection.execute(new CommandRequest("fixture", new byte[0],
                    timeout ? Duration.ofMillis(300) : Duration.ofSeconds(5), 100), () -> false);
                assertEquals(Outcome.UNKNOWN, result.outcome());
                assertEquals(timeout ? "SSH_COMMAND_TIMEOUT" : "SSH_COMMAND_EXIT_UNKNOWN",
                    result.problem().orElseThrow().eventCode());
                assertTrue(result.value().orElseThrow().exitCode().isEmpty());
                assertEquals("observed prefix", result.value().orElseThrow().stdout());
                assertEquals(1, fixture.calls.get());
            }
        }
    }

    static CommandRequest request(String script, long limit) {
        return new CommandRequest(script, new byte[0], Duration.ofSeconds(5), limit);
    }

    public static SshConnector.Connection connect(SshConnector connector, LinuxLoopbackSshServer server) throws Exception {
        return connector.connect(new SshEndpoint("fixture", "127.0.0.1", server.server().getPort(), "fixture"),
            "fixture".toCharArray(), (endpoint, fingerprint) -> true, Duration.ofSeconds(3), () -> false);
    }

    @FunctionalInterface
    interface Work {
        void execute(WireCommand command) throws Exception;
    }

    static final class WireFixture implements AutoCloseable {
        final AtomicInteger calls = new AtomicInteger();

        final Queue<Throwable> failures = new ConcurrentLinkedQueue<>();

        final List<WireCommand> commands = new CopyOnWriteArrayList<>();

        final LinuxLoopbackSshServer server;

        WireFixture(Path root, Work work) throws Exception {
            server = new LinuxLoopbackSshServer(root, ssh -> ssh.setCommandFactory((channel, request) -> {
                assertEquals(SshCommandFrame.COMMAND, request);
                calls.incrementAndGet();
                var command = new WireCommand(work, failures);
                commands.add(command);
                return command;
            }));
        }

        public void close() throws Exception {
            server.close();
            for (var command : commands)
                if (command.worker != null)
                    assertFalse(command.worker.isAlive(), "Wire test worker leaked");
            assertTrue(failures.isEmpty(), failures.toString());
        }
    }

    static final class WireCommand implements Command {
        final Work work;

        final Queue<Throwable> failures;

        final CountDownLatch destroyed = new CountDownLatch(1);

        InputStream input;

        OutputStream output;

        OutputStream error;

        ExitCallback exit;

        ChannelSession channel;

        Thread worker;

        WireCommand(Work work, Queue<Throwable> failures) {
            this.work = work;
            this.failures = failures;
        }

        public void setInputStream(InputStream value) {
            input = value;
        }

        public void setOutputStream(OutputStream value) {
            output = value;
        }

        public void setErrorStream(OutputStream value) {
            error = value;
        }

        public void setExitCallback(ExitCallback value) {
            exit = value;
        }

        public void start(ChannelSession channel, Environment environment) {
            this.channel = channel;
            worker = Thread.ofVirtual().name("fixture-ssh-command").start(() -> {
                try {
                    work.execute(this);
                } catch (Throwable failure) {
                    if (destroyed.getCount() != 0 || !(failure instanceof IOException))
                        failures.add(failure);
                }
            });
        }

        public void destroy(ChannelSession channel) throws Exception {
            destroyed.countDown();
            if (input != null)
                input.close();
            if (worker != null && worker != Thread.currentThread())
                assertTrue(worker.join(Duration.ofSeconds(3)));
        }
    }
}
