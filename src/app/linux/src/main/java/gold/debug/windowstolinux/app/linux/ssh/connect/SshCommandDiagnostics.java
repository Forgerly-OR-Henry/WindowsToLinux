package gold.debug.windowstolinux.app.linux.ssh.connect;

import java.util.Map;

import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.session.Session;
import org.apache.sshd.common.session.SessionListener;

/** Retains typed transport facts without peer messages, addresses or payloads. / 仅保留类型化传输事实，不保留对端消息、地址或载荷。 */
final class SshCommandDiagnostics implements SessionListener {
    private volatile String failureType = "-";

    private volatile int disconnectCode = -1;

    private volatile boolean localDisconnect;

    private volatile boolean sessionClosed;

    private ClientSession session;

    private SshReadDiagnostics.Reading reading;

    void attach(ClientSession value) {
        session = value;
        reading = SshReadDiagnostics.reading(value.getIoSession());
        value.addSessionListener(this);
    }

    @Override
    public void sessionException(Session value, Throwable failure) {
        failure(failure);
    }

    void failure(Throwable failure) {
        String name = failure.getClass().getName();
        if (name.matches("(?:java|org\\.apache\\.sshd)\\.[A-Za-z0-9_.$]{1,160}"))
            failureType = name;
    }

    @Override
    public void sessionDisconnect(Session value, int reason, String message, String language, boolean initiator) {
        disconnectCode = reason;
        localDisconnect = initiator;
    }

    @Override
    public void sessionClosed(Session value) {
        sessionClosed = true;
    }

    void appendTo(Map<String, String> facts) {
        facts.put("transportFailureType", failureType);
        facts.put("disconnectCode", Integer.toString(disconnectCode));
        facts.put("disconnectInitiatorLocal", Boolean.toString(localDisconnect));
        facts.put("sessionCloseObserved", Boolean.toString(sessionClosed));
        if (session != null) {
            var state = session.getKexState();
            facts.put("transportKeyExchangeState", state == null ? "UNKNOWN" : state.name());
            SshReadDiagnostics.appendTo(reading, facts);
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
