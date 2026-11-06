package gold.debug.windowstolinux.app.linux.ssh.connect;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.sshd.common.util.buffer.*;
import org.apache.sshd.client.future.DefaultOpenFuture;
import org.apache.sshd.common.io.IoWriteFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ChannelOpenMetadataTest {
    @TempDir Path root;
    private static Map<String, String> facts(ChannelOpenMetadata metadata) {
        var facts = new LinkedHashMap<String, String>();
        metadata.appendTo(facts);
        return Map.copyOf(facts);
    }
    private static Buffer packet(int opcode, long id) {
        var b = new ByteArrayBuffer();
        b.putByte((byte) opcode);
        if (opcode == 90) b.putString("session");
        b.putUInt(id);
        b.putUInt(0xffffffffL);
        b.putUInt(32768);
        b.putUInt(32768);
        return b;
    }
    @Test void packetObservationTimesUseOriginalCommandMonotonicStart() {
        long commandStarted = System.nanoTime() - Duration.ofSeconds(2).toNanos();
        var metadata = new ChannelOpenMetadata(7, commandStarted);
        assertTrue(metadata.writing(packet(90, 7)));
        metadata.received(packet(91, 7));
        metadata.writeFailed(new IOException());
        long observedEnd = (System.nanoTime() - commandStarted) / 1_000_000;
        var facts = facts(metadata);
        for (String key : List.of("channelOpenWriteStartedMillis", "channelOpenReplyMillis", "channelOpenWriteCompletedMillis")) {
            long value = Long.parseLong(facts.get(key));
            assertTrue(value >= 2000 && value <= observedEnd, key + " must use the original command origin");
        }
    }

    @Test void exactPacketsAreNonMutatingAndOtherChannelsAndAuthAreIgnored() {
        var metadata = new ChannelOpenMetadata(7, System.nanoTime());
        var other = new ChannelOpenMetadata(8, System.nanoTime());
        for (int opcode : List.of(50, 90, 91, 92)) {
            var b = packet(opcode, 7);
            byte[] original = b.array().clone();
            int rpos = b.rpos(), wpos = b.wpos();
            assertEquals(opcode == 90, metadata.writing(b));
            assertFalse(other.writing(b));
            metadata.received(b);
            other.received(b);
            assertEquals(rpos, b.rpos());
            assertEquals(wpos, b.wpos());
            assertArrayEquals(original, b.array());
        }
        assertEquals("FAILURE", facts(metadata).get("channelOpenReply"));
        assertEquals("4294967295", facts(metadata).get("channelOpenFailureReason"));
        assertEquals("NOT_OBSERVED", facts(other).get("channelOpenWriteState"));
        assertEquals("NOT_OBSERVED", facts(other).get("channelOpenReply"));
        for (int length = 0; length < 9; length++) {
            var b = new ByteArrayBuffer(new byte[length]);
            assertDoesNotThrow(() -> { metadata.writing(b); metadata.received(b); });
        }
        var auth = new ByteArrayBuffer() {
            @Override public long rawUInt(int pos) { throw new AssertionError("AUTH fields must not be read"); }
        };
        auth.putByte((byte) 50);
        assertFalse(metadata.writing(auth));
        metadata.received(auth);
        assertEquals("AVAILABLE", facts(metadata).get("channelOpenObservation"));
    }
    @Test void pendingWrittenFailedAndLateOpenRemainSeparateFromPeerReply() {
        var metadata = new ChannelOpenMetadata(7, System.nanoTime());
        assertTrue(metadata.writing(packet(90, 7)));
        var state = new AtomicReference<>("PENDING");
        var failure = new IOException() {
            @Override public String getMessage() { throw new AssertionError("no message"); }
            @Override public StackTraceElement[] getStackTrace() { throw new AssertionError("no stack"); }
        };
        var write = (IoWriteFuture) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
            new Class<?>[]{IoWriteFuture.class}, (proxy, method, args) -> switch (method.getName()) {
                case "isDone" -> !state.get().equals("PENDING");
                case "isWritten" -> state.get().equals("WRITTEN");
                case "getException" -> state.get().equals("FAILED") ? failure : null;
                default -> throw new AssertionError(method.getName());
            });
        metadata.written(write);
        assertEquals("PENDING", facts(metadata).get("channelOpenWriteState"));
        assertEquals("UNKNOWN", facts(metadata).get("channelOpenWriteCompletedMillis"));
        state.set("WRITTEN"); metadata.written(write);
        assertEquals("WRITTEN", facts(metadata).get("channelOpenWriteState"));
        assertEquals("NOT_OBSERVED", facts(metadata).get("channelOpenReply"));
        var opening = new DefaultOpenFuture("synthetic", null);
        metadata.opening(opening);
        metadata.received(packet(91, 7));
        var result = facts(metadata);
        assertEquals("CONFIRMATION", result.get("channelOpenReply"));
        assertEquals("false", result.get("channelOpenFutureDone"));
        opening.setOpened();
        assertEquals("true", facts(metadata).get("channelOpenFutureOpened"));
        assertEquals("false", result.get("channelOpenFutureOpened"), "Earlier result stays a result-time snapshot");
        state.set("FAILED"); metadata.written(write);
        assertEquals("FAILED", facts(metadata).get("channelOpenWriteState"));
        assertEquals("IO", facts(metadata).get("channelOpenWriteFailureType"));
        var rejected = new DefaultOpenFuture("synthetic", null);
        rejected.setException(new org.apache.sshd.common.channel.exception.SshChannelOpenException(7, -1, "private"));
        metadata.opening(rejected);
        assertEquals("4294967295", facts(metadata).get("channelOpenFutureFailureReason"));
        assertFalse(facts(metadata).toString().contains("private"));
    }
    @Test void openingCancellationAndSynchronousWriteFailureDoNotInventPeerReply() {
        var metadata = new ChannelOpenMetadata(7, System.nanoTime());
        assertTrue(metadata.writing(packet(90, 7)));
        var original = new IOException() {
            @Override public String getMessage() { throw new AssertionError("No exception body read"); }
        };
        metadata.writeFailed(original);
        var opening = new DefaultOpenFuture("synthetic", null);
        metadata.opening(opening);
        opening.cancel();
        var facts = facts(metadata);
        assertEquals("FAILED", facts.get("channelOpenWriteState"));
        assertEquals("IO", facts.get("channelOpenWriteFailureType"));
        assertEquals("true", facts.get("channelOpenFutureCanceled"));
        assertEquals("false", facts.get("channelOpenFutureOpened"));
        assertEquals("NOT_OBSERVED", facts.get("channelOpenReply"));
        var unassigned = new ChannelOpenMetadata(-1, System.nanoTime());
        assertFalse(unassigned.writing(packet(90, 0xffffffffL)));
        unassigned.received(packet(91, 0xffffffffL));
        assertEquals("NOT_OBSERVED", facts(unassigned).get("channelOpenReply"));
    }

    @Test void parserFailureIsMetadataOnlyAndRetainsNoPacket() {
        var metadata = new ChannelOpenMetadata(7, System.nanoTime());
        var broken = new ByteArrayBuffer() {
            @Override public byte rawByte(int pos) { throw new IllegalArgumentException("private"); }
        };
        broken.putRawBytes(new byte[20]);
        assertFalse(metadata.writing(broken));
        assertDoesNotThrow(() -> metadata.received(broken));
        assertEquals("UNAVAILABLE", facts(metadata).get("channelOpenObservation"));
        for (var field : ChannelOpenMetadata.class.getDeclaredFields())
            assertFalse(Buffer.class.isAssignableFrom(field.getType()));
        assertFalse(facts(metadata).toString().contains("private"));
    }
    @Test void connectorUsesExactHooksOnLoopbackAndKeepsOriginalNonzeroResult() throws Exception {
        try (var fixture = new SshCommandExecutionTest.WireFixture(root, command -> {
            command.input.readAllBytes();
            command.exit.onExit(9);
        }); var connector = new SshConnector();
             var connection = SshCommandExecutionTest.connect(connector, fixture.server)) {
            var result = connection.execute(new gold.debug.windowstolinux.appapi.linux.command.CommandRequest(
                "private author", new byte[0], Duration.ofSeconds(3), 100), () -> false);
            assertEquals(gold.debug.windowstolinux.appapi.task.result.TaskResult.FAILED, result.outcome());
            var facts = result.problem().orElseThrow().context();
            assertEquals("SSH_COMMAND_NONZERO", result.problem().orElseThrow().eventCode());
            assertEquals("WRITTEN", facts.get("channelOpenWriteState"));
            assertEquals("CONFIRMATION", facts.get("channelOpenReply"));
            assertEquals("true", facts.get("channelOpenFutureOpened"));
            assertEquals("RESULT", facts.get("channelOpenSnapshotPhase"));
            assertFalse(result.toString().contains("private"));
        }
    }
    @Test void observerFailurePreservesOriginalSessionWriteAndReceiveOperations() throws Exception {
        try (var fixture = new SshCommandExecutionTest.WireFixture(root, command -> {});
             var connector = new SshConnector();
             var connection = SshCommandExecutionTest.connect(connector, fixture.server)) {
            var field = connection.getClass().getDeclaredField("session"); field.setAccessible(true);
            var session = (ChannelOpenMetadata.Session) field.get(connection);
            var metadata = new ChannelOpenMetadata(7, System.nanoTime());
            session.bind(metadata);
            var observerRead = new java.util.concurrent.atomic.AtomicBoolean();
            var ignored = new ByteArrayBuffer() {
                @Override public byte rawByte(int pos) {
                    if (observerRead.compareAndSet(false, true)) throw new IllegalArgumentException("private observer failure");
                    return super.rawByte(pos);
                }
            };
            ignored.wpos(5); ignored.rpos(5);
            ignored.putByte((byte) 2); ignored.putString("fixture");
            assertTrue(session.doWritePacket(ignored).verify(Duration.ofSeconds(2)).isWritten());
            var incoming = new ByteArrayBuffer() {
                @Override public byte rawByte(int pos) { throw new IllegalArgumentException("private observer failure"); }
            };
            incoming.putByte((byte) 2); incoming.putString("fixture");
            assertDoesNotThrow(() -> session.doHandleMessage(incoming));
            assertEquals(incoming.wpos(), incoming.rpos(), "Original SSHD receive handler consumed the message");
            assertEquals("UNAVAILABLE", facts(metadata).get("channelOpenObservation"));
            var original = new IllegalArgumentException("private original transport failure");
            var broken = new ByteArrayBuffer() {
                @Override public byte rawByte(int pos) { throw new IllegalStateException("private observer failure"); }
                @Override public byte[] array() { throw original; }
            };
            broken.wpos(20);
            assertSame(original, assertThrows(IllegalArgumentException.class, () -> session.doWritePacket(broken)));
            session.unbind(metadata);
            var before = facts(metadata);
            var safe = session.createBuffer((byte) 2, 20); safe.putString("fixture");
            assertTrue(session.doWritePacket(safe).verify(Duration.ofSeconds(2)).isWritten());
            assertEquals(before, facts(metadata), "Unbound channel cannot receive late session observations");
        }
    }

    @Test void snapshotKeepsLatestWorkersAndDeadlineCrossingAddsNoWait() throws Exception {
        var owner = new ClientIoLifecycle();
        var factory = ClientIoLifecycle.class.getDeclaredMethod("worker", Runnable.class, String.class);
        factory.setAccessible(true);
        for (int i = 0; i < 4; i++) factory.invoke(owner, (Runnable) () -> {}, "io");
        var field = ClientIoLifecycle.class.getDeclaredField("recent");
        field.setAccessible(true);
        @SuppressWarnings("unchecked") var recent = (java.util.concurrent.atomic.AtomicReferenceArray<Thread>) field.get(owner);
        assertTrue(recent.get(0).getName().endsWith("-3"));
        assertTrue(recent.get(1).getName().endsWith("-4"));
        var reads = new java.util.concurrent.atomic.AtomicInteger();
        Thread delayed = new Thread() {
            @Override public State getState() {
                reads.incrementAndGet();
                try { new java.util.concurrent.CountDownLatch(1).await(130, java.util.concurrent.TimeUnit.MILLISECONDS); }
                catch (InterruptedException failure) { throw new AssertionError(failure); }
                return State.NEW;
            }
        };
        recent.set(0, delayed);
        var session = (org.apache.sshd.client.session.ClientSession) java.lang.reflect.Proxy.newProxyInstance(
            getClass().getClassLoader(), new Class<?>[]{org.apache.sshd.client.session.ClientSession.class},
            (proxy, method, args) -> { throw new AssertionError("No transport operation expected"); });
        try (var request = new gold.debug.windowstolinux.appapi.linux.command.CommandRequest(
            "fixture", new byte[0], Duration.ofMillis(100), 100)) {
            var execution = new SshCommandExecution(session, request, owner);
            var phase = SshCommandExecution.class.getDeclaredField("phase"); phase.setAccessible(true);
            @SuppressWarnings({"rawtypes", "unchecked"}) Object opening = Enum.valueOf((Class) phase.getType(), "CHANNEL_OPENING");
            phase.set(execution, opening);
            var started = SshCommandExecution.class.getDeclaredField("started"); started.setAccessible(true);
            started.setLong(execution, System.nanoTime());
            var remaining = SshCommandExecution.class.getDeclaredMethod("remainingMillis", long.class); remaining.setAccessible(true);
            var failure = assertThrows(java.lang.reflect.InvocationTargetException.class,
                () -> remaining.invoke(execution, started.getLong(execution)));
            assertInstanceOf(java.util.concurrent.TimeoutException.class, failure.getCause());
            var snapshot = SshCommandExecution.class.getDeclaredField("deadlineWorkers"); snapshot.setAccessible(true);
            var facts = (Map<?, ?>) snapshot.get(execution);
            assertEquals("NOT_OBSERVED", facts.get("channelOpenWorkerSnapshot"));
            assertEquals("0", facts.get("channelOpenWorkerSnapshotRemainingMillis"));
            assertFalse(facts.containsKey("channelOpenWorkerIo0State"));
            assertThrows(java.lang.reflect.InvocationTargetException.class,
                () -> remaining.invoke(execution, started.getLong(execution)));
            assertEquals(1, reads.get(), "At most one snapshot even across deadline");
            assertTrue(execution.close());
        }
        assertTrue(owner.close(Duration.ofSeconds(1)));
    }

    @Test void workerSnapshotHasOnlyOwnedRolesStatesAndNoNames() throws Exception {
        var owner = new ClientIoLifecycle();
        var factory = ClientIoLifecycle.class.getDeclaredMethod("worker", Runnable.class, String.class);
        factory.setAccessible(true);
        for (String role : List.of("io", "resume"))
            for (int i = 0; i < 2; i++) factory.invoke(owner, (Runnable) () -> {}, role);
        var facts = owner.workerSnapshot();
        assertEquals("2", facts.get("channelOpenWorkerIoCount"));
        assertEquals("2", facts.get("channelOpenWorkerResumeCount"));
        assertEquals(11, facts.size());
        assertEquals("NEW", facts.get("channelOpenWorkerIo0State"));
        assertFalse(facts.toString().contains("platform-ssh"));
        assertTrue(owner.close(Duration.ofSeconds(1)));
    }
}
