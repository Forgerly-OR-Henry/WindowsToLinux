package gold.debug.windowstolinux.appapi.task;

/**
 * Owned structured diagnostics with explicit storage results. / 明确返回存储结果的所属结构化诊断服务。
 */
public interface LogService {

    /**
     * Registers an owned contribution; visibility follows successful startup. / 登记所属扩展；仅在启动成功后对外可见。
     * @param category owner-local diagnostic category / 所有者内部的诊断类别
     * @return the owned result described by this capability / 此能力约定的所属结果
     * @throws ApiException if validation, execution or actual cleanup fails / 校验、执行或实际清理失败时
     */
    LogTarget register(LogCategory category) throws ApiException;

    /**
     * Records a diagnostic and reports the actual storage outcome. / 记录诊断并返回实际存储结果。
     * @param target owned target of the requested operation / 请求操作的所属目标
     * @param event structured diagnostic event without secrets / 不含秘密的结构化诊断事件
     * @return the owned result described by this capability / 此能力约定的所属结果
     */
    LogWriteResult write(LogTarget target, LogEvent event);
}
