package gold.debug.windowstolinux.appapi.linux;

import gold.debug.windowstolinux.appapi.task.ApiException;
import gold.debug.windowstolinux.appapi.task.ApprovalGrant;
import gold.debug.windowstolinux.appapi.task.OperationResult;

/**
 * Owned SSH session requiring reviewed grants for effects. / 产生副作用时需要已审核授权的受管 SSH 会话。
 */
public interface RemoteSession extends AutoCloseable {

    /**
     * Returns observed authenticated host facts without guessing unavailable values. / 返回已观察的认证主机事实，不猜测不可用值。
     * @return the owned result described by this capability / 此能力约定的所属结果
     */
    RemoteFacts facts();

    /**
     * Executes only the exact action covered by the still-valid approval grant. / 仅执行仍有效审批授权覆盖的精确操作。
     * @param request immutable capability request / 不可变的能力请求
     * @param grant opaque approval whose binding the host must verify / 宿主必须核实绑定关系的不透明审批授权
     * @return the owned result described by this capability / 此能力约定的所属结果
     * @throws ApiException if validation, execution or actual cleanup fails / 校验、执行或实际清理失败时
     */
    OperationResult<CommandResult> execute(CommandRequest request, ApprovalGrant grant) throws ApiException;

    /**
     * Transfers bounded content under the reviewed task authorization. / 在已审核任务授权下传输有界内容。
     * @param request immutable capability request / 不可变的能力请求
     * @param grant opaque approval whose binding the host must verify / 宿主必须核实绑定关系的不透明审批授权
     * @return the owned result described by this capability / 此能力约定的所属结果
     * @throws ApiException if validation, execution or actual cleanup fails / 校验、执行或实际清理失败时
     */
    OperationResult<TransferResult> upload(TransferRequest request, ApprovalGrant grant) throws ApiException;

    /**
     * Receives bounded content under the reviewed task authorization. / 在已审核任务授权下接收有界内容。
     * @param request immutable capability request / 不可变的能力请求
     * @param grant opaque approval whose binding the host must verify / 宿主必须核实绑定关系的不透明审批授权
     * @return the owned result described by this capability / 此能力约定的所属结果
     * @throws ApiException if validation, execution or actual cleanup fails / 校验、执行或实际清理失败时
     */
    OperationResult<TransferResult> download(TransferRequest request, ApprovalGrant grant) throws ApiException;

    /**
     * Releases the owned resource; failure keeps occupancy and permits retry. / 释放所属资源；失败时保留占用并允许重试。
     * @throws ApiException if validation, execution or actual cleanup fails / 校验、执行或实际清理失败时
     */
    void close() throws ApiException;
}
