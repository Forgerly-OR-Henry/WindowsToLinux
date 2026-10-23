package gold.debug.windowstolinux.mod.deployment.linux.command.dispatch;

import gold.debug.windowstolinux.appapi.linux.command.CommandRequest;
import gold.debug.windowstolinux.appapi.task.action.ActionIntent;
import gold.debug.windowstolinux.appapi.task.action.ApprovalGrant;
import gold.debug.windowstolinux.appapi.task.action.InteractionService;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.mod.deployment.linux.command.contract.DeploymentCommandReview;

/** Routes fixed commands through their owning mode without replacing host authority. / 按所属模式路由固定命令，不替换宿主权限。 */
public final class DeploymentCommandReviewRouter {
    /** Prevents construction. / 禁止构造。 */
    private DeploymentCommandReviewRouter() {
    }

    /** Gives AI workflows full command bytes while ordinary static operations retain their existing host review. / 向AI流程提供完整命令字节，普通静态操作保留既有宿主审核。
     * @param interaction original mode interaction / 原模式交互
     * @param intent original exact intent / 原精确意图
     * @param request complete immutable command / 完整不可变命令
     * @param task authentic original task / 真实原任务
     * @return actual host grant or original refusal / 实际宿主许可或原拒绝
     * @throws ApiException if original review rejects or is unavailable / 原审核拒绝或不可用时 */
    public static OperationResult<ApprovalGrant> approve(InteractionService interaction, ActionIntent intent,
                                                         CommandRequest request, TaskContext task) throws ApiException {
        return interaction instanceof DeploymentCommandReview review ? review.approveCommand(intent, request, task)
            : interaction.approve(intent, task);
    }
}
