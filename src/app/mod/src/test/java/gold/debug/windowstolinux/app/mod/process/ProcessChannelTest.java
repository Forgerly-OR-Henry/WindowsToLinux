package gold.debug.windowstolinux.app.mod.process;

import gold.debug.windowstolinux.appapi.task.ApiException;
import gold.debug.windowstolinux.appapi.task.OperationResult;
import gold.debug.windowstolinux.appapi.task.Outcome;
import gold.debug.windowstolinux.appapi.task.Problem;
import gold.debug.windowstolinux.appapi.task.ProblemCode;
import gold.debug.windowstolinux.appapi.task.ProcessMessage;

import static gold.debug.windowstolinux.app.mod.process.ProcessProtocol.Kind;
import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;


import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProcessChannelTest {
    @TempDir
    Path root;

    static final Duration DEADLINE = Duration.ofSeconds(5);

    static final ProcessMessage REQUEST = new ProcessMessage("example.work", "{\"input\":\"literal ; 中文\"}");

    Running launch(String mode) throws Exception {
        var java = Path.of(System.getProperty("java.home"), "bin", "java.exe");
        var child = new ProcessBuilder(java.toString(), "-cp", System.getProperty("java.class.path"),
            Child.class.getName(), mode, root.toString()).start();
        var capture = new Capture();
        return new Running(child, new ProcessChannel(child, UUID.randomUUID(), UUID.randomUUID(), capture), capture);
    }

    static void await(java.util.function.BooleanSupplier condition) throws Exception {
        long end = System.nanoTime() + Duration.ofSeconds(8).toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < end)
            Thread.sleep(10);
        assertTrue(condition.getAsBoolean(), "Expected actual child observation before deadline");
    }

    @Test
    void actualChildHandshakeRequestResultAndStopReleasePipes() throws Exception {
        try (var running = launch("normal")) {
            assertThrows(ApiException.class, () -> running.channel.request(REQUEST, DEADLINE));
            running.channel.handshake("example.owner", "1.2.3", DEADLINE);
            var result = running.channel.request(REQUEST, DEADLINE).toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertEquals(Outcome.SUCCEEDED, result.outcome());
            assertEquals("{\"accepted\":true}", result.value().orElseThrow().jsonObject());
            assertFalse(running.channel.exitCode().toCompletableFuture().isDone());
            running.channel.close();
            assertEquals(0, running.channel.exitCode().toCompletableFuture().get());
            assertFalse(running.child.isAlive());
            assertTrue(running.capture.problems.isEmpty(), running.capture.problems.toString());
            assertThrows(ApiException.class, () -> running.channel.request(REQUEST, DEADLINE));
            assertEquals(List.of("HELLO:example.owner:1.2.3", "REQUEST", "STOP"),
                Files.readAllLines(root.resolve("events")));
        }
    }

    @Test
    void timeoutAndRepeatedCancelSendOneCancelWithoutReplayingRequest() throws Exception {
        try (var running = launch("hold")) {
            running.channel.handshake("example.owner", "1", DEADLINE);
            var result = running.channel.request(REQUEST, Duration.ofMillis(80)).toCompletableFuture().get(3,
                TimeUnit.SECONDS);
            assertEquals(Outcome.UNKNOWN, result.outcome());
            assertTrue(running.capture.uncertain.get());
            running.channel.requestCancel();
            running.channel.requestCancel();
            await(() -> {
                try {
                    return Files.readAllLines(root.resolve("events")).contains("CANCEL");
                } catch (IOException unavailable) {
                    return false;
                }
            });
            running.channel.close();
            var events = Files.readAllLines(root.resolve("events"));
            assertEquals(1, events.stream().filter("REQUEST"::equals).count());
            assertEquals(1, events.stream().filter("CANCEL"::equals).count());
        }
    }

    @Test
    void thirtyTwoUnsettledRequestsBoundAdmissionAndEofKeepsThemUnknown() throws Exception {
        try (var running = launch("hold")) {
            running.channel.handshake("example.owner", "1", DEADLINE);
            var requests = new ArrayList<CompletableFuture<OperationResult<ProcessMessage>>>();
            for (int i = 0; i < 32; i++)
                requests.add(running.channel.request(REQUEST, Duration.ofMinutes(1)).toCompletableFuture());
            assertEquals(ProblemCode.BUSY,
                assertThrows(ApiException.class, () -> running.channel.request(REQUEST, DEADLINE)).problem()
                    .code());
            running.channel.close();
            for (var request : requests)
                assertEquals(Outcome.UNKNOWN, request.get(3, TimeUnit.SECONDS).outcome());
        }
    }

    @Test
    void blockedStdinKeepsAThirtyTwoFrameQueueAndRejectsAdditionalControlFrames() throws Exception {
        try (var running = launch("blocked-input")) {
            running.channel.handshake("example.owner", "1", DEADLINE);
            var large = new ProcessMessage("example.work", "{\"data\":\"" + "x".repeat(500000) + "\"}");
            for (int i = 0; i < 32; i++)
                running.channel.request(large, Duration.ofSeconds(20));
            var failure = assertThrows(ApiException.class, running.channel::requestCancel);
            assertEquals("PROCESS_WRITE_LIMIT", failure.problem().eventCode());
            Files.writeString(root.resolve("release-input"), "continue");
            await(() -> {
                try {
                    return Files.readAllLines(root.resolve("events")).stream().filter("REQUEST"::equals).count() == 32;
                } catch (IOException unavailable) {
                    return false;
                }
            });
        } finally {
            Files.writeString(root.resolve("release-input"), "fixture cleanup");
        }
    }

    @Test
    void wrongIdentitiesAndConflictingDuplicatesNeverOverwriteTrustedResults() throws Exception {
        for (String mode : List.of("wrong-task", "unknown-request", "plain-output", "conflicting", "duplicate")) {
            try (var running = launch(mode)) {
                running.channel.handshake("example.owner", "1", DEADLINE);
                var result = running.channel.request(REQUEST, DEADLINE).toCompletableFuture().get(5, TimeUnit.SECONDS);
                assertEquals(
                    mode.equals("conflicting") || mode.equals("duplicate") ? Outcome.SUCCEEDED : Outcome.UNKNOWN,
                    result.outcome());
                if (!mode.equals("duplicate"))
                    await(() -> !running.capture.problems.isEmpty());
                running.channel.close();
                if (mode.equals("duplicate"))
                    assertTrue(running.capture.problems.isEmpty(), running.capture.problems.toString());
                else
                    assertTrue(
                        running.capture.problems.stream().anyMatch(p -> p.eventCode().equals("PROCESS_PROTOCOL")));
            }
        }
    }

    @Test
    void stoppedAcknowledgementIsNotPhysicalExit() throws Exception {
        try (var running = launch("delayed-stop"); var worker = Executors.newVirtualThreadPerTaskExecutor()) {
            running.channel.handshake("example.owner", "1", DEADLINE);
            var close = worker.submit(() -> {
                running.channel.close();
                return true;
            });
            await(() -> Files.exists(root.resolve("stopped")));
            assertTrue(running.child.isAlive());
            assertFalse(close.isDone());
            assertFalse(running.channel.exitCode().toCompletableFuture().isDone());
            Files.writeString(root.resolve("release"), "exit now");
            assertTrue(close.get(5, TimeUnit.SECONDS));
            assertFalse(running.child.isAlive());
        } finally {
            Files.writeString(root.resolve("release"), "fixture cleanup");
        }
    }

    @Test
    void stopDeadlineReportsStillAliveAndRefreshesTheBlockerWithoutKilling() throws Exception {
        try (var running = launch("late-stop")) {
            running.channel.handshake("example.owner", "1", DEADLINE);
            var failure = assertThrows(ApiException.class, running.channel::close);
            assertEquals("PROCESS_EXIT_PENDING", failure.problem().eventCode());
            assertTrue(running.child.isAlive());
            assertFalse(running.channel.exitCode().toCompletableFuture().isDone());
            await(() -> running.capture.waits.get() > 0);
            assertTrue(running.capture.stopAcknowledged.get());
            Files.writeString(root.resolve("release"), "exit now");
            running.channel.close();
        } finally {
            Files.writeString(root.resolve("release"), "fixture cleanup");
        }
    }

    @Test
    void stderrFloodIsConsumedAndAggregatedWithoutBlockingProtocol() throws Exception {
        try (var running = launch("stderr")) {
            running.channel.handshake("example.owner", "1", DEADLINE);
            assertEquals(Outcome.SUCCEEDED, running.channel.request(REQUEST, DEADLINE).toCompletableFuture()
                .get(5, TimeUnit.SECONDS).outcome());
            running.channel.close();
            assertFalse(running.capture.diagnostics.isEmpty());
            assertTrue(running.capture.truncated.get());
            assertTrue(running.capture.diagnostics.stream().allMatch(s -> s.length() <= 8192));
            assertTrue(running.capture.diagnostics.size() < 10, "A pipe flood must not become one log per chunk");
        }
    }

    @Test
    void nonzeroExitIsReportedSeparatelyWithoutReplacingTrustedBusinessResult() throws Exception {
        try (var running = launch("nonzero")) {
            running.channel.handshake("example.owner", "1", DEADLINE);
            var result = running.channel.request(REQUEST, DEADLINE).toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertEquals(Outcome.SUCCEEDED, result.outcome());
            running.channel.close();
            assertEquals(7, running.channel.exitCode().toCompletableFuture().get());
            assertTrue(running.capture.problems.stream().anyMatch(p -> p.eventCode().equals("PROCESS_EXIT_NONZERO")));
            assertFalse(running.capture.uncertain.get());
        }
    }

    @Test
    void invalidReadinessAndHandshakeDeadlineLeaveActualChildOwnedUntilClose() throws Exception {
        for (String mode : List.of("bad-ready", "no-ready")) {
            try (var running = launch(mode)) {
                assertThrows(ApiException.class,
                    () -> running.channel.handshake("example.owner", "1", Duration.ofSeconds(1)));
                assertTrue(running.child.isAlive());
                assertThrows(ApiException.class, () -> running.channel.request(REQUEST, DEADLINE));
                running.channel.close();
                assertFalse(running.child.isAlive());
            }
        }
    }

    record Running(Process child, ProcessChannel channel, Capture capture) implements AutoCloseable {
        @Override
        public void close() throws Exception {
            channel.close();
            assertFalse(child.isAlive(), "Task-created child must have exited");
        }
    }

    static final class Capture implements ProcessChannel.Observer {
        final List<Problem> problems = new CopyOnWriteArrayList<>();

        final List<String> diagnostics = new CopyOnWriteArrayList<>();

        final AtomicBoolean uncertain = new AtomicBoolean(), truncated = new AtomicBoolean();

        final AtomicInteger waits = new AtomicInteger();

        final AtomicBoolean stopAcknowledged = new AtomicBoolean();

        public boolean cancellationRequested() {
            return false;
        }

        public void diagnostic(String excerpt, boolean cut) {
            diagnostics.add(excerpt);
            if (cut)
                truncated.set(true);
        }

        public void failure(Problem problem, boolean unknown) {
            problems.add(problem);
            if (unknown)
                uncertain.set(true);
        }

        public void waiting(boolean acknowledged) {
            waits.incrementAndGet();
            stopAcknowledged.set(acknowledged);
        }
    }

    public static final class Child {
        public static void main(String[] args) throws Exception {
            Thread.ofPlatform().daemon().start(() -> {
                try {
                    Thread.sleep(args[0].equals("late-stop") ? 45000 : 15000);
                    System.exit(92);
                } catch (InterruptedException ignored) {
                }
            });
            String mode = args[0];
            Path root = Path.of(args[1]);
            while (true) {
                var input = ProcessProtocol.read(System.in);
                if (input.isEmpty())
                    return;
                var frame = input.get();
                if (frame.kind() == Kind.HELLO) {
                    var identity = new com.fasterxml.jackson.databind.ObjectMapper().readTree(frame.payload());
                    event(root, "HELLO:" + identity.path("ownerId").asText() + ":"
                        + identity.path("ownerVersion").asText());
                    if (!mode.equals("no-ready"))
                        send(new ProcessProtocol.Frame(frame.process(), frame.task(), frame.request(), Kind.READY,
                            frame.type(), mode.equals("bad-ready") ? "{\"bad\":true}" : "{}"));
                    if (mode.equals("blocked-input"))
                        while (!Files.exists(root.resolve("release-input")))
                            Thread.sleep(10);
                } else if (frame.kind() == Kind.REQUEST) {
                    event(root, "REQUEST");
                    if (mode.equals("hold"))
                        continue;
                    if (mode.equals("stderr")) {
                        for (int i = 0; i < 2048; i++)
                            System.err.write(new byte[8192]);
                        System.err.flush();
                    }
                    if (mode.equals("plain-output")) {
                        System.out.println("non-protocol text");
                        System.out.flush();
                        continue;
                    }
                    var response = new ProcessProtocol.Frame(frame.process(),
                        mode.equals("wrong-task")
                            ? new UUID(frame.task().getMostSignificantBits() ^ 1L,
                            frame.task().getLeastSignificantBits())
                            : frame.task(),
                        mode.equals("unknown-request")
                            ? new UUID(frame.request().getMostSignificantBits() ^ 1L,
                            frame.request().getLeastSignificantBits())
                            : frame.request(),
                        Kind.RESULT, frame.type(),
                        "{\"outcome\":\"SUCCEEDED\",\"value\":{\"accepted\":true},\"problem\":null}");
                    send(response);
                    if (mode.equals("duplicate"))
                        send(new ProcessProtocol.Frame(response.process(), response.task(), response.request(),
                            response.kind(), response.type(),
                            "{\"problem\":null,\"value\":{\"accepted\":true},\"outcome\":\"SUCCEEDED\"}"));
                    if (mode.equals("conflicting"))
                        send(new ProcessProtocol.Frame(response.process(), response.task(), response.request(),
                            response.kind(), response.type(),
                            "{\"outcome\":\"SUCCEEDED\",\"value\":{\"accepted\":false},\"problem\":null}"));
                } else if (frame.kind() == Kind.CANCEL)
                    event(root, "CANCEL");
                else if (frame.kind() == Kind.STOP) {
                    event(root, "STOP");
                    send(new ProcessProtocol.Frame(frame.process(), frame.task(), frame.request(), Kind.STOPPED,
                        frame.type(), "{}"));
                    if (mode.equals("nonzero"))
                        System.exit(7);
                    if (mode.equals("delayed-stop") || mode.equals("late-stop")) {
                        Files.writeString(root.resolve("stopped"), "acknowledged");
                        while (!Files.exists(root.resolve("release")))
                            Thread.sleep(10);
                    }
                    return;
                }
            }
        }

        static void send(ProcessProtocol.Frame frame) throws Exception {
            System.out.write(ProcessProtocol.encode(frame));
            System.out.flush();
        }

        static void event(Path root, String value) throws Exception {
            Files.writeString(root.resolve("events"), value + "\n", StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);
        }
    }
}
