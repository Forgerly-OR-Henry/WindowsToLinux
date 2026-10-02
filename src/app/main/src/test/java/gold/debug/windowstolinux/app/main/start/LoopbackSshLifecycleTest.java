package gold.debug.windowstolinux.app.main.start;

import static org.junit.jupiter.api.Assertions.*;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LoopbackSshLifecycleTest {
    @TempDir
    Path root;

    @Test
    void closesPendingSessionReadsAndPhysicallyDrainsCallbacksBeforeReturning() throws Exception {
        for (int attempt = 0; attempt < 12; attempt++) {
            try (var socket = new Socket(); var fixture = new LoopbackSshServer(root, server -> {
            })) {
                socket.connect(new InetSocketAddress("127.0.0.1", fixture.server().getPort()), 2000);
                socket.setSoTimeout(2000);
                socket.getOutputStream().write("SSH-2.0-owned-lifecycle\r\n".getBytes(StandardCharsets.US_ASCII));
                socket.getOutputStream().flush();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                while (fixture.server().getActiveSessions().isEmpty()) {
                    assertTrue(System.nanoTime() < deadline, "The owned connection was never accepted");
                    Thread.sleep(10);
                }
                fixture.close();
                assertTrue(fixture.server().isClosed());
                assertTrue(fixture.executorsShutdown());
                assertEquals(0, fixture.pendingAccepts());
                try {
                    int remaining = 4096;
                    while (socket.getInputStream().read() != -1)
                        assertTrue(--remaining > 0, "Closed fixture kept producing data");
                } catch (SocketException peerReset) {
                    // An unfinished SSH handshake may close TCP with a reset; all server workers are already drained.
                    assertFalse(socket.isClosed(), "The still-open local socket did not cause the peer closure");
                }
            }
        }
    }
}
