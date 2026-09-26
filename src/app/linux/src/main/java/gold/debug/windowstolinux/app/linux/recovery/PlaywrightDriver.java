package gold.debug.windowstolinux.app.linux.recovery;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import com.microsoft.playwright.Playwright;

/** Retains the original Playwright connection and native driver until exit is proved. / 保留原 Playwright 连接及原生驱动，直到证实退出。 */
final class PlaywrightDriver {
    /** Exact process field in the pinned Playwright implementation. / 固定 Playwright 实现中的精确进程字段。 */
    private final Field processField;

    /** Original connection, retained across close failures. / 原连接，跨关闭失败保留。 */
    private Playwright connection;

    /** Original Process object, never reconstructed from a PID or process name. / 原 Process 对象，绝不由 PID 或进程名称重建。 */
    private Process process;

    /** Creation may have allocated native resources even if the library throws. / 即使依赖抛出异常，创建也可能已分配原生资源。 */
    private boolean creationAttempted;

    /** The original connection accepted close; subsequent attempts only observe exit. / 原连接已接受关闭，后续尝试仅观察退出。 */
    private boolean closeRequested;

    /** Rejects unsupported implementation layouts before any driver allocation. / 分配任何驱动前拒绝不支持的实现结构。
     * @throws ReflectiveOperationException if the pinned process owner is inaccessible / 固定版本的进程所有者不可访问时 */
    PlaywrightDriver() throws ReflectiveOperationException {
        processField = Class.forName("com.microsoft.playwright.impl.PlaywrightImpl").getDeclaredField("driverProcess");
        if (processField.getType() != Process.class || !processField.trySetAccessible())
            throw new IllegalAccessException("Playwright driver process ownership is unavailable");
    }

    /** Creates and retains the exact driver before any browser can start. / 启动任何浏览器前创建并保留精确驱动。
     * @return original Playwright connection / 原 Playwright 连接
     * @throws ReflectiveOperationException if actual process ownership cannot be captured / 无法捕获实际进程归属时 */
    Playwright start() throws ReflectiveOperationException {
        if (creationAttempted)
            throw new IllegalStateException("Original driver creation cannot be replayed");
        creationAttempted = true;
        connection = Playwright.create(new Playwright.CreateOptions()
                .setEnv(Map.of("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1", "DEBUG", "", "PWDEBUG", "0")));
        process = (Process) processField.get(connection);
        if (process == null)
            throw new IllegalStateException("Original driver process was not captured");
        return connection;
    }

    /** Retains failed connection cleanup; after close returns, only waits for original process exit. / 保留失败的连接清理；关闭返回后仅等待原进程退出。
     * @throws BrowserRecoveryException if creation, connection close or physical exit remains unconfirmed / 创建、连接关闭或物理退出仍未确认时 */
    void close() {
        if (!creationAttempted)
            return;
        if (!closeRequested && connection != null) {
            connection.close();
            closeRequested = true;
        }
        if (process == null)
            throw new BrowserRecoveryException(BrowserRecoveryException.Reason.CLEANUP_FAILED, null);
        try {
            if (!process.waitFor(5, TimeUnit.SECONDS))
                throw new BrowserRecoveryException(BrowserRecoveryException.Reason.CLEANUP_FAILED, null);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new BrowserRecoveryException(BrowserRecoveryException.Reason.CLEANUP_FAILED, failure);
        }
        connection = null;
    }
}
