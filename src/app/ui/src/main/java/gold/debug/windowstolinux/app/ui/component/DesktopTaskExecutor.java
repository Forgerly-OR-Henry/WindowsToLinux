package gold.debug.windowstolinux.app.ui.component;

import javax.swing.SwingWorker;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

/** Runs one background operation and returns completion to the Swing event thread. / 运行后台操作并将完成结果返回 Swing 事件线程。 */
public final class DesktopTaskExecutor {
    private static final java.util.concurrent.atomic.AtomicInteger ACTIVE = new java.util.concurrent.atomic.AtomicInteger();
    private DesktopTaskExecutor() {
    }

    /** Prevents appearance rebuilds from detaching callbacks from any active page operation. / 防止外观重建使回调脱离任何正在执行的页面操作。 */
    public static boolean hasActiveTasks() { return ACTIVE.get() > 0; }

    /** Executes a business-neutral Swing task with explicit success and failure callbacks. / 使用显式成功和失败回调执行无业务依赖的 Swing 任务。 */
    public static <T> void run(Callable<T> operation, Consumer<T> success, Consumer<Exception> failure) {
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(success, "success");
        Objects.requireNonNull(failure, "failure");
        ACTIVE.incrementAndGet();
        new SwingWorker<T, Void>() {
            @Override
            protected T doInBackground() throws Exception {
                return operation.call();
            }

            @Override
            protected void done() {
                try {
                    success.accept(get());
                } catch (Exception exception) {
                    failure.accept(exception);
                } finally {
                    ACTIVE.decrementAndGet();
                }
            }
        }.execute();
    }
}
