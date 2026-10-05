package gold.debug.windowstolinux.mod.deployment.ai;

import java.util.List;
import java.util.Set;

import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.action.ActionIntent;
import gold.debug.windowstolinux.appapi.task.action.ActionSemantics;
import gold.debug.windowstolinux.appapi.task.action.ActionTarget;
import gold.debug.windowstolinux.appapi.task.action.AuthorizationMode;
import gold.debug.windowstolinux.appapi.task.action.WorkflowGrant;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;

/** Opens only a real host-owned original workflow scope under its captured deployment policies. / 仅按已抓取部署策略开启真实宿主所属原流程作用域。 */
public final class DeploymentWorkflowAuthorization {
    /** Prevents construction. / 禁止构造。 */
    private DeploymentWorkflowAuthorization() {
    }

    /** Establishes manual question repetition or one complete automatic workflow handoff. / 建立人工问题重复规则或一次完整自动流程交接。
     * @param context actual original owner capabilities / 实际原所有者能力
     * @param task authentic admitted root / 真实已准入根
     * @param server exact original server / 精确原服务器
     * @param revision frozen original model route / 冻结原模型路由
     * @param approval selected approval strategy / 所选审批策略
     * @param authorization selected authorization repetition / 所选授权重复规则
     * @param frozenRequest nonsecret complete workflow choices / 不含秘密的完整流程选择
     * @return opaque real close-only host grant / 不透明真实仅关闭宿主许可
     * @throws ApiException if original scope is unavailable / 原作用域不可用时
     * @throws DeploymentAiJson.CapabilityOutcomeException if original handoff is refused / 原交接被拒绝时 */
    public static WorkflowGrant open(ModContext context, TaskContext task, ServerId server, String revision,
                                     String approval, String authorization, String frozenRequest) throws ApiException, DeploymentAiJson.CapabilityOutcomeException {
        return scope(context, task, server, revision, approval, authorization, frozenRequest, false);
    }

    /** Requests a fresh scope only during explicit original recovery, without restoring old permission. / 仅在明确原恢复请求新作用域，不恢复旧权限。
     * @param context actual original owner / 实际原所有者
     * @param task authentic original recovery / 真实原恢复
     * @param server complete original target / 完整原目标
     * @param revision original frozen model route / 原冻结模型路由
     * @param approval original persisted approval strategy / 原持久审批策略
     * @param authorization original persisted authorization repetition / 原持久授权重复规则
     * @param frozenRequest complete original nonsecret recovery facts / 完整原非秘密恢复事实
     * @return fresh opaque close-only grant / 新不透明仅关闭许可
     * @throws ApiException if recovery admission or old cleanup is unconfirmed / 恢复准入或旧清理未确认时
     * @throws DeploymentAiJson.CapabilityOutcomeException if the fresh handoff is refused / 新交权被拒绝时 */
    public static WorkflowGrant recover(ModContext context, TaskContext task, ServerId server, String revision,
                                        String approval, String authorization, String frozenRequest) throws ApiException, DeploymentAiJson.CapabilityOutcomeException {
        return scope(context, task, server, revision, approval, authorization, frozenRequest, true);
    }

    /** Shares exact original intent construction while letting only the host admit ordinary or recovery work. / 共用精确原意图构造，仅由宿主接纳普通或恢复工作。
     * @param context actual owner / 实际所有者
     * @param task authentic original task / 真实原任务
     * @param server original server / 原服务器
     * @param revision frozen model route / 冻结模型路由
     * @param approval approval strategy / 审批策略
     * @param authorization repetition strategy / 重复策略
     * @param frozenRequest nonsecret exact choices / 不含秘密的精确选择
     * @param recovery explicitly requested original recovery / 明确请求原恢复
     * @return actual opaque host grant / 实际不透明宿主许可
     * @throws ApiException if actual host admission rejects / 实际宿主准入拒绝时
     * @throws DeploymentAiJson.CapabilityOutcomeException if handoff is refused / 交权被拒绝时 */
    private static WorkflowGrant scope(ModContext context, TaskContext task, ServerId server, String revision,
                                       String approval, String authorization, String frozenRequest, boolean recovery) throws ApiException, DeploymentAiJson.CapabilityOutcomeException {
        DeploymentAuthorizationPolicy.requireAllowed(approval, authorization);
        var intent = new ActionIntent("deployment.workflow.authority", List.of(server.id()), task.id().toString(), revision,
            "task=" + task.id() + "\napproval=" + approval + "\nauthorization=" + authorization + "\nrequest=" + frozenRequest,
            approval.equals("MANUAL_REVIEW") ? "Apply authorization repetition to this original deployment's reviewed actions"
                : "Delegate this complete original deployment workflow to the Agent")
            .withSemantics(new ActionSemantics("deployment.workflow.v1", Set.of(new ActionTarget(server.id(), "deployment-task", task.id().toString()))));
        var mode = AuthorizationMode.valueOf(authorization);
        var result = recovery ? context.interaction().recoverWorkflow(intent, mode, approval.equals("MANUAL_REVIEW"), task)
            : approval.equals("MANUAL_REVIEW") ? context.interaction().reviewedWorkflow(intent, mode, task)
            : context.interaction().delegateWorkflow(intent, mode, task);
        if (result.outcome() != TaskResult.SUCCEEDED)
            throw new DeploymentAiJson.CapabilityOutcomeException(result.outcome(), result.problem());
        return result.value().orElseThrow(() -> new IllegalStateException("Host workflow grant is absent"));
    }
}
