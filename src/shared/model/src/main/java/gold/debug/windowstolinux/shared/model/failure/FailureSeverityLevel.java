package gold.debug.windowstolinux.shared.model.failure;

/**
 * Ordered impact of one structured failure. / 单个结构化失败的有序影响等级。
 */
public enum FailureSeverityLevel {
    /**
     * The operation completed with a recoverable warning. / 操作已完成但存在可恢复警告。
     */
    WARNING,
    /**
     * The requested operation did not complete. / 请求的操作未完成。
     */
    ERROR,
    /**
     * The current process cannot safely continue. / 当前进程无法安全继续。
     */
    FATAL
}
