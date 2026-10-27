package gold.debug.windowstolinux.app.linux.ssh.connect;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.sshd.common.io.IoHandler;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.io.IoSession;
import org.apache.sshd.common.util.Readable;
import org.apache.sshd.common.util.buffer.ByteArrayBuffer;
import org.junit.jupiter.api.Test;

class SshReadDiagnosticsTest {
    @Test
    void blockedReadReportsBoundedFramesAndPreservesOriginalFailureWithoutPayloadText() throws Exception {
        var attributes = new ConcurrentHashMap<Object, Object>();
        var session = (IoSession) Proxy.newProxyInstance(IoSession.class.getClassLoader(), new Class<?>[]{IoSession.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "setAttribute" -> attributes.put(args[0], args[1]);
                case "getAttribute" -> attributes.get(args[0]);
                default -> throw new AssertionError(method.getName());
            });
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var failure = new IOException("private peer payload and credential");
        var handler = new SshReadDiagnostics(new IoHandler() {
            public void sessionCreated(IoSession value) {
                assertSame(session, value);
            }

            public void sessionClosed(IoSession value) {
                assertSame(session, value);
            }

            public void exceptionCaught(IoSession value, Throwable problem) {
                assertSame(session, value);
                assertSame(failure, problem);
            }

            public void messageReceived(IoSession value, Readable message) throws Exception {
                entered.countDown();
                assertTrue(release.await(5, TimeUnit.SECONDS));
                throw failure;
            }
        });
        handler.sessionCreated(session);
        var observed = new AtomicReference<Throwable>();
        var worker = Thread.ofPlatform().start(() -> {
            try {
                handler.messageReceived(session, new ByteArrayBuffer("private author script".getBytes()));
            } catch (Throwable problem) {
                observed.set(problem);
            }
        });
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var facts = new LinkedHashMap<String, String>();
            SshReadDiagnostics.appendTo(session, facts);
            assertEquals("1", facts.get("transportReadCallbacks"));
            assertEquals("true", facts.get("transportReadHandlerActive"));
            assertEquals("UNKNOWN", facts.get("transportReadCompletedAgeMillis"));
            assertTrue(facts.size() <= 12);
            assertFalse(facts.toString().contains("private"));
        } finally {
            release.countDown();
            assertTrue(worker.join(Duration.ofSeconds(5)));
        }
        assertSame(failure, observed.get());
        var facts = new LinkedHashMap<String, String>();
        SshReadDiagnostics.appendTo(session, facts);
        assertEquals("false", facts.get("transportReadHandlerActive"));
        assertTrue(Long.parseLong(facts.get("transportReadCompletedAgeMillis")) >= 0);
        handler.exceptionCaught(session, failure);
        handler.sessionClosed(session);
        attributes.clear();
        assertSame(failure, assertThrows(IOException.class,
            () -> handler.messageReceived(session, new ByteArrayBuffer(new byte[0]))));
    }

    @Test
    void ioFailurePrecedesGenericCommandFailureAndReadCountsHaveCommandBaseline() throws Exception {
        var attributes = new ConcurrentHashMap<Object, Object>();
        var session = (IoSession) Proxy.newProxyInstance(IoSession.class.getClassLoader(), new Class<?>[]{IoSession.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "setAttribute" -> attributes.put(args[0], args[1]);
                case "getAttribute" -> attributes.get(args[0]);
                default -> throw new AssertionError(method.getName());
            });
        var forwarded = new AtomicReference<Throwable>();
        var handler = new SshReadDiagnostics(new IoHandler() {
            public void sessionCreated(IoSession value) {}
            public void sessionClosed(IoSession value) {}
            public void exceptionCaught(IoSession value, Throwable problem) { forwarded.set(problem); }
            public void messageReceived(IoSession value, Readable message) {}
        });
        handler.sessionCreated(session);
        for (int index = 0; index < 2; index++)
            handler.messageReceived(session, new ByteArrayBuffer(new byte[0]));
        var client = (ClientSession) Proxy.newProxyInstance(ClientSession.class.getClassLoader(),
            new Class<?>[]{ClientSession.class}, (proxy, method, args) -> switch (method.getName()) {
                case "getIoSession" -> session;
                case "addSessionListener", "removeSessionListener", "getKexState" -> null;
                default -> throw new AssertionError(method.getName());
            });
        var diagnostic = new SshCommandDiagnostics();
        diagnostic.attach(client);
        handler.messageReceived(session, new ByteArrayBuffer(new byte[0]));
        var original = new java.net.SocketException("private transport address");
        original.initCause(new java.io.EOFException("private payload"));
        handler.exceptionCaught(session, original);
        assertSame(original, forwarded.get());
        handler.exceptionCaught(session, new IOException("late private generic failure"));
        diagnostic.failure(new IOException("private command failure"));
        var facts = new LinkedHashMap<String, String>();
        diagnostic.appendTo(facts);
        assertEquals("java.net.SocketException", facts.get("transportFailureType"));
        assertEquals("java.io.EOFException", facts.get("transportFailureCauseType"));
        assertEquals("3", facts.get("transportReadCallbacks"));
        assertEquals("2", facts.get("transportReadCallbacksAtCommandStart"));
        assertEquals("1", facts.get("transportReadCallbacksDuringCommand"));
        assertTrue(Long.parseLong(facts.get("transportSessionAgeMillis")) >= 0);
        assertFalse(facts.toString().contains("private"));
        diagnostic.close();
    }
}
