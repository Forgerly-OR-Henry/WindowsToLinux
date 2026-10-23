package gold.debug.windowstolinux.appapi.task;

import java.util.Optional;

import gold.debug.windowstolinux.appapi.task.model.TaskRequest;
import gold.debug.windowstolinux.appapi.task.model.TaskSnapshot;
import gold.debug.windowstolinux.appapi.task.query.PageSlice;
import gold.debug.windowstolinux.appapi.task.recover.RecoveryRegistration;
import gold.debug.windowstolinux.appapi.task.recover.TaskCheckpoint;
import gold.debug.windowstolinux.appapi.task.recover.TaskRecovery;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.appapi.task.run.TaskHandle;
import gold.debug.windowstolinux.appapi.task.run.TaskWork;

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
     * Reads unfinished caller-owned tasks in submission order. Cursors retain the first page's admission upper bound,
     * so earlier completions do not shift later pages and later submissions are excluded. A cursor belongs to this
     * exact service binding and boot; it is not a transaction snapshot and completed tasks may disappear.
     * / 按提交顺序读取调用方未结束任务。游标保留首屏准入上限，早期任务结束不挤动后续页，后续提交不纳入；
     * 游标仅属于本服务绑定及本次启动，不保证事务快照，已结束任务可以消失。
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

    /** Reads a retained platform archive only for this exact owner and version, after its original execution and leases are gone. / 仅在原执行及占用已清除后读取本精确所有者版本的保留平台归档。
     * @param originalTask selected ended task UUID, without authority to replay it / 所选已结束任务UUID，不授予重放权
     * @param currentTask authentic current caller-owned read or maintenance task / 真实当前所属读取或维护任务
     * @return bounded known conclusion, or empty for absent, expired or incomplete proof / 有界已知结论，证明不存在、过期或不完整时为空
     * @throws ApiException if current scope or durable observation cannot be authenticated / 无法认证当前作用域或持久观测时 */
    Optional<TaskSnapshot> findArchived(java.util.UUID originalTask, TaskContext currentTask) throws ApiException;

    /**
     * Observes whether every explicit resource of the original running or reconciling task was actually released.
     * / 观察原执行或核对任务的全部显式资源是否确已释放。
     * @param task authentic original task context / 真实原任务上下文
     * @return true only when no explicit resource remains / 仅无显式资源残留时为真
     * @throws ApiException if the caller, task, or persisted observation is unavailable / 调用者、任务或持久观测不可用时
     */
    boolean resourcesReleased(TaskContext task) throws ApiException;

    /** Observes explicit resources while excluding only exact root-owned author checkpoints in the current recovery.
     * / 在当前原根恢复中，仅排除精确所属作者检查点后观察显式资源。
     * @param task authentic original reconciling context / 真实原核对上下文
     * @param keys one to sixteen distinct canonical author checkpoint keys / 一到十六项不同的规范作者检查点键
     * @return true only when every excluded checkpoint is authentic and no other explicit lease remains / 仅排除项全真实且无其他显式占用时为真
     * @throws ApiException if scope, checkpoint identity or durable observation is unavailable / 作用域、检查点身份或持久观测不可用时 */
    boolean resourcesReleasedExceptCheckpoints(TaskContext task, java.util.Set<String> keys) throws ApiException;

    /** Also requires the complete original author-checkpoint set, including released rows, in the same observation.
     * / 在同次观测中额外要求原作者检查点完整集合匹配，包括已释放行。
     * @param task authentic original root reconciliation / 真实原根核对
     * @param keys exact checkpoints excluded only from the explicit-resource predicate / 仅从显式资源判据排除的精确检查点
     * @param exactAuthorKeys complete expected set of one to sixteen canonical author keys, containing keys / 包含排除项的一到十六项完整规范作者键集合
     * @return true only for the exact complete author set and no other explicit resources / 仅作者集合完整精确匹配且无其他显式资源时为真
     * @throws ApiException if authentication, input or complete persisted observation fails / 鉴权、输入或完整持久观测失败时 */
    boolean resourcesReleasedExceptCheckpoints(TaskContext task, java.util.Set<String> keys,
                                               java.util.Set<String> exactAuthorKeys) throws ApiException;

    /** Atomically retains one checkpoint of at most 12,000 UTF-8 bytes for the authenticated root or provider in its current synchronous call. / 为已认证根所有者或当前同步调用提供方原子保留一项最多 12,000 UTF-8 字节的检查点。
     * @param task authentic executing original task / 真实执行中的原任务
     * @param key stable owner-local checkpoint key / 稳定的所有者内部检查点键
     * @param initial bounded non-secret JSON object / 初始有界且不含秘密的 JSON 对象
     * @return original checkpoint handle / 原检查点句柄
     * @throws ApiException if an original or released key already exists, or admission fails / 原键或已释放键存在、准入失败时 */
    TaskCheckpoint beginCheckpoint(TaskContext task, String key, String initial) throws ApiException;

    /** Finds only an existing checkpoint for the authenticated root or current synchronous provider in execution or original recovery. / 仅查找执行或原恢复中已认证根所有者或当前同步提供方的既有检查点。
     * @param task authentic original task / 真实原任务
     * @param key stable owner-local checkpoint key / 稳定的所有者内部检查点键
     * @return original row, including released evidence before task archival / 原行，包括任务归档前已释放的依据
     * @throws ApiException if the caller or persisted identity cannot be verified / 无法核实调用者或持久身份时 */
    Optional<TaskCheckpoint> findCheckpoint(TaskContext task, String key) throws ApiException;

    /** Creates bounded recovery progress only in the authenticated original root's current explicit reconciliation.
     * This is new recovery evidence, not an execution checkpoint or authority to replay an effect.
     * / 仅在已认证原根所有者当前显式核对中创建有界恢复进度；它不是原执行检查点或重放权限。
     * @param task authentic original reconciling context / 真实原核对上下文
     * @param key stable owner-local single-use key / 稳定且仅可创建一次的所属键
     * @param initial non-secret object of at most 12,000 UTF-8 bytes / 最多12000 UTF-8字节的非秘密对象
     * @return handle confined to this reconciliation / 仅限本次核对的句柄
     * @throws ApiException if scope, key or persistence cannot be confirmed / 作用域、键或持久化无法确认时 */
    TaskCheckpoint beginRecoveryCheckpoint(TaskContext task, String key, String initial) throws ApiException;

    /** Finds recovery progress in its distinct host namespace, including released evidence before archival.
     * Every handle operation requires the same active reconciliation; author rows are never imported.
     * / 在独立宿主命名空间查找恢复进度，包括归档前已释放依据；每次句柄操作均要求同次核对，不导入作者行。
     * @param task authentic original reconciling context / 真实原核对上下文
     * @param key stable owner-local key / 稳定的所属键
     * @return original recovery row or absence / 原恢复行或不存在
     * @throws ApiException if the caller, scope or stored identity differs / 调用方、作用域或存储身份不符时 */
    Optional<TaskCheckpoint> findRecoveryCheckpoint(TaskContext task, String key) throws ApiException;

    /**
     * Registers manual reconciliation for retained tasks of the given type. / 为指定类型保留任务登记人工核对逻辑。
     * @param taskType owner-local type of recoverable task / 所有者内部可恢复任务的类型
     * @param recovery callback for explicit reconciliation of retained tasks / 显式核对保留任务的回调
     * @return the owned result described by this capability / 此能力约定的所属结果
     * @throws ApiException if validation, execution or actual cleanup fails / 校验、执行或实际清理失败时
     */
    RecoveryRegistration registerRecovery(String taskType, TaskRecovery recovery) throws ApiException;
}
