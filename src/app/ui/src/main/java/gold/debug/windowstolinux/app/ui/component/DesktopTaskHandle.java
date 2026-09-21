package gold.debug.windowstolinux.app.ui.component;

import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Cancels one task by interrupting its own worker; completion waits for resource cleanup. / 通过中断其工作线程取消单个任务，完成回调等待资源清理。
 */
public final class DesktopTaskHandle {
    /**
     * Cancelled.
     * <p>已取消。
     */
    private final AtomicBoolean cancelled = new AtomicBoolean();
    /**
     * Worker.
     * <p>工作线程。
     */
    private final AtomicReference<Thread> worker = new AtomicReference<>();
    /**
     * Requests cancellation without interrupting another task. / 请求取消，不中断其他任务。
     */
    public synchronized void cancel() { cancelled.set(true); Thread current = worker.get(); if (current != null) current.interrupt(); }
    /**
     * Reports the cancellation request. / 返回是否已请求取消。
     *
     * @return true when reports the cancellation request, false otherwise / 返回是否已请求取消时为 true，否则为 false
     */
    public boolean cancelled() { return cancelled.get(); }
    /**
     * Begins desktop task handle.
     * <p>开始Desktop任务句柄。
     */
    void begin() { synchronized (this) { worker.set(Thread.currentThread()); if (cancelled()) Thread.currentThread().interrupt(); } }
    /**
     * Clears the worker reference when the tracked task has ended.
     * <p>所跟踪任务结束时清空工作线程引用。
     */
    synchronized void end() { worker.set(null); }
}
