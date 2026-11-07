package gold.debug.windowstolinux.app.linux.ssh.connect;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.net.SocketAddress;
import java.net.SocketOption;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.channels.spi.AsynchronousChannelProvider;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.common.io.IoHandler;
import org.apache.sshd.common.io.IoSession;
import org.apache.sshd.common.io.nio2.*;
import org.apache.sshd.common.util.Readable;
import org.junit.jupiter.api.Test;

class SshSocketReadDiagnosticsTest {
    @Test void actualNio2BlockedCompletionIsDistinctFromPendingSocketAndQueuedResume() throws Exception {
        var manager = SshClient.setUpDefaultClient(); var raw = new FakeSocket();
        var observed = new SshSocketReadDiagnostics(raw); var group = new FakeGroup(); var resume = new DirectExecutor();
        var callback = Executors.newSingleThreadExecutor();
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var handler = new SshReadDiagnostics(new IoHandler() {
            public void sessionCreated(IoSession session) { }
            public void sessionClosed(IoSession session) { }
            public void exceptionCaught(IoSession session, Throwable failure) { fail(failure); }
            public void messageReceived(IoSession session, Readable message) throws Exception {
                entered.countDown(); assertTrue(release.await(2, TimeUnit.SECONDS));
            }
        });
        var connector = new Nio2Connector(null, manager, handler, group, resume);
        var session = new Nio2Session(connector, manager, handler, observed, null); observed.attach(session);
        try {
            handler.sessionCreated(session); session.startReading(16); raw.buffer.put(new byte[]{1, 2, 3});
            var completion = callback.submit(() -> raw.finish(false));
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            var socket = facts(session); var ssh = new LinkedHashMap<String, String>(); SshReadDiagnostics.appendTo(session, ssh);
            assertEquals("1", socket.get("transportSocketReadDeliveryActive"));
            assertEquals("0", socket.get("transportSocketReadDeliveryReturned"));
            assertEquals("false", socket.get("transportSocketReadPending"));
            assertEquals("true", ssh.get("transportReadHandlerActive"));
            assertEquals(1, raw.readCalls); assertEquals(0, raw.writeCalls);
            release.countDown(); completion.get(1, TimeUnit.SECONDS);
            socket = facts(session); assertEquals("0", socket.get("transportSocketReadDeliveryActive"));
            assertEquals("1", socket.get("transportSocketReadDeliveryReturned"));
            assertEquals("true", socket.get("transportSocketReadPending"));
            assertEquals(2, raw.readCalls); assertEquals(0, raw.writeCalls);
        } finally {
            release.countDown(); callback.shutdownNow(); assertTrue(callback.awaitTermination(2, TimeUnit.SECONDS));
            assertTrue(session.close(true).awaitUninterruptibly(Duration.ofSeconds(1)));
            connector.close(true); resume.shutdown(); group.shutdown(); manager.close(true);
        }
    }

    @Test void completionDeliveryCountsNestedInlineAndAsyncWithoutChangingOriginalTrace() throws Exception {
        for (boolean inline : List.of(false, true)) {
            var traces = new ArrayList<List<String>>();
            for (boolean wrapped : List.of(false, true)) {
                try (var raw = new FakeSocket(); var observed = new SshSocketReadDiagnostics(raw)) {
                    var io = attributes(); observed.attach(io);
                    AsynchronousSocketChannel socket = wrapped ? observed : raw;
                    var trace = new ArrayList<String>();
                    raw.mode = inline ? "SYNC_OK" : "ASYNC_OK";
                    var callbacks = new AtomicInteger();
                    var handler = new CompletionHandler<Integer, Object>() {
                        public void completed(Integer value, Object attachment) {
                            int index = callbacks.incrementAndGet(); trace.add("enter:" + index);
                            if (wrapped) assertEquals(Integer.toString(index == 2 && inline ? 2 : 1), facts(io).get("transportSocketReadDeliveryActive"));
                            if (index == 1) {
                                socket.read(ByteBuffer.allocate(4), 0, TimeUnit.SECONDS, null, this);
                                trace.add("next-returned");
                                if (wrapped && !inline) {
                                    var sample = facts(io);
                                    assertEquals("1", sample.get("transportSocketReadDeliveryActive"));
                                    assertEquals("1", sample.get("transportSocketReadAwaitingCallbacks"));
                                    assertEquals("2", sample.get("transportSocketReadReturned"));
                                }
                            }
                            trace.add("return:" + index);
                        }
                        public void failed(Throwable failure, Object attachment) { fail(); }
                    };
                    socket.read(ByteBuffer.allocate(4), 0, TimeUnit.SECONDS, null, handler); trace.add("first-returned");
                    if (!inline) { raw.finish(false); raw.finish(false); }
                    assertEquals(2, raw.readCalls); assertEquals(0, raw.writeCalls);
                    assertEquals(2, callbacks.get());
                    if (wrapped) {
                        var f = facts(io);
                        assertEquals("2", f.get("transportSocketReadDeliveryEntered"));
                        assertEquals("2", f.get("transportSocketReadDeliveryReturned"));
                        assertEquals("0", f.get("transportSocketReadDeliveryThrew"));
                        assertEquals("0", f.get("transportSocketReadDeliveryActive"));
                        assertEquals("0", f.get("transportSocketReadAwaitingCallbacks"));
                        assertEquals("0", f.get("transportSocketReadActiveCalls"));
                    }
                    traces.add(trace);
                }
            }
            assertEquals(traces.get(0), traces.get(1), "Observation must preserve inline/asynchronous nesting");
        }
    }

    @Test void callbackThrowPreservesIdentityAndDuplicateDeliveryIsCountedSeparatelyFromTerminal() throws Exception {
        for (String mode : List.of("SYNC_OK", "ASYNC_OK", "SYNC_FAIL", "ASYNC_FAIL")) {
            try (var raw = new FakeSocket(); var observed = new SshSocketReadDiagnostics(raw)) {
                var io = attributes(); observed.attach(io); raw.mode = mode;
                var original = new AssertionError("private handler failure");
                var handler = new CompletionHandler<Integer, Object>() {
                    public void completed(Integer value, Object attachment) { throw original; }
                    public void failed(Throwable failure, Object attachment) { assertSame(raw.failure, failure); throw original; }
                };
                if (mode.startsWith("SYNC")) assertSame(original, assertThrows(AssertionError.class,
                    () -> observed.read(ByteBuffer.allocate(4), 0, TimeUnit.SECONDS, null, handler)));
                else {
                    observed.read(ByteBuffer.allocate(4), 0, TimeUnit.SECONDS, null, handler);
                    var producer = raw.pending;
                    assertSame(original, assertThrows(AssertionError.class, () -> raw.finish(mode.endsWith("FAIL"))));
                    assertSame(original, assertThrows(AssertionError.class, () -> producer.completed(3, null)));
                }
                var f = facts(io); String deliveries = mode.startsWith("SYNC") ? "1" : "2";
                assertEquals(deliveries, f.get("transportSocketReadDeliveryEntered"));
                assertEquals(deliveries, f.get("transportSocketReadDeliveryThrew"));
                assertEquals("0", f.get("transportSocketReadDeliveryReturned"));
                assertEquals("0", f.get("transportSocketReadDeliveryActive"));
                assertEquals("0", f.get("transportSocketReadAwaitingCallbacks"));
                assertEquals("0", f.get("transportSocketReadActiveCalls"));
                assertEquals("1", f.get(mode.endsWith("FAIL") ? "transportSocketReadFailed" : "transportSocketReadCompleted"));
                assertFalse(f.toString().contains("private"));
            }
        }
    }

    @Test void immutableCommandBaselineDistinguishesExistingPendingReadFromNewRead() throws Exception {
        try (var raw = new FakeSocket(); var observed = new SshSocketReadDiagnostics(raw)) {
            var io = attributes(); observed.attach(io);
            var handler = new CompletionHandler<Integer, Object>() {
                public void completed(Integer result, Object value) { }
                public void failed(Throwable failure, Object value) { fail(); }
            };
            observed.read(ByteBuffer.allocate(4), 0, TimeUnit.SECONDS, null, handler);
            var baseline = SshSocketReadDiagnostics.snapshot(io);
            var f = new LinkedHashMap<String, String>();
            SshSocketReadDiagnostics.snapshot(io).appendTo(f, baseline);
            assertEquals("true", f.get("transportSocketReadPendingBeforeCommand"));
            assertEquals("1", f.get("transportSocketReadGenerationAtCommandStart"));
            assertEquals("0", f.get("transportSocketReadAttemptsDuringCommand"));
            raw.finish(false);
            observed.read(ByteBuffer.allocate(4), 0, TimeUnit.SECONDS, null, handler);
            f.clear(); SshSocketReadDiagnostics.snapshot(io).appendTo(f, baseline);
            assertEquals("false", f.get("transportSocketReadPendingBeforeCommand"));
            assertEquals("2", f.get("transportSocketReadPendingGeneration"));
            assertEquals("1", f.get("transportSocketReadAttemptsDuringCommand"));
            assertEquals("1", f.get("transportSocketReadDeliveryReturnedDuringCommand"));
            assertEquals("1", baseline.facts().get("transportSocketReadAttempts"));
            assertThrows(UnsupportedOperationException.class, () -> baseline.facts().put("private", "private"));
        }
    }

    @Test void actualNio2SuspensionKeepsResumeQueuedUntilExecutorRunsWithoutExtraIo() throws Exception {
        var manager = SshClient.setUpDefaultClient(); var raw = new FakeSocket();
        var observed = new SshSocketReadDiagnostics(raw); var group = new FakeGroup(); var executor = new QueuedExecutor();
        var handled = new AtomicInteger();
        var handler = new IoHandler() {
            public void sessionCreated(IoSession session) { }
            public void sessionClosed(IoSession session) { }
            public void exceptionCaught(IoSession session, Throwable failure) { fail(failure); }
            public void messageReceived(IoSession session, Readable message) { handled.incrementAndGet(); session.suspendRead(); }
        };
        var connector = new Nio2Connector(null, manager, handler, group, executor);
        var session = new Nio2Session(connector, manager, handler, observed, null); observed.attach(session);
        try {
            session.startReading(16); raw.buffer.put(new byte[]{1, 2, 3}); raw.finish(false);
            assertEquals(1, handled.get()); assertEquals(1, raw.readCalls);
            assertEquals("0", facts(session).get("transportSocketReadDeliveryActive"));
            assertEquals("false", facts(session).get("transportSocketReadPending"));
            session.resumeRead();
            assertEquals(1, executor.tasks.size()); assertEquals(1, raw.readCalls);
            executor.tasks.remove().run();
            assertEquals(2, raw.readCalls); assertEquals(0, raw.writeCalls);
            assertEquals("1", facts(session).get("transportSocketReadAwaitingCallbacks"));
        } finally {
            assertTrue(session.close(true).awaitUninterruptibly(Duration.ofSeconds(1)));
            connector.close(true); executor.shutdown(); group.shutdown(); manager.close(true);
        }
    }

    @Test void fluentChannelReturnsKeepReadObservationWithoutChangingOperationsOrExceptions() throws Exception {
        for (String operation : List.of("BIND", "OPTION", "SHUTDOWN_INPUT", "SHUTDOWN_OUTPUT")) {
            try (var raw = new FakeSocket(); var observed = new SshSocketReadDiagnostics(raw)) {
                var io = attributes(); observed.attach(io); var callbacks = new AtomicInteger();
                var fluent = fluent(observed, operation);
                fluent.read(ByteBuffer.allocate(3), 0, TimeUnit.SECONDS, null, new CompletionHandler<Integer, Object>() {
                    public void completed(Integer result, Object value) { assertEquals(3, result); callbacks.incrementAndGet(); }
                    public void failed(Throwable failure, Object value) { fail(); }
                });
                raw.finish(false);
                assertEquals("1", facts(io).get("transportSocketReadAttempts"), operation + " must not escape observation");
                assertSame(observed, fluent, "Channel fluent methods return this channel");
                assertEquals(1, raw.fluentCalls); assertEquals(1, callbacks.get());
                var original = new IOException("private fluent failure"); raw.fluentFailure = original;
                assertSame(original, assertThrows(IOException.class, () -> fluent(observed, operation)));
                assertEquals(2, raw.fluentCalls);
            }
        }
    }
    private static AsynchronousSocketChannel fluent(AsynchronousSocketChannel socket, String operation) throws IOException {
        return switch (operation) {
            case "BIND" -> socket.bind(null);
            case "OPTION" -> socket.setOption(StandardSocketOptions.SO_KEEPALIVE, true);
            case "SHUTDOWN_INPUT" -> socket.shutdownInput();
            case "SHUTDOWN_OUTPUT" -> socket.shutdownOutput();
            default -> throw new AssertionError(operation);
        };
    }
    @Test void callbackCallsPreserveSynchronousAndAsynchronousResultsAndFailures() throws Exception {
        for (String mode : List.of("SYNC_OK", "SYNC_FAIL", "ASYNC_OK", "ASYNC_FAIL", "THROW")) {
            try (var raw = new FakeSocket(); var observed = new SshSocketReadDiagnostics(raw)) {
                var io = attributes(); observed.attach(io);
                raw.mode = mode;
                Object attachment = new Object(); ByteBuffer buffer = ByteBuffer.allocate(10);
                var callbacks = new AtomicInteger();
                raw.entered = () -> {
                    var f = facts(io); assertEquals("1", f.get("transportSocketReadActiveCalls"));
                    assertEquals("1", f.get("transportSocketReadAwaitingCallbacks"));
                    assertEquals("0", f.get("transportSocketReadReturned"), "A callback may run before read returns");
                };
                var handler = new CompletionHandler<Integer, Object>() {
                    public void completed(Integer result, Object value) { assertEquals(3, result); assertSame(attachment, value); callbacks.incrementAndGet(); }
                    public void failed(Throwable failure, Object value) { assertSame(raw.failure, failure); assertSame(attachment, value); callbacks.incrementAndGet(); }
                };
                if (mode.equals("THROW")) assertSame(raw.originalThrow, assertThrows(RuntimeException.class,
                    () -> observed.read(buffer, 37, TimeUnit.MILLISECONDS, attachment, handler)));
                else {
                    observed.read(buffer, 37, TimeUnit.MILLISECONDS, attachment, handler);
                    assertSame(buffer, raw.buffer); assertEquals(37, raw.timeout); assertSame(TimeUnit.MILLISECONDS, raw.unit);
                    assertSame(attachment, raw.attachment);
                    if (mode.startsWith("ASYNC")) {
                        assertEquals("true", facts(io).get("transportSocketReadPending"));
                        raw.finish(mode.endsWith("FAIL"));
                    }
                }
                var f = facts(io);
                assertEquals("0", f.get("transportSocketReadActiveCalls"));
                assertEquals("0", f.get("transportSocketReadAwaitingCallbacks"));
                assertEquals("false", f.get("transportSocketReadPending"));
                assertEquals(mode.equals("THROW") ? "0" : "1", f.get("transportSocketReadReturned"));
                assertEquals(mode.equals("THROW") ? "1" : "0", f.get("transportSocketReadThrown"));
                assertEquals(mode.endsWith("OK") ? "1" : "0", f.get("transportSocketReadCompleted"));
                assertEquals(mode.endsWith("FAIL") ? "1" : "0", f.get("transportSocketReadFailed"));
                if (mode.equals("THROW")) assertEquals("UNKNOWN", f.get("transportSocketReadLastCompletionAgeMillis"));
                assertEquals(mode.equals("THROW") ? 0 : 1, callbacks.get());
                assertFalse(f.toString().contains("private"));
            }
        }
    }

    @Test void secondReadThrowAndDuplicateCallbackCannotEraseOrDoubleDecrementOriginalPending() throws Exception {
        try (var raw = new FakeSocket(); var observed = new SshSocketReadDiagnostics(raw)) {
            var io = attributes(); observed.attach(io); var callbacks = new AtomicInteger();
            var handler = new CompletionHandler<Integer, Object>() {
                public void completed(Integer result, Object value) { assertEquals(-1, result); callbacks.incrementAndGet(); }
                public void failed(Throwable failure, Object value) { fail(); }
            };
            observed.read(ByteBuffer.allocate(1), 0, TimeUnit.SECONDS, null, handler);
            assertSame(raw.pendingThrow, assertThrows(ReadPendingException.class,
                () -> observed.read(ByteBuffer.allocate(1), 0, TimeUnit.SECONDS, null, handler)));
            var f = facts(io);
            assertEquals("2", f.get("transportSocketReadAttempts"));
            assertEquals("1", f.get("transportSocketReadThrown"));
            assertEquals("1", f.get("transportSocketReadAwaitingCallbacks"));
            var original = raw.pending; raw.pending = null;
            original.completed(-1, raw.attachment); original.completed(-1, raw.attachment);
            f = facts(io);
            assertEquals(2, callbacks.get(), "Original callback delivery is preserved even for a broken duplicate producer");
            assertEquals("1", f.get("transportSocketReadCompleted"));
            assertEquals("0", f.get("transportSocketReadAwaitingCallbacks"));
            assertEquals("EOF", f.get("transportSocketReadLastResult"));
        }
    }

    @Test void futureReadsWriteAndCloseKeepOriginalIdentityAndPendingCoverageUnknown() throws Exception {
        var raw = new FakeSocket(); var observed = new SshSocketReadDiagnostics(raw); var io = attributes(); observed.attach(io);
        try {
            assertSame(raw.provider(), observed.provider());
            assertSame(raw.future, observed.read(ByteBuffer.allocate(1)));
            assertTrue(raw.future.cancel(true)); assertTrue(raw.future.isCancelled());
            assertEquals("FUTURE_UNOBSERVED", facts(io).get("transportSocketReadCoverage"));
            assertEquals("UNKNOWN", facts(io).get("transportSocketReadPending"));
            assertSame(raw.future, observed.write(ByteBuffer.allocate(1)));
            var original = new IOException("private close failure"); raw.closeFailure = original;
            assertSame(original, assertThrows(IOException.class, observed::close));
            assertTrue(observed.isOpen()); raw.closeFailure = null;
            observed.close(); assertFalse(raw.isOpen()); assertFalse(observed.isOpen());
        } finally { raw.closeFailure = null; observed.close(); }
    }

    @Test void scatterReadAndNullHandlerKeepOriginalArgumentsAndFailure() throws Exception {
        try (var raw = new FakeSocket(); var observed = new SshSocketReadDiagnostics(raw)) {
            var io = attributes(); observed.attach(io); var buffers = new ByteBuffer[]{ByteBuffer.allocate(1), ByteBuffer.allocate(2)};
            Object attachment = new Object(); var callbacks = new AtomicInteger();
            observed.read(buffers, 1, 1, 29, TimeUnit.SECONDS, attachment, new CompletionHandler<Long, Object>() {
                public void completed(Long result, Object value) { assertEquals(0L, result); assertSame(attachment, value); callbacks.incrementAndGet(); }
                public void failed(Throwable failure, Object value) { fail(); }
            });
            assertSame(buffers, raw.buffers); assertEquals(1, raw.offset); assertEquals(1, raw.length);
            assertEquals(29, raw.timeout); assertSame(TimeUnit.SECONDS, raw.unit);
            raw.scatter.completed(0L, attachment); assertEquals(1, callbacks.get());
            assertEquals("ZERO", facts(io).get("transportSocketReadLastResult"));
            assertSame(raw.nullHandler, assertThrows(NullPointerException.class,
                () -> observed.read(ByteBuffer.allocate(1), 0, TimeUnit.SECONDS, null, null)));
            assertEquals("HANDLER_UNOBSERVED", facts(io).get("transportSocketReadCoverage"));
            assertEquals("UNKNOWN", facts(io).get("transportSocketReadPending"));
        }
    }

    @Test void actualNio2HandlerReturnPostsNextReadOnSameFakeSocket() throws Exception {
        var manager = SshClient.setUpDefaultClient(); var raw = new FakeSocket();
        var observed = new SshSocketReadDiagnostics(raw); var group = new FakeGroup(); var executor = new DirectExecutor();
        var handled = new AtomicInteger(); var closed = new AtomicInteger();
        var handler = new SshReadDiagnostics(new IoHandler() {
            public void sessionCreated(IoSession session) { }
            public void sessionClosed(IoSession session) { closed.incrementAndGet(); }
            public void exceptionCaught(IoSession session, Throwable failure) { fail("Unexpected NIO2 failure"); }
            public void messageReceived(IoSession session, Readable message) {
                handled.incrementAndGet();
                assertEquals("1", facts(session).get("transportSocketReadAttempts"), "Next read awaits handler return");
                assertEquals("false", facts(session).get("transportSocketReadPending"));
            }
        });
        var connector = new Nio2Connector(null, manager, handler, group, executor);
        var session = new Nio2Session(connector, manager, handler, observed, null); observed.attach(session);
        try {
            handler.sessionCreated(session); session.startReading(16);
            assertEquals("1", facts(session).get("transportSocketReadAwaitingCallbacks"));
            raw.buffer.put(new byte[]{1, 2, 3}); raw.finish(false);
            assertEquals(1, handled.get());
            var f = facts(session);
            assertEquals("2", f.get("transportSocketReadAttempts"));
            assertEquals("1", f.get("transportSocketReadCompleted"));
            assertEquals("1", f.get("transportSocketReadAwaitingCallbacks"));
            var combined = new LinkedHashMap<String, String>(); SshReadDiagnostics.appendTo(session, combined);
            assertEquals("1", combined.get("transportReadCallbacks"), "Handler callbacks are not SSH packet counts");
        } finally {
            assertTrue(session.close(true).awaitUninterruptibly(Duration.ofSeconds(1)));
            assertFalse(raw.isOpen()); assertEquals(1, closed.get());
            connector.close(true); executor.shutdown(); group.shutdown(); manager.close(true);
        }
    }

    private static IoSession attributes() {
        var attributes = new HashMap<Object, Object>();
        return (IoSession) Proxy.newProxyInstance(IoSession.class.getClassLoader(), new Class<?>[]{IoSession.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "setAttribute" -> attributes.put(args[0], args[1]); case "getAttribute" -> attributes.get(args[0]);
                default -> throw new AssertionError(method.getName());
            });
    }
    private static Map<String, String> facts(IoSession session) { var f = new LinkedHashMap<String, String>(); SshSocketReadDiagnostics.appendTo(session, f); return f; }

    /** No native channel: only the standard AsynchronousSocketChannel contract is controlled. */
    static final class FakeSocket extends AsynchronousSocketChannel {
        String mode = "ASYNC_OK"; boolean open = true; Runnable entered = () -> {};
        ByteBuffer buffer; ByteBuffer[] buffers; int offset, length; long timeout; TimeUnit unit; Object attachment;
        CompletionHandler<Integer, Object> pending; CompletionHandler<Long, Object> scatter;
        final IOException failure = new IOException("private read failure");
        final RuntimeException originalThrow = new IllegalStateException("private synchronous failure");
        final ReadPendingException pendingThrow = new ReadPendingException();
        final NullPointerException nullHandler = new NullPointerException("private null handler");
        final CompletableFuture<Integer> future = new CompletableFuture<>(); IOException closeFailure;
        int fluentCalls, readCalls, writeCalls; IOException fluentFailure;
        FakeSocket() { super(AsynchronousChannelProvider.provider()); }
        @Override @SuppressWarnings("unchecked") public <A> void read(ByteBuffer dst, long timeout, TimeUnit unit, A attachment, CompletionHandler<Integer, ? super A> handler) {
            readCalls++;
            if (handler == null) throw nullHandler;
            if (pending != null) throw pendingThrow;
            this.buffer = dst; this.timeout = timeout; this.unit = unit; this.attachment = attachment;
            entered.run();
            if (mode.equals("THROW")) throw originalThrow;
            pending = (CompletionHandler<Integer, Object>) handler;
            if (mode.startsWith("SYNC")) finish(mode.endsWith("FAIL"));
        }
        void finish(boolean failed) {
            var handler = pending; pending = null;
            if (failed) handler.failed(failure, attachment); else handler.completed(3, attachment);
        }
        @Override @SuppressWarnings("unchecked") public <A> void read(ByteBuffer[] dsts, int offset, int length, long timeout, TimeUnit unit, A attachment, CompletionHandler<Long, ? super A> handler) {
            this.buffers = dsts; this.offset = offset; this.length = length; this.timeout = timeout; this.unit = unit;
            scatter = (CompletionHandler<Long, Object>) handler;
        }
        @Override public Future<Integer> read(ByteBuffer dst) { return future; }
        @Override public Future<Integer> write(ByteBuffer src) { return future; }
        @Override public <A> void write(ByteBuffer src, long timeout, TimeUnit unit, A attachment, CompletionHandler<Integer, ? super A> handler) {
            writeCalls++; int count = src.remaining(); src.position(src.limit()); handler.completed(count, attachment);
        }
        @Override public <A> void write(ByteBuffer[] srcs, int offset, int length, long timeout, TimeUnit unit, A attachment, CompletionHandler<Long, ? super A> handler) { handler.completed(0L, attachment); }
        private AsynchronousSocketChannel fluentCall() throws IOException { fluentCalls++; if (fluentFailure != null) throw fluentFailure; return this; }
        @Override public AsynchronousSocketChannel bind(SocketAddress local) throws IOException { assertNull(local); return fluentCall(); }
        @Override public <T> AsynchronousSocketChannel setOption(SocketOption<T> option, T value) throws IOException {
            assertSame(StandardSocketOptions.SO_KEEPALIVE, option); assertEquals(Boolean.TRUE, value); return fluentCall();
        }
        @Override public <T> T getOption(SocketOption<T> option) { return null; }
        @Override public Set<SocketOption<?>> supportedOptions() { return Set.of(); }
        @Override public AsynchronousSocketChannel shutdownInput() throws IOException { return fluentCall(); }
        @Override public AsynchronousSocketChannel shutdownOutput() throws IOException { return fluentCall(); }
        @Override public SocketAddress getRemoteAddress() { return null; }
        @Override public SocketAddress getLocalAddress() { return null; }
        @Override public <A> void connect(SocketAddress remote, A attachment, CompletionHandler<Void, ? super A> handler) { throw new AssertionError("No socket connection"); }
        @Override public Future<Void> connect(SocketAddress remote) { throw new AssertionError("No socket connection"); }
        @Override public boolean isOpen() { return open; }
        @Override public void close() throws IOException { if (closeFailure != null) throw closeFailure; open = false; }
    }
    static final class DirectExecutor extends AbstractExecutorService {
        boolean stopped;
        public void shutdown() { stopped = true; }
        public List<Runnable> shutdownNow() { stopped = true; return List.of(); }
        public boolean isShutdown() { return stopped; }
        public boolean isTerminated() { return stopped; }
        public boolean awaitTermination(long timeout, TimeUnit unit) { return stopped; }
        public void execute(Runnable task) { if (stopped) throw new RejectedExecutionException(); task.run(); }
    }
    static final class QueuedExecutor extends AbstractExecutorService {
        final Queue<Runnable> tasks = new ArrayDeque<>(); boolean stopped;
        public void shutdown() { stopped = true; }
        public List<Runnable> shutdownNow() { stopped = true; var pending = List.copyOf(tasks); tasks.clear(); return pending; }
        public boolean isShutdown() { return stopped; }
        public boolean isTerminated() { return stopped && tasks.isEmpty(); }
        public boolean awaitTermination(long timeout, TimeUnit unit) { return isTerminated(); }
        public void execute(Runnable task) { if (stopped) throw new RejectedExecutionException(); tasks.add(task); }
    }
    static final class FakeGroup extends AsynchronousChannelGroup {
        boolean stopped;
        FakeGroup() { super(AsynchronousChannelProvider.provider()); }
        public boolean isShutdown() { return stopped; }
        public boolean isTerminated() { return stopped; }
        public void shutdown() { stopped = true; }
        public void shutdownNow() { stopped = true; }
        public boolean awaitTermination(long timeout, TimeUnit unit) { return stopped; }
    }
}
