package gold.debug.windowstolinux.app.ui.mod;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import javax.swing.SwingUtilities;

import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.Problem;
import gold.debug.windowstolinux.appapi.task.result.ProblemCode;

/** Retry-safe EDT dispatch and fixed public diagnostics. / 可重试的 EDT 调度及固定公开诊断。 */
final class UiExtensionDispatch {
    /** Prevents allocation. / 禁止实例化。 */
    private UiExtensionDispatch() {
    }

    /** Dispatches without abandoning queued cleanup on interruption. / 中断时不遗弃已排队清理。
     * @param <T> actual result / 实际结果
     * @param action EDT work / EDT 工作
     * @return actual result / 实际结果
     * @throws ApiException if dispatch or work fails / 调度或工作失败时 */
    static <T> T call(Callable<T> action) throws ApiException {
        var future = new FutureTask<>(action);
        if (SwingUtilities.isEventDispatchThread()) future.run();
        else SwingUtilities.invokeLater(future);
        boolean interrupted = false;
        try {
            while (true) {
                try {
                    return future.get();
                } catch (InterruptedException waiting) {
                    interrupted = true;
                } catch (ExecutionException failed) {
                    fatal(failed.getCause());
                    if (failed.getCause() instanceof ApiException problem) throw problem;
                    throw failure(ProblemCode.CONTRACT_VIOLATION, "UI_EXTENSION_CALLBACK_FAILED");
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    /** Keeps fatal JVM failures outside ordinary diagnostics. / 不把致命 JVM 故障转为普通诊断。
     * @param failure original failure / 原故障 */
    static void fatal(Throwable failure) {
        if (failure instanceof VirtualMachineError fatal) throw fatal;
        if (failure instanceof ThreadDeath fatal) throw fatal;
    }

    /** Builds fixed diagnostics without author data. / 创建不含作者数据的固定诊断。
     * @param code public classification / 公开分类
     * @param event stable event / 稳定事件
     * @return safe failure / 安全故障 */
    static ApiException failure(ProblemCode code, String event) {
        return new ApiException(new Problem(code, event, "The UI extension operation could not complete",
            Map.of(), Optional.empty()));
    }
}
