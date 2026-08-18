package gold.debug.windowstolinux.shared.linux.sshd.session;

import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.session.ClientSession;

import java.io.IOException;
import java.time.Duration;

/** Closes Apache SSHD session resources without obscuring the primary operation result. / 关闭 Apache SSHD 会话资源且不掩盖首要操作结果。 */
public final class SshSessionCloser {
    private static final Duration CLOSE_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration WINDOWS_NIO2_COMPLETION_GRACE = Duration.ofMillis(500);

    private SshSessionCloser() {
    }

    /** Closes a client and stops its resources. / 关闭客户端并停止其资源。 */
    public static void closeQuietly(SshClient client) {
        try {
            if (!client.close(false).await(CLOSE_TIMEOUT)) {
                client.close(true).await(CLOSE_TIMEOUT);
            }
            client.stop();
        } catch (IOException | RuntimeException ignored) {
            // A failed connection should not obscure its safe primary error. / 连接失败不应掩盖其安全的首要错误。
        }
    }

    /** Closes a client session and drains Windows NIO2 completion briefly. / 关闭客户端会话并短暂排空 Windows NIO2 完成回调。 */
    public static void closeQuietly(ClientSession session) {
        try {
            if (!session.close(false).await(CLOSE_TIMEOUT)) {
                session.close(true).await(CLOSE_TIMEOUT);
            }
            awaitWindowsNio2Completion();
        } catch (IOException | RuntimeException ignored) {
            // Session shutdown cannot change the already completed operation result. / 会话关闭不能改变已完成操作的结果。
        }
    }

    private static void awaitWindowsNio2Completion() {
        if (!System.getProperty("os.name", "").startsWith("Windows")) {
            return;
        }
        try {
            Thread.sleep(WINDOWS_NIO2_COMPLETION_GRACE.toMillis());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
