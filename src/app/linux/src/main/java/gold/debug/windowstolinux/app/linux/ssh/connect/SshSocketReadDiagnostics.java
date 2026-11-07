package gold.debug.windowstolinux.app.linux.ssh.connect;

import java.io.IOException;
import java.net.SocketAddress;
import java.net.SocketOption;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousSocketChannel;
import java.nio.channels.CompletionHandler;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.sshd.common.io.IoSession;

/** Observes calls to the existing socket, not SSH packets or kernel acceptance. No buffer or peer data is retained. */
final class SshSocketReadDiagnostics extends AsynchronousSocketChannel {
    private static final Object KEY = new Object();
    private final AsynchronousSocketChannel delegate;
    private final Progress progress = new Progress();

    SshSocketReadDiagnostics(AsynchronousSocketChannel delegate) {
        super(delegate.provider());
        this.delegate = delegate;
    }

    void attach(IoSession session) { session.setAttribute(KEY, progress); }

    static void appendTo(IoSession session, Map<String, String> facts) {
        snapshot(session).appendTo(facts, null);
    }

    static Snapshot snapshot(IoSession session) {
        try {
            if (session != null && session.getAttribute(KEY) instanceof Progress observed) return observed.snapshot();
            return new Snapshot("NOT_OBSERVED", Map.of(), 0, 0, 0, 0);
        } catch (RuntimeException unavailable) { return new Snapshot("UNAVAILABLE", Map.of(), 0, 0, 0, 0); }
    }

    /** One immutable session-wide sample; a TCP read is never attributed to an SSH channel. */
    record Snapshot(String observation, Map<String, String> facts, long sampledAt, long generation,
                    long pendingGeneration, long pendingSince) {
        private static final java.util.List<String> COUNTERS = java.util.List.of("Attempts", "Returned", "Completed",
            "Failed", "Thrown", "FutureAttempts", "DeliveryEntered", "DeliveryReturned", "DeliveryThrew");
        Snapshot { facts = Map.copyOf(facts); }
        void appendTo(Map<String, String> target, Snapshot baseline) {
            target.put("transportSocketReadObservation", observation);
            target.putAll(facts);
            if (baseline == null || !observation.equals("AVAILABLE") || !baseline.observation.equals("AVAILABLE")) return;
            for (String counter : COUNTERS) {
                String key = "transportSocketRead" + counter;
                long before = Long.parseLong(baseline.facts.get(key));
                long after = Long.parseLong(facts.get(key));
                target.put(key + "AtCommandStart", Long.toString(before));
                target.put(key + "DuringCommand", Long.toString(Math.max(0, after - before)));
            }
            target.put("transportSocketReadGenerationAtCommandStart", Long.toString(baseline.generation));
            target.put("transportSocketReadPendingBeforeCommand", pendingGeneration < 0 ? "UNKNOWN"
                : Boolean.toString(pendingGeneration > 0 && pendingGeneration <= baseline.generation));
            target.put("transportSocketReadSampleDuringCommandMillis", Long.toString(Math.max(0, sampledAt - baseline.sampledAt) / 1_000_000));
        }
    }

    /** An awaiting callback includes a call still inside read(); it does not assert kernel submission or peer progress.
     * Future reads preserve their original identity/cancellation and make pending coverage explicitly UNKNOWN.
     */
    static final class Progress {
        private long attempts, returned, completed, failed, thrown, futureAttempts;
        private long activeCalls, awaiting, lastAttempt, lastReturn, lastCompletion;
        private long deliveryEntered, deliveryReturned, deliveryThrew, deliveryActive;
        private long pendingGeneration, pendingSince;
        private boolean unobserved;
        private String lastResult = "NOT_OBSERVED";
        synchronized Call begin() {
            attempts++; activeCalls++; awaiting++; lastAttempt = System.nanoTime();
            if (awaiting == 1) { pendingGeneration = attempts; pendingSince = lastAttempt; }
            return new Call(this, attempts);
        }
        synchronized void returned() { returned++; activeCalls--; lastReturn = System.nanoTime(); }
        synchronized void threw() { thrown++; activeCalls--; }
        synchronized void terminal(String result, long generation) {
            awaiting--;
            if (awaiting == 0) { pendingGeneration = 0; pendingSince = 0; }
            else if (pendingGeneration == generation) { pendingGeneration = -1; pendingSince = 0; }
            lastResult = result;
            if (!result.equals("THREW")) lastCompletion = System.nanoTime();
            if (result.equals("FAILED")) failed++;
            else if (!result.equals("THREW")) completed++;
        }
        synchronized void futureAttempt() { futureAttempts++; unobserved = true; }
        synchronized void unobservedRead() { unobserved = true; }
        synchronized void deliveryEntered() { deliveryEntered++; deliveryActive++; }
        synchronized void deliveryFinished(boolean returned) {
            deliveryActive--;
            if (returned) deliveryReturned++; else deliveryThrew++;
        }
        synchronized Snapshot snapshot() {
            var facts = new java.util.LinkedHashMap<String, String>();
            facts.put("transportSocketReadObservation", "AVAILABLE");
            facts.put("transportSocketReadCoverage", futureAttempts != 0 ? "FUTURE_UNOBSERVED" : unobserved ? "HANDLER_UNOBSERVED" : "CALLBACK_ONLY");
            facts.put("transportSocketReadAttempts", Long.toString(attempts));
            facts.put("transportSocketReadReturned", Long.toString(returned));
            facts.put("transportSocketReadCompleted", Long.toString(completed));
            facts.put("transportSocketReadFailed", Long.toString(failed));
            facts.put("transportSocketReadThrown", Long.toString(thrown));
            facts.put("transportSocketReadFutureAttempts", Long.toString(futureAttempts));
            facts.put("transportSocketReadActiveCalls", Long.toString(activeCalls));
            facts.put("transportSocketReadAwaitingCallbacks", Long.toString(awaiting));
            facts.put("transportSocketReadDeliveryEntered", Long.toString(deliveryEntered));
            facts.put("transportSocketReadDeliveryReturned", Long.toString(deliveryReturned));
            facts.put("transportSocketReadDeliveryThrew", Long.toString(deliveryThrew));
            facts.put("transportSocketReadDeliveryActive", Long.toString(deliveryActive));
            facts.put("transportSocketReadGeneration", Long.toString(attempts));
            facts.put("transportSocketReadPendingGeneration", pendingGeneration < 0 ? "UNKNOWN" : Long.toString(pendingGeneration));
            facts.put("transportSocketReadPending", unobserved ? "UNKNOWN" : Boolean.toString(awaiting != 0));
            facts.put("transportSocketReadLastResult", lastResult);
            long now = System.nanoTime();
            age(facts, "transportSocketReadLastAttemptAgeMillis", now, lastAttempt);
            age(facts, "transportSocketReadLastReturnAgeMillis", now, lastReturn);
            age(facts, "transportSocketReadLastCompletionAgeMillis", now, lastCompletion);
            age(facts, "transportSocketReadPendingRegistrationAgeMillis", now, pendingSince);
            return new Snapshot("AVAILABLE", facts, now, attempts, unobserved ? -1 : pendingGeneration, pendingSince);
        }
        private static void age(Map<String, String> facts, String key, long now, long since) {
            facts.put(key, since == 0 ? "UNKNOWN" : Long.toString(Math.max(0, now - since) / 1_000_000));
        }
    }

    private static final class Call {
        private final Progress progress;
        private final AtomicBoolean terminal = new AtomicBoolean();
        private final long generation;
        private Call(Progress progress, long generation) { this.progress = progress; this.generation = generation; }
        void finish(String result) { if (terminal.compareAndSet(false, true)) progress.terminal(result, generation); }
    }

    @Override public <A> void read(ByteBuffer dst, long timeout, TimeUnit unit, A attachment,
                                  CompletionHandler<Integer, ? super A> handler) {
        if (handler == null) {
            progress.unobservedRead(); delegate.read(dst, timeout, unit, attachment, null); return;
        }
        Call call = progress.begin();
        try {
            delegate.read(dst, timeout, unit, attachment, new CompletionHandler<Integer, A>() {
                @Override public void completed(Integer result, A value) {
                    call.finish(result == null ? "UNKNOWN" : result < 0 ? "EOF" : result == 0 ? "ZERO" : "DATA");
                    progress.deliveryEntered(); boolean returned = false;
                    try { handler.completed(result, value); returned = true; }
                    finally { progress.deliveryFinished(returned); }
                }
                @Override public void failed(Throwable failure, A value) {
                    call.finish("FAILED"); progress.deliveryEntered(); boolean returned = false;
                    try { handler.failed(failure, value); returned = true; }
                    finally { progress.deliveryFinished(returned); }
                }
            });
        } catch (RuntimeException | Error original) {
            call.finish("THREW"); progress.threw(); throw original;
        }
        progress.returned();
    }

    @Override public <A> void read(ByteBuffer[] dsts, int offset, int length, long timeout, TimeUnit unit, A attachment,
                                  CompletionHandler<Long, ? super A> handler) {
        if (handler == null) {
            progress.unobservedRead(); delegate.read(dsts, offset, length, timeout, unit, attachment, null); return;
        }
        Call call = progress.begin();
        try {
            delegate.read(dsts, offset, length, timeout, unit, attachment, new CompletionHandler<Long, A>() {
                @Override public void completed(Long result, A value) {
                    call.finish(result == null ? "UNKNOWN" : result < 0 ? "EOF" : result == 0 ? "ZERO" : "DATA");
                    progress.deliveryEntered(); boolean returned = false;
                    try { handler.completed(result, value); returned = true; }
                    finally { progress.deliveryFinished(returned); }
                }
                @Override public void failed(Throwable failure, A value) {
                    call.finish("FAILED"); progress.deliveryEntered(); boolean returned = false;
                    try { handler.failed(failure, value); returned = true; }
                    finally { progress.deliveryFinished(returned); }
                }
            });
        } catch (RuntimeException | Error original) {
            call.finish("THREW"); progress.threw(); throw original;
        }
        progress.returned();
    }

    @Override public Future<Integer> read(ByteBuffer dst) { progress.futureAttempt(); return delegate.read(dst); }
    @Override public AsynchronousSocketChannel bind(SocketAddress local) throws IOException { delegate.bind(local); return this; }
    @Override public <T> AsynchronousSocketChannel setOption(SocketOption<T> option, T value) throws IOException { delegate.setOption(option, value); return this; }
    @Override public <T> T getOption(SocketOption<T> option) throws IOException { return delegate.getOption(option); }
    @Override public Set<SocketOption<?>> supportedOptions() { return delegate.supportedOptions(); }
    @Override public AsynchronousSocketChannel shutdownInput() throws IOException { delegate.shutdownInput(); return this; }
    @Override public AsynchronousSocketChannel shutdownOutput() throws IOException { delegate.shutdownOutput(); return this; }
    @Override public SocketAddress getRemoteAddress() throws IOException { return delegate.getRemoteAddress(); }
    @Override public SocketAddress getLocalAddress() throws IOException { return delegate.getLocalAddress(); }
    @Override public <A> void connect(SocketAddress remote, A attachment, CompletionHandler<Void, ? super A> handler) { delegate.connect(remote, attachment, handler); }
    @Override public Future<Void> connect(SocketAddress remote) { return delegate.connect(remote); }
    @Override public <A> void write(ByteBuffer src, long timeout, TimeUnit unit, A attachment, CompletionHandler<Integer, ? super A> handler) { delegate.write(src, timeout, unit, attachment, handler); }
    @Override public Future<Integer> write(ByteBuffer src) { return delegate.write(src); }
    @Override public <A> void write(ByteBuffer[] srcs, int offset, int length, long timeout, TimeUnit unit, A attachment, CompletionHandler<Long, ? super A> handler) { delegate.write(srcs, offset, length, timeout, unit, attachment, handler); }
    @Override public boolean isOpen() { return delegate.isOpen(); }
    @Override public void close() throws IOException { delegate.close(); }
}
