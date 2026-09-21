package gold.debug.windowstolinux.shared.backup.contract.spi;

/**
 * Caller cancellation and progress outside the mandatory recovery window. / 必要恢复窗口之外的调用方取消与进度交互。
 */
public interface BackupCollectionInteraction {
    /**
     * Stops further collection when the caller has cancelled; the shared service still completes necessary runtime recovery.
     * <p>调用方取消时停止进一步采集；共享服务仍会完成必要运行恢复。
     *
     * @throws InterruptedException if the waiting or worker thread is interrupted / 等待线程或工作线程被中断时
     */
    void checkCancelled() throws InterruptedException;
    /**
     * Reports the next component before downloading its material; this callback runs outside the mandatory recovery steps.
     * <p>在下载素材前报告下一组件；当前回调在必要恢复步骤之外执行。
     *
     * @param componentId identifier within the reviewed component graph / 已审阅组件图内的标识
     * @throws Exception if the delegated operation or caller-provided interaction fails / 被委派操作或调用方提供的交互失败时
     */
    void collecting(String componentId) throws Exception;
}
