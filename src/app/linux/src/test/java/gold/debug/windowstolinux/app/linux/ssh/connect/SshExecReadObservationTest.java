package gold.debug.windowstolinux.app.linux.ssh.connect;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.channel.ChannelExec;
import org.apache.sshd.client.future.OpenFuture;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.Closeable;
import org.apache.sshd.common.channel.throttle.DefaultChannelStreamWriter;
import org.apache.sshd.common.io.AbstractIoWriteFuture;
import org.apache.sshd.common.io.IoWriteFuture;
import org.apache.sshd.common.util.buffer.Buffer;
import org.apache.sshd.common.util.buffer.ByteArrayBuffer;
import gold.debug.windowstolinux.appapi.linux.command.CommandRequest;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import org.junit.jupiter.api.Test;

/** No sockets: actual SSHD open-success and initial-zero remote-window/input-stream mechanisms. */
class SshExecReadObservationTest {
    @Test void zeroInitialWindowExpiresOnceAtLengthAndRetainsUnknown() throws Exception {
        var owner = new ClientIoLifecycle();
        try (var fixture = new Fixture();
             var request = new CommandRequest("private author", new byte[0], Duration.ofMillis(350), 100)) {
            var execution = new SshCommandExecution(fixture.session, request, owner);
            try {
                var result = execution.run(() -> false);
                var facts = result.problem().orElseThrow().context();
                assertEquals(TaskResult.UNKNOWN, result.outcome());
                assertEquals("SSH_COMMAND_TIMEOUT", result.problem().orElseThrow().eventCode());
                assertEquals("WRITING_LENGTH", facts.get("frameWritePhase"));
                assertEquals("SENDING", facts.get("inputState"));
                assertEquals("0", facts.get("remoteWindowOpenSize"));
                assertEquals("0", facts.get("remoteWindowFailureSize"));
                assertEquals("MAY_HAVE_BEEN_SENT", facts.get("authorPayloadState"));
                assertEquals(1, fixture.dispatches.get());
                assertEquals(1, fixture.execRequests.get());
                assertEquals(0, fixture.data.size());
                assertEquals("NOT_OBSERVED", facts.get("execRequestWriteState"), "A stub session has no packet observer");
                assertEquals("WAITING_FOR_EXIT", facts.get("channelOpenWorkerSnapshotCommandPhase"));
                assertEquals("BEFORE_DEADLINE", facts.get("channelOpenWorkerSnapshot"));
                assertFalse(facts.toString().contains("private"));
            } finally {
                assertTrue(execution.close());
            }
        } finally { assertTrue(owner.close(Duration.ofSeconds(1))); }
    }

    @Test void legitimateZeroWindowBackpressureResumesAndWritesExactFrameOnce() throws Exception {
        try (var fixture = new Fixture(); var request = new CommandRequest("private 汉字", new byte[]{0, 1, -1}, Duration.ofSeconds(2), 100);
             var frame = new SshCommandFrame(request)) {
            fixture.channel.open();
            var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
            var sender = Thread.ofVirtual().unstarted(() -> {
                try (OutputStream input = fixture.channel.getInvertedIn()) { frame.writeTo(input); }
                catch (Throwable problem) { failure.set(problem); }
            });
            sender.start();
            try {
                assertTrue(fixture.inputRequested.await(2, TimeUnit.SECONDS));
                awaitRemoteWindowWait(sender);
                assertEquals(SshCommandFrame.WritePhase.WRITING_LENGTH, frame.writePhase());
                assertEquals(0, fixture.data.size());
                // A different controlled actor expands while Object.wait has released the window lock.
                var adjuster = Thread.ofVirtual().start(() -> {
                    try { fixture.channel.getRemoteWindow().expand(32768); }
                    catch (Throwable problem) { failure.set(problem); }
                });
                adjuster.join(Duration.ofSeconds(2));
                assertFalse(adjuster.isAlive());
                sender.join(Duration.ofSeconds(2));
                assertFalse(sender.isAlive());
                assertNull(failure.get());
                byte[] script = request.script().getBytes(StandardCharsets.UTF_8);
                var expected = new ByteArrayOutputStream();
                expected.write((script.length + "\n").getBytes(StandardCharsets.US_ASCII));
                expected.write(script); expected.write(request.stdin());
                assertArrayEquals(expected.toByteArray(), fixture.data.toByteArray());
                assertEquals(1, fixture.execRequests.get());
                assertEquals(SshCommandFrame.WritePhase.WRITTEN, frame.writePhase());
                assertEquals(0, fixture.channel.getRemoteWindow().getMaxSize(), "Initial size is not a high-water mark");
            } finally {
                fixture.channel.getRemoteWindow().close();
                sender.join(Duration.ofSeconds(2));
                assertFalse(sender.isAlive());
            }
        }
    }

    @Test void realOpenSuccessDoesNotWaitForExecWriteOrWindowAdjustment() throws Exception {
        try (var fixture = new Fixture(true); var request = new CommandRequest("private author", new byte[0], Duration.ofSeconds(2), 100);
             var frame = new SshCommandFrame(request)) {
            OpenFuture opened = fixture.channel.open();
            assertTrue(opened.isOpened(), "Actual handleOpenSuccess completes after dispatching exec, not its write future");
            assertFalse(fixture.execWrite.isDone());
            var facts = new LinkedHashMap<String, String>(); fixture.observed.appendTo(facts);
            assertEquals("PENDING", facts.get("execRequestWriteState"));
            assertEquals("UNKNOWN", facts.get("execRequestWriteCompletedMillis"));
            assertEquals("false", facts.get("execRequestWantReply"), "Original SSHD default stays unchanged");
            assertEquals("UNKNOWN", facts.get("execRequestPeerAcceptance"));
            var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
            var sender = Thread.ofVirtual().start(() -> {
                try (var input = fixture.channel.getInvertedIn()) { frame.writeTo(input); }
                catch (Throwable problem) { failure.set(problem); }
            });
            try {
                assertTrue(fixture.inputRequested.await(2, TimeUnit.SECONDS)); awaitRemoteWindowWait(sender);
                fixture.execWrite.written();
                fixture.observed.appendTo(facts);
                assertEquals("WRITTEN", facts.get("execRequestWriteState"));
                assertEquals("UNKNOWN", facts.get("execRequestPeerAcceptance"));
                assertEquals(SshCommandFrame.WritePhase.WRITING_LENGTH, frame.writePhase());
                assertEquals(0, fixture.data.size());
                fixture.channel.getRemoteWindow().expand(32768);
                sender.join(Duration.ofSeconds(2));
                assertFalse(sender.isAlive()); assertNull(failure.get());
                assertEquals(SshCommandFrame.WritePhase.WRITTEN, frame.writePhase());
                assertEquals(1, fixture.execRequests.get());
                assertNull(fixture.channel.getExitStatus(), "Neither local write nor window space proves command exit");
            } finally {
                fixture.channel.getRemoteWindow().close(); sender.join(Duration.ofSeconds(2)); assertFalse(sender.isAlive());
            }
        }
    }

    private static void awaitRemoteWindowWait(Thread sender) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (System.nanoTime() < deadline) {
            if (Arrays.stream(sender.getStackTrace()).anyMatch(f -> f.getClassName().equals("org.apache.sshd.common.channel.RemoteWindow")
                && f.getMethodName().equals("waitForSpace"))) return;
            Thread.onSpinWait();
        }
        fail("Sender did not enter the actual RemoteWindow wait");
    }

    static final class PendingWrite extends AbstractIoWriteFuture {
        PendingWrite() { super("synthetic", null); }
        void written() { setValue(Boolean.TRUE); }
        void failed(Throwable failure) { setValue(failure); }
    }

    static final class Fixture implements AutoCloseable {
        final SshClient manager = SshClient.setUpDefaultClient(); // Not started; no socket or executor.
        final AtomicInteger dispatches = new AtomicInteger(), execRequests = new AtomicInteger();
        final ByteArrayOutputStream data = new ByteArrayOutputStream();
        final CountDownLatch inputRequested = new CountDownLatch(1);
        final PendingWrite execWrite = new PendingWrite();
        final ChannelOpenMetadata observed = new ChannelOpenMetadata(7, System.nanoTime());
        final ClientSession session;
        final ChannelExec channel;
        Fixture() throws Exception { this(false); }
        Fixture(boolean observe) throws Exception {
            channel = new ChannelExec(SshCommandFrame.COMMAND, null, Map.of()) {
                @Override public synchronized OpenFuture open() throws java.io.IOException {
                    var future = super.open();
                    if (observe) {
                        var confirmation = new ByteArrayBuffer(); confirmation.putByte((byte) 91);
                        confirmation.putUInt(7); confirmation.putUInt(19); confirmation.putUInt(0); confirmation.putUInt(32768);
                        observed.received(confirmation);
                    }
                    handleOpenSuccess(19, 0, 32768, new ByteArrayBuffer());
                    return future;
                }
                @Override public OutputStream getInvertedIn() { inputRequested.countDown(); return super.getInvertedIn(); }
                @Override protected void preClose() {
                    try { getRemoteWindow().close(); }
                    catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
                }
                @Override protected Closeable getInnerCloseable() { return builder().build(); }
            };
            channel.setChannelStreamWriterResolver((ch, cmd) -> new DefaultChannelStreamWriter(ch));
            session = (ClientSession) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{ClientSession.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "createExecChannel" -> { dispatches.incrementAndGet(); assertEquals(SshCommandFrame.COMMAND, args[0]); yield channel; }
                    case "createBuffer" -> { var b = new ByteArrayBuffer(); b.wpos(5); b.rpos(5); b.putByte((byte) args[0]); yield b; }
                    case "writePacket" -> {
                        Buffer b = (Buffer) args[0]; int pos = b.rpos(); int opcode = b.rawByte(pos) & 255;
                        if (opcode == 98) {
                            execRequests.incrementAndGet();
                            if (observe && observed.execWriting(b)) execWrite.addListener(observed::execWritten);
                            yield execWrite;
                        }
                        if (opcode == 94) data.write(b.array(), pos + 9, (int) b.rawUInt(pos + 5));
                        yield AbstractIoWriteFuture.fulfilled("synthetic", Boolean.TRUE);
                    }
                    case "getFactoryManager", "getParentPropertyResolver" -> manager;
                    case "getProperties" -> Map.of();
                    case "toString" -> "synthetic-session";
                    case "isOpen", "isAuthenticated" -> true;
                    case "getChannelListenerProxy", "getSessionListenerProxy", "getIoSession", "getKexState", "resetIdleTimeout",
                         "addSessionListener", "removeSessionListener" -> null;
                    default -> throw new AssertionError(method.getName());
                });
            channel.init(null, session, 7);
            channel.setOut(OutputStream.nullOutputStream()); channel.setErr(OutputStream.nullOutputStream());
        }
        @Override public void close() { channel.close(true); manager.close(true); }
    }
}
