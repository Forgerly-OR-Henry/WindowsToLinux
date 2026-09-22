package gold.debug.windowstolinux.web.service.contract;

/**
 * Expresses the business result that the executor maps to a durable terminal task state.
 * <p>表达由执行器映射为持久化任务终态的业务结果。
 */
public enum OperationCompletionState {
    /**
     * SUCCEEDED classification within operation completion state.
     * <p>操作完成状态中的已成功分类。
     */
    SUCCEEDED,
    /**
     * FAILED classification within operation completion state.
     * <p>操作完成状态中的失败分类。
     */
    FAILED,
    /**
     * REVALIDATION REQUIRED classification within operation completion state.
     * <p>操作完成状态中的重新验证必需分类。
     */
    REVALIDATION_REQUIRED
}
