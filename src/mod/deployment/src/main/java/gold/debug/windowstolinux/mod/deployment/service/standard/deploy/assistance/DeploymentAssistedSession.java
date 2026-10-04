package gold.debug.windowstolinux.mod.deployment.service.standard.deploy.assistance;

import java.util.UUID;

import gold.debug.windowstolinux.appapi.linux.*;
import gold.debug.windowstolinux.appapi.linux.session.RemoteFacts;
import gold.debug.windowstolinux.appapi.linux.session.RemoteSession;
import gold.debug.windowstolinux.appapi.linux.command.CommandRequest;
import gold.debug.windowstolinux.appapi.linux.command.CommandResult;
import gold.debug.windowstolinux.appapi.linux.transfer.TransferConflictChoice;
import gold.debug.windowstolinux.appapi.linux.transfer.TransferRequest;
import gold.debug.windowstolinux.appapi.linux.transfer.TransferResult;
import gold.debug.windowstolinux.appapi.task.action.ApprovalGrant;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;
import gold.debug.windowstolinux.mod.deployment.linux.DeploymentSendGuard;

/** Rechecks the assisted binding immediately before every actual SSH dispatch. / 每次真实 SSH 发送前立即复核辅助绑定。
 * @param original task-owned session / 任务所属会话
 * @param guard frozen model and policy binding / 冻结模型与策略绑定 */
record DeploymentAssistedSession(RemoteSession original, DeploymentSendGuard guard) implements RemoteSession {
    /** Returns authenticated original facts. / 返回原认证事实。
     * @return actual facts / 实际事实 */
    @Override
    public RemoteFacts facts() {
        return original.facts();
    }

    /** Checks then sends one exact command. / 检查后发送一项精确命令。
     * @param request original command / 原命令
     * @param grant actual host grant / 实际宿主授权
     * @return actual feedback / 实际反馈
     * @throws ApiException on drift or actual failure / 漂移或真实失败时 */
    @Override
    public OperationResult<CommandResult> execute(CommandRequest request, ApprovalGrant grant) throws ApiException {
        guard.verify();
        return original.execute(request, grant);
    }

    /** Checks then uploads the original bytes. / 检查后上传原字节。
     * @param request exact transfer / 精确传输
     * @param grant actual grant / 实际授权
     * @return actual result / 实际结果
     * @throws ApiException on drift or failure / 漂移或失败时 */
    @Override
    public OperationResult<TransferResult> upload(TransferRequest request, ApprovalGrant grant) throws ApiException {
        guard.verify();
        return original.upload(request, grant);
    }

    /** Preserves the guard and explicit upload conflict policy. / 保留发送门禁及显式上传冲突策略。
     * @param request original request / 原请求
     * @param grant actual approval / 实际审批
     * @param choice exact choice / 精确选择
     * @return actual result / 实际结果
     * @throws ApiException on drift or failure / 漂移或失败时
     */
    @Override
    public OperationResult<TransferResult> upload(TransferRequest request, ApprovalGrant grant, TransferConflictChoice choice) throws ApiException {
        guard.verify();
        return original.upload(request, grant, choice);
    }

    /** Checks then downloads original content. / 检查后下载原内容。
     * @param request exact transfer / 精确传输
     * @param grant actual grant / 实际授权
     * @return actual result / 实际结果
     * @throws ApiException on drift or failure / 漂移或失败时 */
    @Override
    public OperationResult<TransferResult> download(TransferRequest request, ApprovalGrant grant) throws ApiException {
        guard.verify();
        return original.download(request, grant);
    }

    /** Preserves the guard and explicit download conflict policy. / 保留发送门禁及显式下载冲突策略。
     * @param request original request / 原请求
     * @param grant actual approval / 实际审批
     * @param choice exact choice / 精确选择
     * @return actual result / 实际结果
     * @throws ApiException on drift or failure / 漂移或失败时
     */
    @Override
    public OperationResult<TransferResult> download(TransferRequest request, ApprovalGrant grant, TransferConflictChoice choice) throws ApiException {
        guard.verify();
        return original.download(request, grant, choice);
    }

    /** Checks then resumes only the original transfer. / 检查后仅续传原传输。
     * @param id original transfer / 原传输
     * @param grant recovery grant / 恢复授权
     * @return actual result / 实际结果
     * @throws ApiException on drift or failure / 漂移或失败时 */
    @Override
    public OperationResult<TransferResult> resumeTransfer(UUID id, ApprovalGrant grant) throws ApiException {
        guard.verify();
        return original.resumeTransfer(id, grant);
    }

    /** Checks then discards only original staging. / 检查后仅丢弃原暂存。
     * @param id original transfer / 原传输
     * @param grant cleanup grant / 清理授权
     * @return actual cleanup / 实际清理
     * @throws ApiException on drift or failure / 漂移或失败时 */
    @Override
    public OperationResult<Void> discardTransfer(UUID id, ApprovalGrant grant) throws ApiException {
        guard.verify();
        return original.discardTransfer(id, grant);
    }

    /** Closes the real session even after the binding changes. / 即使绑定变化仍关闭真实会话。
     * @throws ApiException if actual release fails / 实际释放失败时 */
    @Override
    public void close() throws ApiException {
        original.close();
    }
}
