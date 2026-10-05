package gold.debug.windowstolinux.appapi.task.log;

import gold.debug.windowstolinux.appapi.task.result.ApiException;

import java.util.Optional;
import java.util.UUID;

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

    /** Reads the retained redacted window of an original target belonging to this activation. / 读取本启用所属原目标的保留脱敏窗口。
     * @param target original opaque target, never a copied UUID / 原不透明目标，不能是复制的UUID
     * @param taskId optional association filter without task-operation authority / 不授予任务操作权的可选关联筛选
     * @param limit maximum records from one to one hundred / 一至一百的最大记录数
     * @return current-version observations and explicit partial-storage issues / 当前版本观测及明确的部分存储问题
     * @throws ApiException if ownership, worker, lock or lifetime access is refused / 归属、工作线程、锁定或寿命访问被拒绝时 */
    LogReadResult read(LogTarget target, Optional<UUID> taskId, int limit) throws ApiException;
}
