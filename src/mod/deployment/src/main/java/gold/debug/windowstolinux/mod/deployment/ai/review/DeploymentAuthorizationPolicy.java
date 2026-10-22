package gold.debug.windowstolinux.mod.deployment.ai.review;

import java.util.Optional;
import java.util.Set;

import gold.debug.windowstolinux.appapi.linux.command.CommandRequest;
import gold.debug.windowstolinux.appapi.task.action.*;
import gold.debug.windowstolinux.appapi.task.result.*;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;

/** Obtains exact host authority after independent review. / 独立审核后获取精确宿主权限。 */
public final class DeploymentAuthorizationPolicy {
    /** Binds only the original inputs and capabilities owned by this adapter. / 仅绑定当前适配所属的原输入及能力。 */
    private DeploymentAuthorizationPolicy() {
    }

    /** Rejects an action disallowed by the already captured authorization policy. / 拒绝已捕获授权策略不允许的动作。
     * @param approval captured approval policy / 已捕获审批策略
     * @param rememberAuthorization captured exact-action reuse preference / 已捕获精确动作复用偏好 */
    public static void requireAllowed(String approval, boolean rememberAuthorization) {
        if (!Set.of("MANUAL_REVIEW", "AUTOMATIC", "FULL_CONTROL").contains(approval))
            throw new IllegalArgumentException("Invalid deployment approval policy");
    }

    /** Obtains original host authority after independent action review. / 在独立动作审核后取得原宿主授权。
     * @param host original host bound to this operation / 当前操作绑定的原host值
     * @param intent original intent bound to this operation / 当前操作绑定的原intent值
     * @param command exact independently reviewed remote request / 精确独立审核远端请求
     * @param task original admitted task / 原已准入任务
     * @param approval captured approval policy / 已捕获审批策略
     * @param rememberAuthorization captured exact-action reuse preference / 已捕获精确动作复用偏好
     * @param review original review bound to this operation / 当前操作绑定的原review值
     * @return original exact host approval result / 原精确宿主审批结果
     * @throws ApiException if original input, admission or evidence validation fails / 原输入、准入或依据校验失败时 */
    public static OperationResult<ApprovalGrant> authorize(InteractionService host, ActionIntent intent, Optional<CommandRequest> command,
                                                           TaskContext task, String approval, boolean rememberAuthorization, DeploymentReviewDecision review) throws ApiException {
        requireAllowed(approval, rememberAuthorization);
        return host.approveReviewedAction(intent, command, review.requiresUserConfirmation(), review.reason(), task);
    }
}
