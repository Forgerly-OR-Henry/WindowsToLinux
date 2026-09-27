package gold.debug.windowstolinux.appapi.secret;

import java.util.Optional;
import java.util.UUID;

import gold.debug.windowstolinux.appapi.server.ReferenceResolution;
import gold.debug.windowstolinux.appapi.task.ApiException;
import gold.debug.windowstolinux.appapi.task.TaskContext;

/**
 * Owned secrets exposed only through authorized temporary callbacks. / 仅经授权临时回调提供所属秘密的服务。
 */
public interface SecretService {

    /**
     * Creates the owned value within the current lifecycle boundary. / 在当前生命周期边界内创建所属值。
     * @param purpose declared purpose used for routing or authorization / 路由或授权使用的声明用途
     * @param value optional observed result payload / 可选的已观察结果载荷
     * @param task active host-created task context / 宿主创建的活动任务上下文
     * @return the owned result described by this capability / 此能力约定的所属结果
     * @throws ApiException if validation, execution or actual cleanup fails / 校验、执行或实际清理失败时
     */
    SecretRef create(String purpose, byte[] value, TaskContext task) throws ApiException;

    /**
     * Protects the referenced resource before the business transaction commits. / 在业务事务提交前保护引用资源。
     * @param secret opaque authorized secret reference or temporary secret value / 不透明的已授权秘密引用或临时秘密值
     * @param businessKey stable key of the referencing business record / 引用业务记录的稳定键
     * @param task active host-created task context / 宿主创建的活动任务上下文
     * @return the owned result described by this capability / 此能力约定的所属结果
     * @throws ApiException if validation, execution or actual cleanup fails / 校验、执行或实际清理失败时
     */
    SecretReference reserve(SecretRef secret, String businessKey, TaskContext task) throws ApiException;

    /**
     * Looks up an existing association without reserving or changing it, including original-task recovery. / 只读查询已有关联，支持原任务恢复，不预留或改变引用。
     * @param secret original secret identity with its admitted scope / 原秘密身份及其已准入范围
     * @param businessKey exact original association key / 精确原关联键
     * @param task authentic executing or reconciling task / 真实执行或核对任务
     * @return current owned reference, or empty when absent; not proof of business rollback / 当前所属引用，不存在时为空；不证明业务已回滚
     * @throws ApiException if input, authorization or reading fails / 输入、授权或读取失败时
     */
    Optional<SecretReference> findReference(SecretRef secret, String businessKey, TaskContext task) throws ApiException;

    /**
     * Records a reconciled business reference result without guessing unknown commits. / 登记已核对的业务引用结果，不猜测未知提交。
     * @param referenceId identity of the existing reference reservation / 已有引用预留的身份
     * @param result observed result used to resolve retained state / 用于处理保留状态的已观察结果
     * @param task active host-created task context / 宿主创建的活动任务上下文
     * @throws ApiException if validation, execution or actual cleanup fails / 校验、执行或实际清理失败时
     */
    void resolve(UUID referenceId, ReferenceResolution result, TaskContext task) throws ApiException;

    /**
     * Consumes temporarily authorized secret bytes without retaining or logging them. / 使用临时授权的秘密字节，不保留或记录秘密。
     * @param <T> public payload or service contract type / 公开载荷或服务契约类型
     * @param secret opaque authorized secret reference or temporary secret value / 不透明的已授权秘密引用或临时秘密值
     * @param purpose declared purpose used for routing or authorization / 路由或授权使用的声明用途
     * @param task active host-created task context / 宿主创建的活动任务上下文
     * @param consumer authorized callback that must not retain the secret / 不得保留秘密的已授权回调
     * @return the owned result described by this capability / 此能力约定的所属结果
     * @throws ApiException if validation, execution or actual cleanup fails / 校验、执行或实际清理失败时
     */
    <T> T use(SecretRef secret, String purpose, TaskContext task, SecretConsumer<T> consumer) throws ApiException;
}
