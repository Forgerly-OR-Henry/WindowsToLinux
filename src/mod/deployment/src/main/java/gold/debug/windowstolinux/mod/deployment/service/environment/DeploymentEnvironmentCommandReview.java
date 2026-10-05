package gold.debug.windowstolinux.mod.deployment.service.environment;

import gold.debug.windowstolinux.appapi.linux.command.CommandRequest;
import gold.debug.windowstolinux.appapi.linux.session.RemoteTarget;
import gold.debug.windowstolinux.appapi.task.action.ApprovalGrant;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;

/** Original mode's command review boundary, covering complete stdin and fixed target. / 原模式命令审核边界，覆盖完整 stdin 及固定目标。 */
@FunctionalInterface
public interface DeploymentEnvironmentCommandReview {
    /** Reviews one complete exact command under the original mode and task. / 在原模式及任务下审核一条完整精确命令。
     * @param target original fixed target / 原固定目标
     * @param revision original resource and observed-facts revision / 原资源及观测事实修订
     * @param request complete actual script and stdin / 完整实际脚本及 stdin
     * @param task authentic original task / 真实原任务
     * @return actual host grant after the mode's required reviews / 原模式所需审核后的实际宿主授权
     * @throws ApiException if approval is unavailable / 审核不可用时 */
    OperationResult<ApprovalGrant> approve(RemoteTarget target, String revision, CommandRequest request,
                                           TaskContext task) throws ApiException;
}
