package gold.debug.windowstolinux.appapi.linux.session;

import gold.debug.windowstolinux.appapi.linux.command.CommandRequest;
import gold.debug.windowstolinux.appapi.linux.command.CommandResult;
import gold.debug.windowstolinux.appapi.linux.transfer.TransferConflictChoice;
import gold.debug.windowstolinux.appapi.linux.transfer.TransferRequest;
import gold.debug.windowstolinux.appapi.linux.transfer.TransferResult;
import gold.debug.windowstolinux.appapi.task.action.ApprovalGrant;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;

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

    /** Uploads with an explicit conflict choice covered by the exact approval. / 按精确审批覆盖的显式冲突选择上传。
     * @param request bounded request / 有界请求
     * @param grant original single-use approval / 原单次审批
     * @param choice explicit conflict decision / 明确冲突决定
     * @return actual transfer observation / 实际传输观察
     * @throws ApiException if approval, input or transfer fails / 审批、输入或传输失败时
     */
    OperationResult<TransferResult> upload(TransferRequest request, ApprovalGrant grant, TransferConflictChoice choice) throws ApiException;

    /**
     * Receives bounded content under the reviewed task authorization. / 在已审核任务授权下接收有界内容。
     * @param request immutable capability request / 不可变的能力请求
     * @param grant opaque approval whose binding the host must verify / 宿主必须核实绑定关系的不透明审批授权
     * @return the owned result described by this capability / 此能力约定的所属结果
     * @throws ApiException if validation, execution or actual cleanup fails / 校验、执行或实际清理失败时
     */
    OperationResult<TransferResult> download(TransferRequest request, ApprovalGrant grant) throws ApiException;

    /** Downloads with an explicit conflict choice covered by the exact approval. / 按精确审批覆盖的显式冲突选择下载。
     * @param request bounded request / 有界请求
     * @param grant original single-use approval / 原单次审批
     * @param choice explicit conflict decision / 明确冲突决定
     * @return actual transfer observation / 实际传输观察
     * @throws ApiException if approval, input or transfer fails / 审批、输入或传输失败时
     */
    OperationResult<TransferResult> download(TransferRequest request, ApprovalGrant grant, TransferConflictChoice choice) throws ApiException;

    /** Reconciles or resumes one original transfer after source and staging verification. / 核验源及暂存内容后核对或续传一次原传输。
     * @param transferId original checkpoint owned by this task / 此任务所属的原检查点
     * @param grant exact single-use recovery approval / 精确单次恢复审批
     * @return verified publication or retained uncertainty / 经核验发布或保留的不确定性
     * @throws ApiException if authorization or original ownership fails / 授权或原归属失败时 */
    OperationResult<TransferResult> resumeTransfer(java.util.UUID transferId, ApprovalGrant grant) throws ApiException;

    /** Removes only verified staging for an original transfer, never its final target. / 仅移除原传输经核验的暂存文件，绝不移除最终目标。
     * @param transferId original checkpoint owned by this task / 此任务所属的原检查点
     * @param grant exact single-use discard approval / 精确单次丢弃审批
     * @return cleanup outcome without a claim about overall business success / 不对整体业务成功作出断言的清理结果
     * @throws ApiException if authorization or original ownership fails / 授权或原归属失败时 */
    OperationResult<Void> discardTransfer(java.util.UUID transferId, ApprovalGrant grant) throws ApiException;

    /**
     * Releases the owned resource; failure keeps occupancy and permits retry. / 释放所属资源；失败时保留占用并允许重试。
     * @throws ApiException if validation, execution or actual cleanup fails / 校验、执行或实际清理失败时
     */
    void close() throws ApiException;
}
