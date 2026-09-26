package gold.debug.windowstolinux.appapi.task;

import java.util.Optional;

/**
 * Caller-bound task admission, observation and reconciliation. / 绑定调用方的任务准入、观察与核对服务。
 */
public interface TaskService {

    /**
     * Admits the entire initial resource set atomically before running author work. / 运行作者逻辑前原子接纳完整初始资源集合。
     * @param <T> public payload or service contract type / 公开载荷或服务契约类型
     * @param request immutable capability request / 不可变的能力请求
     * @param work author callback executed in an admitted background task / 在已接纳后台任务中执行的作者回调
     * @return the owned result described by this capability / 此能力约定的所属结果
     * @throws ApiException if validation, execution or actual cleanup fails / 校验、执行或实际清理失败时
     */
    <T> TaskHandle<T> submit(TaskRequest request, TaskWork<T> work) throws ApiException;

    /**
     * Reads bounded caller-owned snapshots; cursors are not transaction snapshots. / 读取有界的调用方快照；游标不保证事务快照。
     * @param cursor optional opaque continuation cursor / 可选的不透明后续游标
     * @param limit maximum page size from one to two hundred / 一到二百之间的页容量上限
     * @return the owned result described by this capability / 此能力约定的所属结果
     * @throws ApiException if validation, execution or actual cleanup fails / 校验、执行或实际清理失败时
     */
    PageSlice<TaskSnapshot> list(Optional<String> cursor, int limit) throws ApiException;

    /**
     * Registers manual reconciliation for retained tasks of the given type. / 为指定类型保留任务登记人工核对逻辑。
     * @param taskType owner-local type of recoverable task / 所有者内部可恢复任务的类型
     * @param recovery callback for explicit reconciliation of retained tasks / 显式核对保留任务的回调
     * @return the owned result described by this capability / 此能力约定的所属结果
     * @throws ApiException if validation, execution or actual cleanup fails / 校验、执行或实际清理失败时
     */
    RecoveryRegistration registerRecovery(String taskType, TaskRecovery recovery) throws ApiException;
}
