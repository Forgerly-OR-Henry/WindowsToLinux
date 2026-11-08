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

/** Exact open/exec local observations, without packet or exception bodies.
 * PENDING/FAILED do not imply zero transmitted bytes; WRITTEN does not imply a peer reply.
 * Exec acceptance stays UNKNOWN: the unchanged exec wantReply policy is not a readiness protocol.
 * All times are monotonic milliseconds since command execution started, not wall time.
 */
final class ChannelOpenMetadata {
    private final long channelId;
    private final long started;
    private String writeState = "NOT_OBSERVED", writeFailure = "UNKNOWN", reply = "NOT_OBSERVED";
    private long writeStarted = -1, writeCompleted = -1, replyAt = -1, failureReason = -1;
    private OpenFuture opening;
    private boolean unavailable;
    private long peerChannel = -1;
    private String execWriteState = "NOT_OBSERVED", execWriteFailure = "UNKNOWN", execWantReply = "UNKNOWN";
    private long execWriteStarted = -1, execWriteCompleted = -1;
    private boolean execUnavailable;
    ChannelOpenMetadata(long channelId, long commandStarted) {
        this.channelId = channelId;
        this.started = commandStarted;
    }

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
    /** The same channel's fixed exec request, observed before encoding; never reads its command body. */
    synchronized boolean execWriting(Buffer b) {
        try {
            int p = b.rpos();
            if (peerChannel < 0 || b.available() < 18 || (b.rawByte(p) & 255) != 98
                || b.rawUInt(p + 1) != peerChannel || b.rawUInt(p + 5) != 4
                || b.rawByte(p + 9) != 'e' || b.rawByte(p + 10) != 'x'
                || b.rawByte(p + 11) != 'e' || b.rawByte(p + 12) != 'c') return false;
            execWriteState = "PENDING";
            execWriteStarted = elapsed();
            execWantReply = Boolean.toString(b.rawByte(p + 13) != 0);
            return true;
        } catch (RuntimeException failure) { execUnavailable = true; return false; }
    }
    synchronized void execWritten(IoWriteFuture future) {
        try {
            execWriteState = future.isWritten() ? "WRITTEN" : future.isDone() ? "FAILED" : "PENDING";
            if (future.isDone()) execWriteCompleted = elapsed();
            if (future.getException() != null) execWriteFailure = failureType(future.getException());
        } catch (RuntimeException failure) { execUnavailable = true; }
    }
    synchronized void execWriteFailed(Throwable failure) {
        execWriteState = "FAILED";
        execWriteCompleted = elapsed();
        execWriteFailure = failureType(failure);
    }
    synchronized void execUnavailable() { execUnavailable = true; }
    synchronized void received(Buffer b) {
        try { receivedPacket(b); } catch (RuntimeException failure) { unavailable = true; }
    }
    private void receivedPacket(Buffer b) {
        int pos = b.rpos();
        if (channelId < 0 || b.available() < 1) return;
        int opcode = b.rawByte(pos) & 255;
        if (opcode != 91 && opcode != 92) return;
        if (b.available() < 5 || b.rawUInt(pos + 1) != channelId) return;
        if (opcode == 91 && b.available() >= 17) {
            reply = "CONFIRMATION";
            peerChannel = b.rawUInt(pos + 5);
        }
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
        facts.put("execRequestObservation", execUnavailable ? "UNAVAILABLE" : execWriteState.equals("NOT_OBSERVED") ? "NOT_OBSERVED" : "AVAILABLE");
        facts.put("execRequestWriteState", execWriteState);
        facts.put("execRequestWriteFailureType", execWriteFailure);
        number(facts, "execRequestWriteStartedMillis", execWriteStarted);
        number(facts, "execRequestWriteCompletedMillis", execWriteCompleted);
        facts.put("execRequestWantReply", execWantReply);
        // A local write is not exec acceptance or readiness, whether or not a reply was requested.
        facts.put("execRequestPeerAcceptance", "UNKNOWN");
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
            boolean matchedExec = observed != null && observed.execWriting(buffer);
            try {
                var future = super.doWritePacket(buffer);
                if (matched) {
                    try { future.addListener(observed::written); }
                    catch (RuntimeException unavailable) { observed.unavailable(); }
                }
                if (matchedExec) {
                    try { future.addListener(observed::execWritten); }
                    catch (RuntimeException unavailable) { observed.execUnavailable(); }
                }
                return future;
            } catch (IOException | RuntimeException | Error failure) {
                if (matched) observed.writeFailed(failure);
                if (matchedExec) observed.execWriteFailed(failure);
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
