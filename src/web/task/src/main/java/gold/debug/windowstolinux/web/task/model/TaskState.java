package gold.debug.windowstolinux.web.task.model;

/**
 * Defines public durable task states that cannot be assigned by the browser.
 * <p>定义不能由浏览器赋值的公开持久化任务状态。
 */
public enum TaskState {
    /**
     * QUEUED classification within task state.
     * <p>任务状态中的已入队分类。
     */
    QUEUED,
    /**
     * ANALYZING classification within task state.
     * <p>任务状态中的分析中分类。
     */
     ANALYZING,
    /**
     * WAITING DECISION classification within task state.
     * <p>任务状态中的等待决定分类。
     */
     WAITING_DECISION,
    /**
     * RUNNING classification within task state.
     * <p>任务状态中的运行中分类。
     */
     RUNNING,
    /**
     * CANCELLING classification within task state.
     * <p>任务状态中的取消中分类。
     */
     CANCELLING,
    /**
     * CANCELLED classification within task state.
     * <p>任务状态中的已取消分类。
     */
     CANCELLED,
    /**
     * SUCCEEDED classification within task state.
     * <p>任务状态中的已成功分类。
     */
     SUCCEEDED,
    /**
     * FAILED classification within task state.
     * <p>任务状态中的失败分类。
     */
     FAILED,
    /**
     * INTERRUPTED classification within task state.
     * <p>任务状态中的已中断分类。
     */
     INTERRUPTED,
    /**
     * REVALIDATION REQUIRED classification within task state.
     * <p>任务状态中的重新验证必需分类。
     */
     REVALIDATION_REQUIRED;
    /**
     * Tests the terminal predicate against the supplied evidence.
     * <p>根据所提供证据检查终端条件。
     *
     * @return true when terminal predicate against the supplied evidence, false otherwise / 根据所提供证据检查终端条件时为 true，否则为 false
     */
    public boolean terminal() { return switch (this) { case CANCELLED, SUCCEEDED, FAILED, INTERRUPTED, REVALIDATION_REQUIRED -> true; default -> false; }; }
}
