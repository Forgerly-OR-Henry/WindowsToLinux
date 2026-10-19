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
            assertTrue(facts.size() <= 11);
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
}
