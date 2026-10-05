package gold.debug.windowstolinux.mod.deployment.ai;

import java.util.Set;

import gold.debug.windowstolinux.appapi.task.action.ActionIntent;
import gold.debug.windowstolinux.appapi.task.action.ApprovalGrant;
import gold.debug.windowstolinux.appapi.task.action.InteractionService;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;

/** Separates independently reviewed risk from captured human authorization repetition. / 将独立审核风险与已抓取人工授权重复策略分开。 */
public final class DeploymentAuthorizationPolicy {
    /** Prevents construction. / 禁止构造。 */
    private DeploymentAuthorizationPolicy() {
    }

    /** Rejects unsupported combinations before admission or preference writes. / 准入或偏好写入前拒绝不支持的组合。
     * @param approval selected approval strategy / 所选审批策略
     * @param authorization selected authorization repetition / 所选授权重复规则 */
    public static void requireAllowed(String approval, String authorization) {
        if (!Set.of("MANUAL_REVIEW", "AUTOMATIC", "FULL_CONTROL").contains(approval)
            || !Set.of("ONCE", "GENERAL", "FULL").contains(authorization)
            || approval.equals("MANUAL_REVIEW") && authorization.equals("FULL")
            || approval.equals("FULL_CONTROL") && authorization.equals("ONCE"))
            throw new IllegalArgumentException("Invalid deployment approval and authorization combination");
    }

    /** Obtains the real host grant only after independent approval, preserving exact effect fields. / 仅在独立批准后取得真实宿主许可，保留精确副作用字段。
     * @param host original host capability / 原宿主能力
     * @param intent actual classified effect / 实际已分类副作用
     * @param task authentic original task / 真实原任务
     * @param approval frozen approval strategy / 冻结审批策略
     * @param authorization frozen authorization strategy / 冻结授权策略
     * @param high independent reviewer classified HIGH / 独立审核者判为高危
     * @return actual single-use grant or original refusal / 实际单次许可或原拒绝
     * @throws ApiException if permission is unavailable or a lower-risk replacement is required / 权限不可用或要求低风险替代时 */
    public static OperationResult<ApprovalGrant> authorize(InteractionService host, ActionIntent intent, TaskContext task,
                                                           String approval, String authorization, boolean high) throws ApiException {
        requireAllowed(approval, authorization);
        if (approval.equals("AUTOMATIC") && high && authorization.equals("FULL"))
            throw new DeploymentLowRiskRequiredException(task, "HIGH requires a different lower-risk action under automatic full authorization");
        var result = approval.equals("MANUAL_REVIEW") || approval.equals("AUTOMATIC") && high
            ? host.approveRequired(intent, task) : host.approve(intent, task);
        if (approval.equals("AUTOMATIC") && high && result.outcome() == TaskResult.CANCELLED && !task.cancelToken().requested())
            throw new DeploymentLowRiskRequiredException(task, "The HIGH action was not authorized; propose a different lower-risk action");
        return result;
    }
}
