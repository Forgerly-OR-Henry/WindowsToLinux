package gold.debug.windowstolinux.appapi.task;

/**
 * Diagnostic severity independent of business operation state. / 独立于业务操作状态的诊断级别。
 */
public enum LogLevel {
    /**
     * Detailed diagnostics for investigation. / 排查使用的详细诊断。
     */
    DEBUG,
    /**
     * Ordinary lifecycle or business observations. / 普通生命周期或业务观察。
     */
    INFO,
    /**
     * An abnormal condition requiring attention. / 需要关注的异常条件。
     */
    WARN,
    /**
     * A failed operation with diagnostic evidence. / 带诊断证据的失败操作。
     */
    ERROR
}
