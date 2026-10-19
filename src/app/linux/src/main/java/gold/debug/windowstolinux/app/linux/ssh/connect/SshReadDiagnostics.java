package gold.debug.windowstolinux.app.linux.ssh.connect;

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
        private final AtomicLong callbacks = new AtomicLong();

        private volatile long completed;

        private volatile Thread reader;

        private volatile long entered;
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
        facts.put("transportReadHandlerActive", Boolean.toString(active != null));
        facts.put("transportReadCompletedAgeMillis", age(now, observed.completed));
        if (active == null)
            return;
        facts.put("transportReadHandlerAgeMillis", age(now, observed.entered));
        facts.put("transportReadThreadState", active.getState().name());
        int index = 0;
        for (var frame : active.getStackTrace()) {
            if (frame.getClassName().matches(
                "(?:java|jdk|sun\\.nio|org\\.apache\\.sshd|gold\\.debug\\.windowstolinux\\.app\\.linux)\\.[A-Za-z0-9_.$]{1,160}")
                && frame.getMethodName().matches("[A-Za-z0-9_$<>]{1,128}")) {
                facts.put("transportReadStack[" + index++ + "]",
                    frame.getClassName() + "#" + frame.getMethodName() + ":" + frame.getLineNumber());
                if (index == 6)
                    break;
            }
        }
    }

    private static String age(long now, long since) {
        return since == 0 ? "UNKNOWN" : Long.toString(Math.max(0, now - since) / 1_000_000);
    }
}
