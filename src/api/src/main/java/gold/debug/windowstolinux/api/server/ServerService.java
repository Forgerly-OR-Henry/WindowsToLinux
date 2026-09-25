package gold.debug.windowstolinux.api.server;

import java.util.Optional;
import java.util.UUID;

import gold.debug.windowstolinux.api.task.ApiException;
import gold.debug.windowstolinux.api.task.PageSlice;
import gold.debug.windowstolinux.api.task.TaskContext;

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

    /**
     * Reads the authorized non-secret configuration snapshot. / 读取授权范围内的不含秘密配置快照。
     * @param server selected platform server reference / 选定的平台服务器引用
     * @param task active host-created task context / 宿主创建的活动任务上下文
     * @return the owned result described by this capability / 此能力约定的所属结果
     * @throws ApiException if validation, execution or actual cleanup fails / 校验、执行或实际清理失败时
     */
    ServerInfo get(ServerRef server, TaskContext task) throws ApiException;

    /**
     * Protects the referenced resource before the business transaction commits. / 在业务事务提交前保护引用资源。
     * @param server selected platform server reference / 选定的平台服务器引用
     * @param businessKey stable key of the referencing business record / 引用业务记录的稳定键
     * @param task active host-created task context / 宿主创建的活动任务上下文
     * @return the owned result described by this capability / 此能力约定的所属结果
     * @throws ApiException if validation, execution or actual cleanup fails / 校验、执行或实际清理失败时
     */
    ServerReference reserve(ServerRef server, String businessKey, TaskContext task) throws ApiException;

    /**
     * Records a reconciled business reference result without guessing unknown commits. / 登记已核对的业务引用结果，不猜测未知提交。
     * @param referenceId identity of the existing reference reservation / 已有引用预留的身份
     * @param result observed result used to resolve retained state / 用于处理保留状态的已观察结果
     * @param task active host-created task context / 宿主创建的活动任务上下文
     * @throws ApiException if validation, execution or actual cleanup fails / 校验、执行或实际清理失败时
     */
    void resolve(UUID referenceId, ReferenceResolution result, TaskContext task) throws ApiException;
}
