package gold.debug.windowstolinux.app.ui.component;

import javax.swing.SwingWorker;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

/**
 * Runs one background operation and returns completion to the Swing event thread. / 运行后台操作并将完成结果返回 Swing 事件线程。
 */
public final class DesktopTaskExecutor {
    /**
     * ACTIVE.
     * <p>活跃。
     */
    private static final java.util.concurrent.atomic.AtomicInteger ACTIVE = new java.util.concurrent.atomic.AtomicInteger();
    /**
     * Prevents instantiation of this static contract helper.
     * <p>防止实例化当前静态契约辅助类。
     */
    private DesktopTaskExecutor() {
    }

    /**
     * Prevents appearance rebuilds from detaching callbacks from any active page operation. / 防止外观重建使回调脱离任何正在执行的页面操作。
     *
     * @return true when prevents appearance rebuilds from detaching callbacks from any active page operation, false otherwise / 防止外观重建使回调脱离任何正在执行的页面操作时为 true，否则为 false
     */
    public static boolean hasActiveTasks() { return ACTIVE.get() > 0; }

    /**
     * Executes a business-neutral Swing task with explicit success and failure callbacks. / 使用显式成功和失败回调执行无业务依赖的 Swing 任务。
     *
     * @param <T> type of the contract payload / 契约载荷的类型
     * @param operation operation / 操作
     * @param success success / 成功
     * @param failure structured failure occurrence retained for safe reporting / 保留用于安全报告的结构化失败实例
     */
    public static <T> void run(Callable<T> operation, Consumer<T> success, Consumer<Exception> failure) {
        submit(operation, success, failure);
    }

    /**
     * Returns a cancellation handle while retaining completion-after-cleanup semantics. / 返回取消句柄，保持资源清理后才完成的语义。
     *
     * @param <T> type of the contract payload / 契约载荷的类型
     * @param operation operation / 操作
     * @param success success / 成功
     * @param failure structured failure occurrence retained for safe reporting / 保留用于安全报告的结构化失败实例
     * @return a cancellation handle while retaining completion-after-cleanup semantics / 取消句柄，保持资源清理后才完成的语义
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public static <T> DesktopTaskHandle submit(Callable<T> operation, Consumer<T> success, Consumer<Exception> failure) {
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(success, "success");
        Objects.requireNonNull(failure, "failure");
        ACTIVE.incrementAndGet();
        DesktopTaskHandle handle = new DesktopTaskHandle();
        new SwingWorker<T, Void>() {
            /**
             * Returns do in background.
             * <p>返回do在背景。
             *
             * @return do in background / do在背景
             * @throws Exception if the delegated operation or caller-provided interaction fails / 被委派操作或调用方提供的交互失败时
             */
            @Override
            protected T doInBackground() throws Exception {
                try { handle.begin(); return operation.call(); }
                finally { handle.end(); }
            }

            /**
             * Delivers the worker result or failure on the Swing event thread after checking cancellation.
             * <p>检查取消状态后，在 Swing 事件线程交付工作结果或失败。
             */
            @Override
            protected void done() {
                try {
                    if (handle.cancelled()) throw new java.util.concurrent.CancellationException("Desktop task cancelled");
                    success.accept(get());
                } catch (Exception exception) {
                    failure.accept(exception);
                } finally {
                    ACTIVE.decrementAndGet();
                }
            }
        }.execute();
        return handle;
    }
}
