package gold.debug.windowstolinux.app.linux.ssh.connect;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.sshd.client.SshClient;
import org.apache.sshd.common.session.Session;
import org.apache.sshd.common.session.SessionListener;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClientIoLifecycleTest {
    @TempDir
    Path root;

    @Test
    void logicalSessionClosureDoesNotProveNativeCallbackExitAndSameWorkerCannotJoinItself() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var selfClose = new AtomicReference<Boolean>();
        var owner = new ClientIoLifecycle();
        try (var server = new LinuxLoopbackSshServer(root, ssh -> {
        }); var client = SshClient.setUpDefaultClient()) {
            client.setIoServiceFactoryFactory(owner);
            client.setServerKeyVerifier((c, address, key) -> true);
            client.addSessionListener(new SessionListener() {
                @Override
                public void sessionClosed(Session session) {
                    try {
                        selfClose.set(owner.close(Duration.ofSeconds(1)));
                        entered.countDown();
                        assertTrue(release.await(8, TimeUnit.SECONDS));
                    } catch (Exception failure) {
                        throw new AssertionError("Native callback probe failed", failure);
                    }
                }
            });
            try {
                client.start();
                var session = client.connect("fixture", "127.0.0.1", server.server().getPort())
                    .verify(Duration.ofSeconds(3)).getSession();
                session.addPasswordIdentity("fixture");
                session.auth().verify(Duration.ofSeconds(3));
                var workers = SshConnectorTest.clientIoWorkers();
                assertTrue(workers.size() >= 3);
                for (var remote : server.server().getActiveSessions())
                    assertTrue(remote.close(true).await(Duration.ofSeconds(2)));
                assertTrue(entered.await(3, TimeUnit.SECONDS));
                assertEquals(Boolean.FALSE, selfClose.get(), "An I/O worker cannot prove its own termination");
                assertTrue(client.close(true).await(Duration.ofSeconds(2)));
                assertTrue(client.isClosed());
                assertTrue(session.getIoSession().isClosed());
                assertFalse(session.isClosed(), "The logical client can close before sessionClosed returns");
                assertFalse(owner.close(Duration.ofMillis(50)),
                    "Logical SSHD close must not hide a still-running native callback");
                assertTrue(workers.stream().anyMatch(Thread::isAlive));
                release.countDown();
                assertTrue(owner.close(Duration.ofSeconds(3)), "Another worker can retry physical cleanup");
                assertTrue(workers.stream().noneMatch(Thread::isAlive));
                assertTrue(session.isClosed());
            } finally {
                release.countDown();
                assertTrue(client.close(true).await(Duration.ofSeconds(3)));
                assertTrue(owner.close(Duration.ofSeconds(5)));
            }
        }
    }
}
