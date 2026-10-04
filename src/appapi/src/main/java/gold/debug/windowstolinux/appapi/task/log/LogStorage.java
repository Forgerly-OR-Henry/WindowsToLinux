package gold.debug.windowstolinux.appapi.task.log;

/**
 * Actual diagnostic storage outcome without a durability guess. / 不猜测持久化成功的实际诊断存储结果。
 */
public enum LogStorage {
    /**
     * Diagnostic persistence in the database was confirmed. / 已确认诊断持久化到数据库。
     */
    DATABASE,
    /**
     * Only the emergency diagnostic file was written. / 仅写入了紧急诊断文件。
     */
    EMERGENCY_FILE,
    /**
     * No durable diagnostic destination was confirmed. / 未确认任何诊断持久化位置。
     */
    NOT_SAVED
}
