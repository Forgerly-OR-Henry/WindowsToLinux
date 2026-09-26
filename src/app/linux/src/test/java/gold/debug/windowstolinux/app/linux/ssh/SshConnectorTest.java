package gold.debug.windowstolinux.app.linux.ssh;

import static org.junit.jupiter.api.Assertions.*;

import java.net.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import javax.swing.SwingUtilities;

import gold.debug.windowstolinux.appapi.task.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SshConnectorTest {
    @TempDir
    Path root;

    @Test
    void selectedPasswordAndExplicitWireKeyTrustOwnOneActualConnection() throws Exception {
        var passwords = new AtomicInteger();
        var keys = new AtomicInteger();
        var trusted = new AtomicReference<String>();
        try (var server = new LoopbackSshServer(root, ssh -> {
            ssh.setPasswordAuthenticator((user, password, session) -> {
                passwords.incrementAndGet();
                return user.equals("fixture") && password.equals("isolated-ssh-password");
            });
            ssh.setPublickeyAuthenticator((user, key, session) -> {
                keys.incrementAndGet();
                return false;
            });
        }); var connector = new SshConnector()) {
            var endpoint = endpoint(server.server().getPort());
            char[] supplied = "isolated-ssh-password".toCharArray();
            var connection = connector.connect(endpoint, supplied, (observed, fingerprint) -> {
                assertEquals(endpoint, observed);
                assertTrue(fingerprint.startsWith("SHA256:"));
                trusted.set(fingerprint);
                return true;
            }, Duration.ofSeconds(3), () -> false);
            assertEquals("isolated-ssh-password", new String(supplied));
            assertEquals(1, passwords.get());
            assertEquals(0, keys.get());
            assertEquals(trusted.get(), connection.fingerprint());
            assertEquals(endpoint, connection.endpoint());
            assertEquals(1, connector.retainedConnections());
            assertTrue(connection.connected());
            connection.close();
            connection.close();
            assertFalse(connection.connected());
            assertEquals(0, connector.retainedConnections());
        }
    }

    @Test
    void rejectedHostKeyNeverOffersCredentialsAndFailureCanBeRetriedExplicitly() throws Exception {
        var attempts = new AtomicInteger();
        try (var server = new LoopbackSshServer(root, ssh -> ssh.setPasswordAuthenticator((u, p, s) -> {
            attempts.incrementAndGet();
            return true;
        })); var connector = new SshConnector()) {
            var rejected = assertThrows(ApiException.class, () -> connector.connect(endpoint(server.server().getPort()),
                    "fixture".toCharArray(), (e, key) -> false, Duration.ofSeconds(3), () -> false));
            assertEquals(ProblemCode.ACCESS_DENIED, rejected.problem().code());
            assertEquals("SSH_HOST_KEY_REJECTED", rejected.problem().eventCode());
            assertEquals(0, attempts.get());
            assertEquals(0, connector.retainedConnections());
            try (var accepted = connector.connect(endpoint(server.server().getPort()), "fixture".toCharArray(),
                    (e, key) -> true, Duration.ofSeconds(3), () -> false)) {
                assertEquals(1, connector.retainedConnections());
            }
        }
    }

    @Test
    void authenticationFailureClosesActualClientWithoutRetainingSecretDiagnostics() throws Exception {
        try (var server = new LoopbackSshServer(root, ssh -> ssh.setPasswordAuthenticator((u, p, s) -> false));
                var connector = new SshConnector()) {
            var denied = assertThrows(ApiException.class, () -> connector.connect(endpoint(server.server().getPort()),
                    "never-log-this-password".toCharArray(), (e, key) -> true, Duration.ofSeconds(3), () -> false));
            assertEquals(ProblemCode.ACCESS_DENIED, denied.problem().code());
            assertFalse(denied.toString().contains("never-log"));
            assertNull(denied.getCause());
            assertEquals(0, connector.retainedConnections());
        }
    }

    @Test
    void shutdownDoesNotAbandonATrustDecisionAndSealsLaterDelivery() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var server = new LoopbackSshServer(root, ssh -> {
        }); var connector = new SshConnector(); var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var connecting = executor.submit(
                    () -> connector.connect(endpoint(server.server().getPort()), "fixture".toCharArray(), (e, key) -> {
                        entered.countDown();
                        try {
                            assertTrue(release.await(5, TimeUnit.SECONDS));
                        } catch (InterruptedException interrupted) {
                            throw new AssertionError(interrupted);
                        }
                        return true;
                    }, Duration.ofSeconds(8), () -> false));
            try {
                assertTrue(entered.await(3, TimeUnit.SECONDS));
                assertEquals(ProblemCode.BUSY, assertThrows(ApiException.class, connector::close).problem().code());
                assertEquals(1, connector.retainedConnections());
            } finally {
                release.countDown();
            }
            var rejected = assertThrows(ExecutionException.class, () -> connecting.get(8, TimeUnit.SECONDS));
            assertInstanceOf(ApiException.class, rejected.getCause());
            assertEquals(ProblemCode.CANCELLED, ((ApiException) rejected.getCause()).problem().code());
            assertEquals(0, connector.retainedConnections());
        }
    }

    @Test
    void silentPeerIsBoundedByTimeoutAndActualClientRelease() throws Exception {
        try (var listener = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
                var connector = new SshConnector();
                var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var peer = executor.submit(() -> {
                try (var socket = listener.accept()) {
                    socket.setSoTimeout(3000);
                    try {
                        while (socket.getInputStream().read() != -1) {
                        }
                    } catch (SocketException resetByClosedClient) {
                        /* A closed Windows TCP peer may reset instead of EOF. */ }
                    return true;
                }
            });
            var expired = assertThrows(ApiException.class, () -> connector.connect(endpoint(listener.getLocalPort()),
                    "fixture".toCharArray(), (e, key) -> true, Duration.ofMillis(150), () -> false));
            assertEquals(ProblemCode.TIMEOUT, expired.problem().code());
            assertEquals(0, connector.retainedConnections());
            assertTrue(peer.get(4, TimeUnit.SECONDS));
        }
    }

    @Test
    void cancellationAndEdtRejectionDoNotStartConnections() throws Exception {
        try (var connector = new SshConnector()) {
            assertEquals(ProblemCode.CANCELLED, assertThrows(ApiException.class, () -> connector.connect(endpoint(22),
                    "fixture".toCharArray(), (e, key) -> true, Duration.ofSeconds(2), () -> true)).problem().code());
            var rejected = new AtomicReference<Throwable>();
            SwingUtilities.invokeAndWait(() -> {
                try {
                    connector.connect(endpoint(22), "fixture".toCharArray(), (e, key) -> true, Duration.ofSeconds(2),
                            () -> false);
                } catch (Throwable problem) {
                    rejected.set(problem);
                }
            });
            assertInstanceOf(IllegalStateException.class, rejected.get());
            assertEquals(0, connector.retainedConnections());
        }
    }

    @Test
    void cancellationDuringHandshakeClosesActualClientWithoutInterruptingTheWorker() throws Exception {
        var cancelled = new AtomicBoolean();
        var accepted = new CountDownLatch(1);
        try (var listener = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
                var connector = new SshConnector();
                var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var peer = executor.submit(() -> {
                try (var socket = listener.accept()) {
                    socket.setSoTimeout(3000);
                    accepted.countDown();
                    try {
                        while (socket.getInputStream().read() != -1) {
                        }
                    } catch (SocketException closedPeer) {
                    }
                    return true;
                }
            });
            var connecting = executor.submit(() -> {
                var failure = assertThrows(ApiException.class,
                        () -> connector.connect(endpoint(listener.getLocalPort()), "fixture".toCharArray(),
                                (e, key) -> true, Duration.ofSeconds(3), cancelled::get));
                assertFalse(Thread.currentThread().isInterrupted());
                return failure;
            });
            assertTrue(accepted.await(2, TimeUnit.SECONDS));
            cancelled.set(true);
            assertEquals(ProblemCode.CANCELLED, connecting.get(4, TimeUnit.SECONDS).problem().code());
            assertTrue(peer.get(4, TimeUnit.SECONDS));
            assertEquals(0, connector.retainedConnections());
        }
    }

    @Test
    void peerDisconnectDoesNotDiscardOwnedClientResources() throws Exception {
        try (var server = new LoopbackSshServer(root, ssh -> {
        }); var connector = new SshConnector()) {
            var connection = connector.connect(endpoint(server.server().getPort()), "fixture".toCharArray(),
                    (e, key) -> true, Duration.ofSeconds(3), () -> false);
            for (var session : server.server().getActiveSessions())
                assertTrue(session.close(true).await(2000));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (connection.connected() && System.nanoTime() < deadline)
                Thread.sleep(20);
            assertFalse(connection.connected());
            assertEquals(1, connector.retainedConnections());
            connection.close();
            assertEquals(0, connector.retainedConnections());
        }
    }

    static SshEndpoint endpoint(int port) {
        return new SshEndpoint("fixture", "127.0.0.1", port, "fixture");
    }
}
