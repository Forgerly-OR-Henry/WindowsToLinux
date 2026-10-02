package gold.debug.windowstolinux.app.service.security;

import java.util.concurrent.Callable;

/** Owns one accepted desktop operation across worker threads. / 持有跨工作线程的已授权桌面操作。 */
public interface DesktopTaskAuthorization extends AutoCloseable {
    /** Runs work inside the retained authorization until execution cleanup finishes. / 在保留授权内执行工作，直至执行清理结束。
     * @param work operation executed within the retained boundary / 保留边界内执行的操作
     * @param <T> t supplied to this boundary / 传入当前边界的T值
     * @return the validated result or retained scoped handle / 已验证结果或保留的限定句柄
     * @throws Exception if validation or the scoped operation fails / 校验或限定操作失败时
     */
    <T> T execute(Callable<T> work) throws Exception;

    /** Releases this scope and its owned resources. / 释放当前作用域及其持有资源。
     */
    @Override
    void close();
}
