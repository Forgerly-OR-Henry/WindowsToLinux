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

    /** Reads the first bounded caller-owned page. / 读取绑定调用方的第一页有界任务。
     * @param limit existing bounded page capacity / 现有有界页容量
     * @return first page under the original access rules / 原访问规则下的第一页
     * @throws ApiException if input or access is invalid / 输入或访问无效时
     */
    default PageSlice<TaskSnapshot> list(int limit) throws ApiException {
        return list(Optional.empty(), limit);
    }

    /** Reads the authenticated original task identity during its current callback or delegated service call. / 在原回调或被委托服务调用期间读取已认证原任务身份。
     * @param task authentic original context, not a copied identifier / 真实原上下文，而非复制的标识
     * @return the original root owner's task snapshot / 原根所有者任务快照
     * @throws ApiException if the caller or current callback scope cannot be verified / 调用方或当前回调作用域无法核实时 */
    TaskSnapshot inspect(TaskContext task) throws ApiException;

    /**
     * Observes whether every explicit resource of the original running or reconciling task was actually released.
     * / 观察原执行或核对任务的全部显式资源是否确已释放。
     * @param task authentic original task context / 真实原任务上下文
     * @return true only when no explicit resource remains / 仅无显式资源残留时为真
     * @throws ApiException if the caller, task, or persisted observation is unavailable / 调用者、任务或持久观测不可用时
     */
    boolean resourcesReleased(TaskContext task) throws ApiException;

    /** Atomically retains one checkpoint of at most 12,000 UTF-8 bytes for the original task's root owner. / 为原任务根所有者原子保留一项最多 12,000 UTF-8 字节的检查点。
     * @param task authentic executing original task / 真实执行中的原任务
     * @param key stable owner-local checkpoint key / 稳定的所有者内部检查点键
     * @param initial bounded non-secret JSON object / 初始有界且不含秘密的 JSON 对象
     * @return original checkpoint handle / 原检查点句柄
     * @throws ApiException if an original or released key already exists, or admission fails / 原键或已释放键存在、准入失败时 */
    TaskCheckpoint beginCheckpoint(TaskContext task, String key, String initial) throws ApiException;

    /** Finds only an existing checkpoint for the original task's root owner in execution or recovery. / 仅查找原任务根所有者在执行或恢复中的既有检查点。
     * @param task authentic original task / 真实原任务
     * @param key stable owner-local checkpoint key / 稳定的所有者内部检查点键
     * @return original row, including released evidence before task archival / 原行，包括任务归档前已释放的依据
     * @throws ApiException if the caller or persisted identity cannot be verified / 无法核实调用者或持久身份时 */
    Optional<TaskCheckpoint> findCheckpoint(TaskContext task, String key) throws ApiException;

    /**
     * Registers manual reconciliation for retained tasks of the given type. / 为指定类型保留任务登记人工核对逻辑。
     * @param taskType owner-local type of recoverable task / 所有者内部可恢复任务的类型
     * @param recovery callback for explicit reconciliation of retained tasks / 显式核对保留任务的回调
     * @return the owned result described by this capability / 此能力约定的所属结果
     * @throws ApiException if validation, execution or actual cleanup fails / 校验、执行或实际清理失败时
     */
    RecoveryRegistration registerRecovery(String taskType, TaskRecovery recovery) throws ApiException;
}
