package gold.debug.windowstolinux.mod.deployment.linux.command.contract;

import gold.debug.windowstolinux.appapi.linux.command.CommandRequest;
import gold.debug.windowstolinux.appapi.task.action.ActionIntent;
import gold.debug.windowstolinux.appapi.task.action.ApprovalGrant;
import gold.debug.windowstolinux.appapi.task.action.InteractionService;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;

/** Mode-owned review receives complete immutable requests before the real host grant. / 模式所属审核在真实宿主许可前接收完整不可变请求。 */
public interface DeploymentCommandReview extends InteractionService {
    /** Reviews the actual command and executable input under the original task policy. / 按原任务策略审核实际命令与可执行输入。
     * @param intent original exact intent / 原精确意图
     * @param request complete actual request / 完整实际请求
     * @param task authentic original task / 真实原任务
     * @return actual single-use host grant or classified refusal / 实际单次宿主许可或分类拒绝
     * @throws ApiException if original review cannot complete / 原审核无法完成时 */
    OperationResult<ApprovalGrant> approveCommand(ActionIntent intent, CommandRequest request, TaskContext task)
        throws ApiException;

    /** Observes a validated preparation set matched to the channel's successful receipts, never a permission. */
    default void toolchainsPrepared(gold.debug.windowstolinux.appapi.server.model.ServerId server,
                                    gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentResolvedToolchains tools,
                                    TaskContext task) throws ApiException {
    }

    /** Observes intended dependencies of the source-bound reviewed plan, without granting permission. */
    default void toolchainPreparationPlanned(
        gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.contract.ReviewedDeploymentPlan plan,
        String reviewedPlanSha256, TaskContext task) throws ApiException {
    }
}
