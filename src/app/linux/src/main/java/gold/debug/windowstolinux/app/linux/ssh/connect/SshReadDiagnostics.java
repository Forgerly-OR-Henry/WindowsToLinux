package gold.debug.windowstolinux.app.linux.ssh.connect;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.sshd.common.io.IoHandler;
import org.apache.sshd.common.io.IoSession;
import org.apache.sshd.common.util.Readable;

/** Observes read progress without retaining buffers or peer text. / 观察读取进展，不保留缓冲区或对端文本。 */
final class SshReadDiagnostics implements IoHandler {
    private static final Object KEY = new Object();

    private final IoHandler delegate;

    static final class Reading {
        private final long created = System.nanoTime();

        private final AtomicLong callbacks = new AtomicLong();

        private volatile long completed;

        private volatile Thread reader;

        private volatile long entered;

        private volatile Failure firstFailure;

        synchronized void failure(Throwable failure) {
            if (firstFailure == null)
                firstFailure = Failure.capture(failure);
        }

        long callbacks() {
            return callbacks.get();
        }
    }

    /** Immutable bounded exception metadata, never the original throwable. / 有界异常元数据，不保留原始异常。 */
    record Failure(String type, String causeType, List<String> stack) {
        static Failure capture(Throwable failure) {
            var frames = new ArrayList<String>();
            for (var frame : failure.getStackTrace()) {
                if (safeFrame(frame)) {
                    frames.add(frame.getClassName() + "#" + frame.getMethodName() + ":" + frame.getLineNumber());
                    if (frames.size() == 6)
                        break;
                }
            }
            return new Failure(type(failure), type(failure.getCause()), List.copyOf(frames));
        }

        private static String type(Throwable failure) {
            if (failure == null)
                return "UNKNOWN";
            String name = failure.getClass().getName();
            return name.matches("(?:java|org\\.apache\\.sshd)\\.[A-Za-z0-9_.$]{1,160}") ? name : "UNKNOWN";
        }

        void appendTo(Map<String, String> facts) {
            facts.put("transportFailureType", type);
            facts.put("transportFailureCauseType", causeType);
            for (int index = 0; index < stack.size(); index++)
                facts.put("transportFailureStack[" + index + "]", stack.get(index));
        }
    }

    SshReadDiagnostics(IoHandler delegate) {
        this.delegate = delegate;
    }

    @Override
    public void sessionCreated(IoSession session) throws Exception {
        session.setAttribute(KEY, new Reading());
        delegate.sessionCreated(session);
    }

    @Override
    public void sessionClosed(IoSession session) throws Exception {
        delegate.sessionClosed(session);
    }

    @Override
    public void exceptionCaught(IoSession session, Throwable failure) throws Exception {
        var observed = reading(session);
        if (observed != null)
            observed.failure(failure);
        delegate.exceptionCaught(session, failure);
    }

    @Override
    public void messageReceived(IoSession session, Readable message) throws Exception {
        var observed = reading(session);
        if (observed == null) {
            delegate.messageReceived(session, message);
            return;
        }
        observed.callbacks.incrementAndGet();
        observed.entered = System.nanoTime();
        observed.reader = Thread.currentThread();
        try {
            delegate.messageReceived(session, message);
        } finally {
            observed.completed = System.nanoTime();
            observed.reader = null;
        }
    }

    static Reading reading(IoSession session) {
        return session != null && session.getAttribute(KEY) instanceof Reading observed ? observed : null;
    }

    static void appendTo(IoSession session, Map<String, String> facts) {
        appendTo(reading(session), facts);
    }

    static void appendTo(Reading observed, Map<String, String> facts) {
        if (observed == null)
            return;
        long now = System.nanoTime();
        Thread active = observed.reader;
        facts.put("transportReadCallbacks", Long.toString(observed.callbacks.get()));
        facts.put("transportSessionAgeMillis", age(now, observed.created));
        facts.put("transportReadHandlerActive", Boolean.toString(active != null));
        facts.put("transportReadCompletedAgeMillis", age(now, observed.completed));
        if (active == null)
            return;
        facts.put("transportReadHandlerAgeMillis", age(now, observed.entered));
        facts.put("transportReadThreadState", active.getState().name());
        int index = 0;
        for (var frame : active.getStackTrace()) {
            if (safeFrame(frame)) {
                facts.put("transportReadStack[" + index++ + "]",
                    frame.getClassName() + "#" + frame.getMethodName() + ":" + frame.getLineNumber());
                if (index == 6)
                    break;
            }
        }
    }

    static Failure firstFailure(Reading observed) {
        return observed == null ? null : observed.firstFailure;
    }

    private static boolean safeFrame(StackTraceElement frame) {
        return frame.getClassName().matches(
            "(?:java|jdk|sun\\.nio|org\\.apache\\.sshd|gold\\.debug\\.windowstolinux\\.app\\.linux)\\.[A-Za-z0-9_.$]{1,160}")
            && frame.getMethodName().matches("[A-Za-z0-9_$<>]{1,128}");
    }

    private static String age(long now, long since) {
        return since == 0 ? "UNKNOWN" : Long.toString(Math.max(0, now - since) / 1_000_000);
    }
}
