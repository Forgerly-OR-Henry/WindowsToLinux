package gold.debug.windowstolinux.shared.linux.sshd.session;

import java.io.IOException;
import java.time.Duration;

import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.session.ClientSession;

/**
 * Closes Apache SSHD session resources without obscuring the primary operation result. / 关闭 Apache SSHD 会话资源且不掩盖首要操作结果。
 */
public final class SshSessionLifecycleExecutor {
    /**
     * CLOSE TIMEOUT.
     * <p>关闭超时。
     */
    private static final Duration CLOSE_TIMEOUT = Duration.ofSeconds(5);

    /**
     * WINDOWS NIO 2 COMPLETION GRACE.
     * <p>WindowsNIO2完成GRACE。
     */
    private static final Duration WINDOWS_NIO2_COMPLETION_GRACE = Duration.ofMillis(500);

    /**
     * Prevents instantiation of this static contract helper.
     * <p>防止实例化当前静态契约辅助类。
     */
    private SshSessionLifecycleExecutor() {
    }

    /**
     * Closes a client and stops its resources. / 关闭客户端并停止其资源。
     *
     * @param client client / 客户端
     */
    public static void closeQuietly(SshClient client) {
        closeConnection(client);
        try {
            client.stop();
        } catch (RuntimeException ignored) {
            // A failed connection should not obscure its safe primary error. / 连接失败不应掩盖其安全的首要错误。
        }
    }

    /**
     * Closes a client session and drains Windows NIO2 completion briefly. / 关闭客户端会话并短暂排空 Windows NIO2 完成回调。
     *
     * @param session session used for the current scoped operation / 当前限定作用域操作使用的会话
     */
    public static void closeQuietly(ClientSession session) {
        closeConnection(session);
        awaitWindowsNio2Completion();
    }

    /**
     * Attempts bounded graceful connection closure, then forces cleanup if graceful closure fails or times out.
     * <p>尝试有界优雅连接关闭，失败或超时后强制清理。
     *
     * @param connection connection scoped to the current database or remote operation / 限定于当前数据库或远端操作的连接
     */
    private static void closeConnection(org.apache.sshd.common.Closeable connection) {
        try {
            if (connection.close(false).await(CLOSE_TIMEOUT))
                return;
        } catch (IOException | RuntimeException ignored) {
            // A failed graceful close must still reach forced channel cleanup. / 优雅关闭异常后仍须执行强制通道回收。
        }
        try {
            connection.close(true).await(CLOSE_TIMEOUT);
        } catch (IOException | RuntimeException ignored) {
            // Preserve the primary operation result. / 保留首要操作结果。
        }
    }

    /**
     * Waits for windows nio 2 completion.
     * <p>等待WindowsNio2完成。
     */
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
