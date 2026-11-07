package gold.debug.windowstolinux.app.linux.ssh.connect;

import java.util.Map;

import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.session.Session;
import org.apache.sshd.common.session.SessionListener;

/** Retains typed transport facts without peer messages, addresses or payloads. / 仅保留类型化传输事实，不保留对端消息、地址或载荷。 */
final class SshCommandDiagnostics implements SessionListener {
    private volatile SshReadDiagnostics.Failure firstFailure;

    private volatile int disconnectCode = -1;

    private volatile boolean localDisconnect;

    private volatile boolean disconnectObserved;

    private volatile boolean sessionClosed;

    private ClientSession session;

    private SshReadDiagnostics.Reading reading;

    private long readCallbacksAtCommandStart;

    void attach(ClientSession value) {
        session = value;
        reading = SshReadDiagnostics.reading(value.getIoSession());
        if (reading != null)
            readCallbacksAtCommandStart = reading.callbacks();
        value.addSessionListener(this);
    }

    @Override
    public void sessionException(Session value, Throwable failure) {
        failure(failure);
    }

    synchronized void failure(Throwable failure) {
        if (firstFailure == null)
            firstFailure = SshReadDiagnostics.Failure.capture(failure);
    }

    @Override
    public void sessionDisconnect(Session value, int reason, String message, String language, boolean initiator) {
        disconnectCode = reason;
        localDisconnect = initiator;
        disconnectObserved = true;
    }

    @Override
    public void sessionClosed(Session value) {
        sessionClosed = true;
    }

    void appendTo(Map<String, String> facts) {
        var failure = SshReadDiagnostics.firstFailure(reading);
        if (failure == null)
            failure = firstFailure;
        if (failure == null) {
            facts.put("transportFailureType", "-");
            facts.put("transportFailureCauseType", "UNKNOWN");
        } else {
            failure.appendTo(facts);
        }
        boolean observed = disconnectObserved;
        facts.put("disconnectObserved", Boolean.toString(observed));
        facts.put("disconnectCode", Integer.toString(disconnectCode));
        facts.put("disconnectInitiatorLocal", observed ? Boolean.toString(localDisconnect) : "UNKNOWN");
        facts.put("sessionCloseObserved", Boolean.toString(sessionClosed));
        if (session != null) {
            var state = session.getKexState();
            facts.put("transportKeyExchangeState", state == null ? "UNKNOWN" : state.name());
            SshReadDiagnostics.appendTo(reading, facts);
            SshSocketReadDiagnostics.appendTo(session.getIoSession(), facts);
            facts.put("transportReadCallbacksAtCommandStart", reading == null ? "UNKNOWN"
                : Long.toString(readCallbacksAtCommandStart));
            facts.put("transportReadCallbacksDuringCommand", reading == null ? "UNKNOWN"
                : Long.toString(Math.max(0, reading.callbacks() - readCallbacksAtCommandStart)));
        }
    }

    void close() {
        if (session != null) {
            session.removeSessionListener(this);
            session = null;
            reading = null;
        }
    }
}
