package gold.debug.windowstolinux.app.linux.ssh.connect;

import java.io.IOException;
import java.util.Map;
import org.apache.sshd.client.ClientFactoryManager;
import org.apache.sshd.client.future.OpenFuture;
import org.apache.sshd.client.session.ClientSessionImpl;
import org.apache.sshd.common.channel.exception.SshChannelOpenException;
import org.apache.sshd.common.io.IoSession;
import org.apache.sshd.common.io.IoWriteFuture;
import org.apache.sshd.common.util.buffer.Buffer;

/** Exact local observations, without packet or exception bodies.
 * PENDING/FAILED do not imply zero transmitted bytes; WRITTEN does not imply a peer reply.
 * All times are monotonic milliseconds since this channel was bound, not wall time.
 */
final class ChannelOpenMetadata {
    private final long channelId;
    private final long started = System.nanoTime();
    private String writeState = "NOT_OBSERVED", writeFailure = "UNKNOWN", reply = "NOT_OBSERVED";
    private long writeStarted = -1, writeCompleted = -1, replyAt = -1, failureReason = -1;
    private OpenFuture opening;
    private boolean unavailable;
    ChannelOpenMetadata(long channelId) { this.channelId = channelId; }

    synchronized boolean writing(Buffer b) {
        try { return writingPacket(b); }
        catch (RuntimeException failure) { unavailable = true; return false; }
    }
    private boolean writingPacket(Buffer b) {
        int pos = b.rpos();
        if (channelId < 0 || b.available() < 9 || b.rawByte(pos) != 90) return false;
        long length = b.rawUInt(pos + 1);
        if (length > b.available() - 9L || b.rawUInt(pos + 5 + (int) length) != channelId) return false;
        writeState = "PENDING";
        writeStarted = elapsed();
        return true;
    }
    synchronized void written(IoWriteFuture future) {
        try {
            writeState = future.isWritten() ? "WRITTEN" : future.isDone() ? "FAILED" : "PENDING";
            if (future.isDone()) writeCompleted = elapsed();
            if (future.getException() != null) writeFailure = failureType(future.getException());
        } catch (RuntimeException failure) { unavailable = true; }
    }
    synchronized void writeFailed(Throwable failure) {
        writeState = "FAILED";
        writeCompleted = elapsed();
        writeFailure = failureType(failure);
    }
    synchronized void received(Buffer b) {
        try { receivedPacket(b); } catch (RuntimeException failure) { unavailable = true; }
    }
    private void receivedPacket(Buffer b) {
        int pos = b.rpos();
        if (channelId < 0 || b.available() < 1) return;
        int opcode = b.rawByte(pos) & 255;
        if (opcode != 91 && opcode != 92) return;
        if (b.available() < 5 || b.rawUInt(pos + 1) != channelId) return;
        if (opcode == 91 && b.available() >= 17) reply = "CONFIRMATION";
        else if (opcode == 92 && b.available() >= 9) {
            reply = "FAILURE";
            failureReason = b.rawUInt(pos + 5);
        } else return;
        replyAt = elapsed();
    }
    synchronized void opening(OpenFuture future) { opening = future; }
    synchronized void appendTo(Map<String, String> facts) {
        facts.put("channelOpenObservation", unavailable ? "UNAVAILABLE" : "AVAILABLE");
        facts.put("channelOpenWriteState", writeState);
        facts.put("channelOpenWriteFailureType", writeFailure);
        number(facts, "channelOpenWriteStartedMillis", writeStarted);
        number(facts, "channelOpenWriteCompletedMillis", writeCompleted);
        facts.put("channelOpenReply", reply);
        number(facts, "channelOpenReplyMillis", replyAt);
        number(facts, "channelOpenFailureReason", failureReason);
        facts.put("channelOpenSnapshotPhase", "RESULT");
        facts.put("channelOpenFutureDone", opening == null ? "UNKNOWN" : Boolean.toString(opening.isDone()));
        facts.put("channelOpenFutureOpened", opening == null ? "UNKNOWN" : Boolean.toString(opening.isOpened()));
        facts.put("channelOpenFutureCanceled", opening == null ? "UNKNOWN" : Boolean.toString(opening.isCanceled()));
        Throwable failure = opening == null ? null : opening.getException();
        facts.put("channelOpenFutureFailureType", failure == null ? "UNKNOWN" : failureType(failure));
        number(facts, "channelOpenFutureFailureReason", failure instanceof SshChannelOpenException f ? Integer.toUnsignedLong(f.getReasonCode()) : -1);
    }
    synchronized void unavailable() { unavailable = true; }
    private long elapsed() { return Math.max(0, System.nanoTime() - started) / 1_000_000; }
    private static void number(Map<String, String> facts, String key, long value) {
        facts.put(key, value < 0 ? "UNKNOWN" : Long.toString(value));
    }
    private static String failureType(Throwable value) {
        if (value instanceof SshChannelOpenException) return "CHANNEL_OPEN";
        if (value instanceof java.util.concurrent.CancellationException) return "CANCELLATION";
        if (value instanceof java.util.concurrent.TimeoutException) return "TIMEOUT";
        if (value instanceof IOException) return "IO";
        return "OTHER";
    }
    /** Session-local binding excludes all other sessions and channels. */
    static final class Session extends ClientSessionImpl {
        private volatile ChannelOpenMetadata bound;
        Session(ClientFactoryManager manager, IoSession io) throws Exception { super(manager, io); }
        void bind(ChannelOpenMetadata metadata) { bound = metadata; }
        void unbind(ChannelOpenMetadata metadata) { if (bound == metadata) bound = null; }
        @Override protected IoWriteFuture doWritePacket(Buffer buffer) throws IOException {
            var observed = bound;
            boolean matched = observed != null && observed.writing(buffer);
            try {
                var future = super.doWritePacket(buffer);
                if (matched) {
                    try { future.addListener(observed::written); }
                    catch (RuntimeException unavailable) { observed.unavailable(); }
                }
                return future;
            } catch (IOException | RuntimeException | Error failure) {
                if (matched) observed.writeFailed(failure);
                throw failure;
            }
        }
        @Override protected void doHandleMessage(Buffer buffer) throws Exception {
            var observed = bound;
            if (observed != null) observed.received(buffer);
            super.doHandleMessage(buffer);
        }
    }
}
