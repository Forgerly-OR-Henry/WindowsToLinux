package gold.debug.windowstolinux.appapi.server;

import java.util.Optional;
import java.util.UUID;

import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.server.model.ServerInfo;
import gold.debug.windowstolinux.appapi.task.ref.ResourceRefResult;
import gold.debug.windowstolinux.appapi.server.ref.ServerRef;
import gold.debug.windowstolinux.appapi.task.query.PageSlice;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;

/**
 * Task-bound server lookup and persistent reference management. / 绑定任务的服务器查询及持久引用管理。
 */
public interface ServerService {

    /**
     * Reads bounded caller-owned snapshots; cursors are not transaction snapshots. / 读取有界的调用方快照；游标不保证事务快照。
     * @param cursor optional opaque continuation cursor / 可选的不透明后续游标
     * @param limit maximum page size from one to two hundred / 一到二百之间的页容量上限
     * @param task active host-created task context / 宿主创建的活动任务上下文
     * @return the owned result described by this capability / 此能力约定的所属结果
     * @throws ApiException if validation, execution or actual cleanup fails / 校验、执行或实际清理失败时
     */
    PageSlice<ServerInfo> list(Optional<String> cursor, int limit, TaskContext task) throws ApiException;

    /** Reads the first bounded admitted-server page. / 读取已准入服务器的第一页有界数据。
     * @param limit existing bounded page capacity / 现有有界页容量
     * @param task authentic task / 真实任务
     * @return first page under the original server-use rules / 原服务器使用规则下的第一页
     * @throws ApiException if input or access is invalid / 输入或访问无效时
     */
    default PageSlice<ServerInfo> list(int limit, TaskContext task) throws ApiException {
        return list(Optional.empty(), limit, task);
    }

    /** Lists saved non-secret server summaries for selection only; actual server operations require explicit admission of the selected server. / 仅为选择列出已保存的非秘密服务器摘要；实际服务器操作仍须明确准入所选服务器。
     * @param cursor optional discovery-only cursor bound to this task / 绑定当前任务的可选发现专用游标
     * @param limit page size from one to two hundred / 一到二百之间的页容量
     * @param task authentic active task / 真实活动任务
     * @return bounded saved summaries without server-use authority / 不含服务器使用授权的有界摘要
     * @throws ApiException if owner, task or storage is unavailable / 所属者、任务或存储不可用时 */
    PageSlice<ServerInfo> listConfigured(Optional<String> cursor, int limit, TaskContext task) throws ApiException;

    /** Reads the first discovery-only page without server-use authority. / 读取仅供发现的第一页，不授予服务器使用权。
     * @param limit existing bounded page capacity / 现有有界页容量
     * @param task authentic task / 真实任务
     * @return first discovery page / 第一页发现数据
     * @throws ApiException if input or access is invalid / 输入或访问无效时
     */
    default PageSlice<ServerInfo> listConfigured(int limit, TaskContext task) throws ApiException {
        return listConfigured(Optional.empty(), limit, task);
    }

    /**
     * Reads the authorized non-secret configuration snapshot. / 读取授权范围内的不含秘密配置快照。
     * @param server selected platform server reference / 选定的平台服务器引用
     * @param task active host-created task context / 宿主创建的活动任务上下文
     * @return the owned result described by this capability / 此能力约定的所属结果
     * @throws ApiException if validation, execution or actual cleanup fails / 校验、执行或实际清理失败时
     */
    ServerInfo get(ServerId server, TaskContext task) throws ApiException;

    /**
     * Protects the referenced resource before the business transaction commits. / 在业务事务提交前保护引用资源。
     * @param server selected platform server reference / 选定的平台服务器引用
     * @param businessKey stable key of the referencing business record / 引用业务记录的稳定键
     * @param task active host-created task context / 宿主创建的活动任务上下文
     * @return the owned result described by this capability / 此能力约定的所属结果
     * @throws ApiException if validation, execution or actual cleanup fails / 校验、执行或实际清理失败时
     */
    ServerRef reserve(ServerId server, String businessKey, TaskContext task) throws ApiException;

    /**
     * Looks up an existing association without reserving or changing it, including original-task recovery. / 只读查询已有关联，支持原任务恢复，不预留或改变引用。
     * @param server original server identity with its admitted scope / 原服务器身份及其已准入范围
     * @param businessKey exact original association key / 精确原关联键
     * @param task authentic executing or reconciling task / 真实执行或核对任务
     * @return current owned reference, or empty when absent; not proof of business rollback / 当前所属引用，不存在时为空；不证明业务已回滚
     * @throws ApiException if input, authorization or reading fails / 输入、授权或读取失败时
     */
    Optional<ServerRef> findReference(ServerId server, String businessKey, TaskContext task) throws ApiException;

    /**
     * Records a reconciled business reference result without guessing unknown commits. / 登记已核对的业务引用结果，不猜测未知提交。
     * @param referenceId identity of the existing reference reservation / 已有引用预留的身份
     * @param result observed result used to resolve retained state / 用于处理保留状态的已观察结果
     * @param task active host-created task context / 宿主创建的活动任务上下文
     * @throws ApiException if validation, execution or actual cleanup fails / 校验、执行或实际清理失败时
     */
    void resolve(UUID referenceId, ResourceRefResult result, TaskContext task) throws ApiException;
}
