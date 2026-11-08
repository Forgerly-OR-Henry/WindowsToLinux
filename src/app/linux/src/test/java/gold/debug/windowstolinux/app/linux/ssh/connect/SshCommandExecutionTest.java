package gold.debug.windowstolinux.app.linux.ssh.connect;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import gold.debug.windowstolinux.appapi.linux.command.CommandRequest;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.ProblemCode;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
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
    void authorPayloadNotSentBeforeSenderRetainsKnownFailureAndStartedSenderRemainsUnknown() throws Exception {
        for (boolean opened : List.of(false, true)) {
            var bytes = new AtomicInteger();
            var inputRequested = new AtomicInteger();
            var inputStarted = new CountDownLatch(1);
            var openingFailure = new IOException("private channel failure");
            var opening = (org.apache.sshd.client.future.OpenFuture) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{org.apache.sshd.client.future.OpenFuture.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "await" -> true;
                    case "verify" -> {
                        if (!opened) throw openingFailure;
                        yield proxy;
                    }
                    case "isDone" -> true;
                    case "isOpened" -> opened;
                    case "isCanceled" -> false;
                    case "getException" -> opened ? null : openingFailure;
                    default -> throw new AssertionError(method.getName());
                });
            var channel = new org.apache.sshd.client.channel.ChannelExec(SshCommandFrame.COMMAND, null, Map.of()) {
                @Override
                public org.apache.sshd.client.future.OpenFuture open() {
                    return opening;
                }

                @Override
                public OutputStream getInvertedIn() {
                    inputRequested.incrementAndGet();
                    return new OutputStream() {
                        public void write(int value) {
                            bytes.incrementAndGet();
                            inputStarted.countDown();
                        }
                    };
                }

                @Override
                public Set<org.apache.sshd.client.channel.ClientChannelEvent> waitFor(Collection<org.apache.sshd.client.channel.ClientChannelEvent> events, long timeout) {
                    try {
                        assertTrue(inputStarted.await(3, TimeUnit.SECONDS));
                    } catch (InterruptedException interrupted) {
                        throw new AssertionError(interrupted);
                    }
                    throw new UncheckedIOException(new IOException("private receipt lost after sender creation"));
                }

                @Override
                protected void preClose() {
                }

                @Override
                protected org.apache.sshd.common.Closeable getInnerCloseable() {
                    return builder().build();
                }
            };
            var session = (org.apache.sshd.client.session.ClientSession) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{org.apache.sshd.client.session.ClientSession.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "createExecChannel" -> {
                        assertEquals(SshCommandFrame.COMMAND, args[0]);
                        assertFalse(args[0].toString().contains("private-author"));
                        yield channel;
                    }
                    case "addSessionListener", "removeSessionListener", "getKexState", "getIoSession" -> null;
                    case "isOpen", "isAuthenticated" -> true;
                    default -> throw new AssertionError(method.getName());
                });
            try (var request = request("private-author script", 100)) {
                var execution = new SshCommandExecution(session, request);
                try {
                    var result = execution.run(() -> false);
                    var problem = result.problem().orElseThrow();
                    assertEquals(opened ? TaskResult.UNKNOWN : TaskResult.FAILED, result.outcome());
                    assertEquals(opened ? "SSH_COMMAND_TRANSPORT" : "SSH_COMMAND_AUTHOR_PAYLOAD_NOT_SENT", problem.eventCode());
                    assertEquals("true", problem.context().get("commandOpenRequested"), "Bootstrap opening is not classified as absent");
                    assertEquals(Boolean.toString(opened), problem.context().get("inputSenderCreated"));
                    assertEquals(opened ? "MAY_HAVE_BEEN_SENT" : "NOT_SENT", problem.context().get("authorPayloadState"));
                    assertEquals(opened ? 1 : 0, inputRequested.get());
                    assertEquals(opened, bytes.get() > 0);
                    assertFalse(result.toString().contains("private"));
                } finally {
                    assertTrue(execution.close());
                    assertTrue(channel.isClosed());
                }
            }
        }
    }

    @Test
    void channelOpeningTimeoutProvesAuthorPayloadAbsenceAndPreservesCancellationAndStartedInputUncertainty() {
        assertAll(List.of("OPEN_TIMEOUT", "DEADLINE_CANCEL", "DEADLINE_INTERRUPT", "OPEN_CANCEL",
            "INTERRUPTED_IO", "INTERRUPTED_EXCEPTION", "TRANSPORT_CANCEL", "TRANSPORT_INTERRUPT",
            "STARTED_TIMEOUT", "CLOSE_PENDING", "BEFORE_CANCEL").stream().map(scenario ->
            (org.junit.jupiter.api.function.Executable) () -> {
                var bytes = new AtomicInteger();
                var inputRequested = new AtomicInteger();
                var openRequests = new AtomicInteger();
                var cancellationChecks = new AtomicInteger();
                var cancelled = new AtomicBoolean(scenario.equals("BEFORE_CANCEL"));
                var inputStarted = new CountDownLatch(1);
                var pending = new CountDownLatch(1);
                boolean startedSender = scenario.equals("STARTED_TIMEOUT");
                boolean knownAbsence = Set.of("OPEN_TIMEOUT", "CLOSE_PENDING").contains(scenario);
                boolean interrupted = Set.of("DEADLINE_INTERRUPT", "INTERRUPTED_IO",
                    "INTERRUPTED_EXCEPTION", "TRANSPORT_INTERRUPT").contains(scenario);
                class OpeningFailure {
                    @SuppressWarnings("unchecked")
                    <T extends Throwable> void raise(Throwable failure) throws T {
                        throw (T) failure;
                    }
                }
                var opening = new org.apache.sshd.client.future.DefaultOpenFuture(scenario, null) {
                    @Override
                    public boolean await(long timeout, org.apache.sshd.common.future.CancelOption... options) throws IOException {
                        switch (scenario) {
                            case "INTERRUPTED_IO" -> throw new InterruptedIOException("private-121 transport");
                            case "INTERRUPTED_EXCEPTION" ->
                                new OpeningFailure().raise(new InterruptedException("private-121 transport"));
                            case "TRANSPORT_CANCEL" -> {
                                cancelled.set(true);
                                throw new IOException("private-121 transport");
                            }
                            case "TRANSPORT_INTERRUPT" -> {
                                Thread.currentThread().interrupt();
                                throw new IOException("private-121 transport");
                            }
                            case "OPEN_CANCEL" -> cancelled.set(true);
                            case "STARTED_TIMEOUT" -> {
                                return super.await(timeout, options);
                            }
                            default -> {
                                try {
                                    assertFalse(pending.await(350, TimeUnit.MILLISECONDS));
                                } catch (InterruptedException failure) {
                                    throw new AssertionError(failure);
                                }
                            }
                        }
                        return false;
                    }
                };
                if (startedSender) opening.setOpened();
                var innerCloseable = new org.apache.sshd.common.util.closeable.SimpleCloseable(scenario, null) {
                    @Override
                    protected void doClose(boolean immediately) {
                        if (!scenario.equals("CLOSE_PENDING")) super.doClose(immediately);
                    }

                    void confirmClose() {
                        future.setClosed();
                    }
                };
                var channel = new org.apache.sshd.client.channel.ChannelExec(SshCommandFrame.COMMAND, null, Map.of()) {
                    @Override
                    public org.apache.sshd.client.future.OpenFuture open() {
                        openRequests.incrementAndGet();
                        return opening;
                    }

                    @Override
                    public OutputStream getInvertedIn() {
                        inputRequested.incrementAndGet();
                        return new OutputStream() {
                            @Override
                            public void write(int value) {
                                bytes.incrementAndGet();
                                inputStarted.countDown();
                            }
                        };
                    }

                    @Override
                    public Set<org.apache.sshd.client.channel.ClientChannelEvent> waitFor(Collection<org.apache.sshd.client.channel.ClientChannelEvent> events, long timeout) {
                        try {
                            assertTrue(inputStarted.await(3, TimeUnit.SECONDS));
                            assertFalse(pending.await(timeout, TimeUnit.MILLISECONDS));
                        } catch (InterruptedException failure) {
                            throw new AssertionError(failure);
                        }
                        return Set.of();
                    }

                    @Override
                    protected void preClose() {
                    }

                    @Override
                    protected org.apache.sshd.common.Closeable getInnerCloseable() {
                        return innerCloseable;
                    }
                };
                var session = (org.apache.sshd.client.session.ClientSession) java.lang.reflect.Proxy.newProxyInstance(
                    getClass().getClassLoader(), new Class<?>[]{org.apache.sshd.client.session.ClientSession.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "createExecChannel" -> {
                            assertEquals(SshCommandFrame.COMMAND, args[0]);
                            assertFalse(args[0].toString().contains("private-121"));
                            yield channel;
                        }
                        case "addSessionListener", "removeSessionListener", "getKexState", "getIoSession" -> null;
                        case "isOpen", "isAuthenticated" -> true;
                        default -> throw new AssertionError(method.getName());
                    });
                try (var request = new CommandRequest("private-121 author script", "private-121 input".getBytes(StandardCharsets.UTF_8),
                    startedSender ? Duration.ofSeconds(1) : Duration.ofMillis(250), 100)) {
                    var execution = new SshCommandExecution(session, request);
                    try {
                        var result = execution.run(() -> {
                            int checks = cancellationChecks.incrementAndGet();
                            if (checks >= 3 && scenario.equals("DEADLINE_CANCEL")) cancelled.set(true);
                            if (checks >= 3 && scenario.equals("DEADLINE_INTERRUPT"))
                                Thread.currentThread().interrupt();
                            return cancelled.get();
                        });
                        var problem = result.problem().orElseThrow();
                        boolean beforeDispatch = scenario.equals("BEFORE_CANCEL");
                        assertEquals(beforeDispatch ? TaskResult.CANCELLED : knownAbsence ? TaskResult.FAILED : TaskResult.UNKNOWN,
                            result.outcome(), scenario);
                        assertEquals(knownAbsence ? "SSH_COMMAND_AUTHOR_PAYLOAD_NOT_SENT"
                            : startedSender ? "SSH_COMMAND_TIMEOUT" : "SSH_COMMAND_CANCELLED", problem.eventCode(), scenario);
                        assertEquals(knownAbsence ? ProblemCode.IO_FAILURE : beforeDispatch ? ProblemCode.CANCELLED : ProblemCode.UNKNOWN,
                            problem.code(), scenario);
                        var diagnostic = problem.context();
                        assertEquals(beforeDispatch ? "BEFORE_DISPATCH" : startedSender ? "WAITING_FOR_EXIT" : "CHANNEL_OPENING",
                            diagnostic.get("commandPhase"), scenario);
                        assertEquals(Boolean.toString(!beforeDispatch), diagnostic.get("commandOpenRequested"));
                        assertEquals(Boolean.toString(startedSender), diagnostic.get("channelOpenConfirmed"));
                        assertEquals(Boolean.toString(startedSender), diagnostic.get("inputSenderCreated"));
                        assertEquals(startedSender ? "MAY_HAVE_BEEN_SENT" : "NOT_SENT", diagnostic.get("authorPayloadState"));
                        if (!startedSender) assertEquals("NOT_STARTED", diagnostic.get("inputState"));
                        assertEquals("true", diagnostic.get("sessionOpen"));
                        assertEquals("true", diagnostic.get("sessionAuthenticated"));
                        assertEquals("false", diagnostic.get("outputObserved"));
                        assertEquals("false", diagnostic.get("exitObserved"));
                        assertEquals(beforeDispatch ? 0 : 1, openRequests.get());
                        assertEquals(startedSender ? 1 : 0, inputRequested.get());
                        assertEquals(startedSender, bytes.get() > 0);
                        assertEquals(interrupted, Thread.currentThread().isInterrupted(), scenario);
                        assertFalse(result.toString().contains("private-121"));
                        if (knownAbsence || startedSender) {
                            assertEquals(startedSender ? "1000" : "250", diagnostic.get("timeoutMillis"));
                            assertTrue(Long.parseLong(diagnostic.get("elapsedMillis")) >= (startedSender ? 1000 : 250));
                        }
                        if (knownAbsence) {
                            opening.setOpened();
                            assertTrue(opening.isOpened());
                            assertEquals(0, inputRequested.get(), "A late open receipt cannot create a sender after run returned");
                            assertEquals(0, bytes.get());
                            assertTrue(problem.message().contains("deadline"));
                        }
                        if (scenario.equals("CLOSE_PENDING")) {
                            assertFalse(execution.close());
                            assertFalse(channel.isClosed(), "Known payload absence does not prove local resource release");
                        }
                    } finally {
                        boolean retainedInterrupt = Thread.interrupted();
                        try {
                            innerCloseable.confirmClose();
                            if (scenario.equals("BEFORE_CANCEL")) channel.close(true);
                            assertTrue(execution.close());
                            assertTrue(channel.isClosed());
                        } finally {
                            Thread.interrupted();
                        }
                        assertEquals(interrupted, retainedInterrupt, scenario);
                        assertFalse(Thread.currentThread().isInterrupted());
                    }
                }
            }).toList());
    }

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
            assertEquals(TaskResult.SUCCEEDED, result.outcome(), result.toString());
            assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input)),
                result.value().orElseThrow().stdout().split(" ")[0]);
            assertEquals("warning", result.value().orElseThrow().stderr());
            assertEquals(SshCommandFrame.COMMAND, wireCommand.get());
            assertTrue(wireCommand.get().length() < 1024);
            assertFalse(wireCommand.get().contains("中文脚本"));
            var failed = connection.execute(request("builtin printf 'observed'; exit 7", 1024), () -> false);
            assertEquals(TaskResult.FAILED, failed.outcome());
            assertEquals(7, failed.value().orElseThrow().exitCode().orElseThrow());
            assertEquals("observed", failed.value().orElseThrow().stdout());
            var ignoredInput = connection.execute(new CommandRequest("", input, Duration.ofSeconds(5), 10),
                () -> false);
            assertEquals(TaskResult.SUCCEEDED, ignoredInput.outcome());
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
                assertEquals(TaskResult.SUCCEEDED, result.outcome());
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
            var result = connection.execute(request("private-command", 10), () -> true);
            assertEquals(TaskResult.CANCELLED, result.outcome());
            var diagnostic = result.problem().orElseThrow().context();
            assertEquals("BEFORE_DISPATCH", diagnostic.get("commandPhase"));
            assertEquals("false", diagnostic.get("commandOpenRequested"));
            assertEquals("false", diagnostic.get("channelOpenConfirmed"));
            assertEquals("NOT_STARTED", diagnostic.get("inputState"));
            assertFalse(diagnostic.toString().contains("private-command"));
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
            assertEquals(TaskResult.UNKNOWN, result.outcome());
            assertEquals("SSH_COMMAND_CANCELLED", result.problem().orElseThrow().eventCode());
            assertEquals(ProblemCode.BUSY,
                assertThrows(ApiException.class, () -> connection.execute(request("write again", 100), () -> false))
                    .problem().code());
            assertEquals(1, effect.get());
            assertEquals(1, connector.retainedConnections());
        }
    }

    @Test
    void channelCreationFailureRemainsKnownAndReportsNoPrivateExceptionText() throws Exception {
        var session = (org.apache.sshd.client.session.ClientSession) java.lang.reflect.Proxy.newProxyInstance(
            getClass().getClassLoader(), new Class<?>[]{org.apache.sshd.client.session.ClientSession.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "createExecChannel" -> throw new IOException("private transport address and payload");
                case "addSessionListener", "removeSessionListener", "getKexState", "getIoSession" -> null;
                case "isOpen", "isAuthenticated" -> true;
                default -> throw new AssertionError("Unexpected session call: " + method.getName());
            });
        try (var request = request("private source command", 10)) {
            var execution = new SshCommandExecution(session, request);
            try {
                var result = execution.run(() -> false);
                assertEquals(TaskResult.FAILED, result.outcome());
                assertEquals("SSH_COMMAND_NOT_SENT", result.problem().orElseThrow().eventCode());
                var diagnostic = result.problem().orElseThrow().context();
                assertEquals("CREATING_CHANNEL", diagnostic.get("commandPhase"));
                assertEquals("false", diagnostic.get("commandOpenRequested"));
                assertEquals("false", diagnostic.get("channelOpenConfirmed"));
                assertEquals("NOT_STARTED", diagnostic.get("inputState"));
                assertFalse(result.toString().contains("private"));
            } finally {
                assertTrue(execution.close());
            }
        }
    }

    @Test
    void blockedInputReportsBoundedLibraryFramesWithoutPayloadAndRemainsUnknown() throws Exception {
        try (var fixture = new WireFixture(root, command -> command.destroyed.await());
             var connector = new SshConnector()) {
            org.apache.sshd.core.CoreModuleProperties.WINDOW_SIZE.set(fixture.server.server(), 1024L);
            try (var connection = connect(connector, fixture.server)) {
                var result = connection.execute(new CommandRequest("private command", new byte[65536],
                    Duration.ofSeconds(1), 100), () -> false);
                assertEquals(TaskResult.UNKNOWN, result.outcome());
                assertEquals("SSH_COMMAND_TIMEOUT", result.problem().orElseThrow().eventCode());
                var diagnostic = result.problem().orElseThrow().context();
                assertEquals("SENDING", diagnostic.get("inputState"));
                assertTrue(Set.of("WAITING", "TIMED_WAITING", "RUNNABLE").contains(
                    diagnostic.get("inputThreadState")), diagnostic.toString());
                var frames = diagnostic.entrySet().stream()
                    .filter(entry -> entry.getKey().startsWith("inputStack[")).toList();
                assertTrue(!frames.isEmpty() && frames.size() <= 6, diagnostic.toString());
                assertTrue(frames.stream().anyMatch(entry -> entry.getValue().contains("org.apache.sshd.")),
                    diagnostic.toString());
                assertFalse(diagnostic.toString().contains("private"));
                assertFalse(diagnostic.toString().contains("127.0.0.1"));
                assertEquals(1, fixture.calls.get());
                assertEquals(ProblemCode.BUSY,
                    assertThrows(ApiException.class, () -> connection.execute(request("replay", 100), () -> false))
                        .problem().code());
            }
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
                assertEquals(TaskResult.UNKNOWN, result.outcome());
                assertEquals(timeout ? "SSH_COMMAND_TIMEOUT" : "SSH_COMMAND_EXIT_UNKNOWN",
                    result.problem().orElseThrow().eventCode());
                assertTrue(result.value().orElseThrow().exitCode().isEmpty());
                assertEquals("observed prefix", result.value().orElseThrow().stdout());
                var diagnostic = result.problem().orElseThrow().context();
                assertEquals("WAITING_FOR_EXIT", diagnostic.get("commandPhase"));
                assertEquals("true", diagnostic.get("commandOpenRequested"));
                assertEquals("true", diagnostic.get("channelOpenConfirmed"));
                assertEquals(Boolean.toString(!timeout), diagnostic.get("channelClosed"));
                assertEquals("true", diagnostic.get("sessionOpen"));
                assertEquals("true", diagnostic.get("sessionAuthenticated"));
                assertEquals("COMPLETED", diagnostic.get("inputState"));
                assertEquals("true", diagnostic.get("outputObserved"));
                assertEquals("false", diagnostic.get("exitObserved"));
                assertEquals(timeout ? "300" : "5000", diagnostic.get("timeoutMillis"));
                long elapsed = Long.parseLong(diagnostic.get("elapsedMillis"));
                assertTrue(elapsed >= 0);
                if (timeout) assertTrue(elapsed >= 300);
                assertFalse(diagnostic.toString().contains("fixture"));
                assertFalse(diagnostic.toString().contains("observed prefix"));
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
