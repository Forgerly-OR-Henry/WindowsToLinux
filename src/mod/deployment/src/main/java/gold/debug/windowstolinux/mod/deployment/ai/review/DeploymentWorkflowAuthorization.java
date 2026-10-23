package gold.debug.windowstolinux.mod.deployment.ai.review;

import java.util.List;
import java.util.Set;

import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.action.ActionIntent;
import gold.debug.windowstolinux.appapi.task.action.ActionSemantics;
import gold.debug.windowstolinux.appapi.task.action.ActionTarget;
import gold.debug.windowstolinux.appapi.task.action.CommandReviewMode;
import gold.debug.windowstolinux.appapi.task.action.WorkflowGrant;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.mod.deployment.ai.DeploymentAiJson;

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
     * @param rememberAuthorization selected rememberAuthorization repetition / 所选授权重复规则
     * @param frozenRequest nonsecret complete workflow choices / 不含秘密的完整流程选择
     * @return opaque real close-only host grant / 不透明真实仅关闭宿主许可
     * @throws ApiException if original scope is unavailable / 原作用域不可用时
     * @throws DeploymentAiJson.CapabilityOutcomeException if original handoff is refused / 原交接被拒绝时 */
    public static WorkflowGrant open(ModContext context, TaskContext task, ServerId server, String revision,
                                     String approval, boolean rememberAuthorization, String frozenRequest) throws ApiException, DeploymentAiJson.CapabilityOutcomeException {
        return scope(context, task, server, revision, approval, rememberAuthorization, frozenRequest, false, 0);
    }

    /** Carries only confirmed goal policy; every child still obtains a fresh real host handoff. */
    public static WorkflowGrant open(ModContext context, TaskContext task, ServerId server, String revision,
                                     String approval, boolean rememberAuthorization, String frozenRequest, long policyRevision)
        throws ApiException, DeploymentAiJson.CapabilityOutcomeException {
        return scope(context, task, server, revision, approval, rememberAuthorization, frozenRequest, false, policyRevision);
    }

    /** Requests a fresh scope only during explicit original recovery, without restoring old permission. / 仅在明确原恢复请求新作用域，不恢复旧权限。
     * @param context actual original owner / 实际原所有者
     * @param task authentic original recovery / 真实原恢复
     * @param server complete original target / 完整原目标
     * @param revision original frozen model route / 原冻结模型路由
     * @param approval original persisted approval strategy / 原持久审批策略
     * @param rememberAuthorization original persisted rememberAuthorization repetition / 原持久授权重复规则
     * @param frozenRequest complete original nonsecret recovery facts / 完整原非秘密恢复事实
     * @return fresh opaque close-only grant / 新不透明仅关闭许可
     * @throws ApiException if recovery admission or old cleanup is unconfirmed / 恢复准入或旧清理未确认时
     * @throws DeploymentAiJson.CapabilityOutcomeException if the fresh handoff is refused / 新交权被拒绝时 */
    public static WorkflowGrant recover(ModContext context, TaskContext task, ServerId server, String revision,
                                        String approval, boolean rememberAuthorization, String frozenRequest) throws ApiException, DeploymentAiJson.CapabilityOutcomeException {
        return scope(context, task, server, revision, approval, rememberAuthorization, frozenRequest, true, 0);
    }

    /** Shares exact original intent construction while letting only the host admit ordinary or recovery work. / 共用精确原意图构造，仅由宿主接纳普通或恢复工作。
     * @param context actual owner / 实际所有者
     * @param task authentic original task / 真实原任务
     * @param server original server / 原服务器
     * @param revision frozen model route / 冻结模型路由
     * @param approval approval strategy / 审批策略
     * @param rememberAuthorization repetition strategy / 重复策略
     * @param frozenRequest nonsecret exact choices / 不含秘密的精确选择
     * @param recovery explicitly requested original recovery / 明确请求原恢复
     * @return actual opaque host grant / 实际不透明宿主许可
     * @throws ApiException if actual host admission rejects / 实际宿主准入拒绝时
     * @throws DeploymentAiJson.CapabilityOutcomeException if handoff is refused / 交权被拒绝时 */
    private static WorkflowGrant scope(ModContext context, TaskContext task, ServerId server, String revision,
                                       String approval, boolean rememberAuthorization, String frozenRequest, boolean recovery, long policyRevision) throws ApiException, DeploymentAiJson.CapabilityOutcomeException {
        DeploymentAuthorizationPolicy.requireAllowed(approval, rememberAuthorization);
        var confirmed = DeploymentApprovalPolicies.original(context, task, approval, policyRevision);
        DeploymentApprovalPolicies.requireRemember(context, task, rememberAuthorization);
        approval = confirmed.mode();
        var intent = new ActionIntent("deployment.workflow.authority", List.of(server.id()), task.id().toString(), revision,
            "task=" + task.id() + "\napproval=" + approval + "\nrememberAuthorization=" + rememberAuthorization + "\nrequest=" + frozenRequest,
            approval.equals("FULL_CONTROL") ? "Allow the independently reviewed workflow to run without further command questions, including unresolved choices about existing resources; cancel to keep control of those choices"
                : "Review each action under the selected human decision policy")
            .withSemantics(new ActionSemantics("deployment.workflow.v1", Set.of(new ActionTarget(server.id(), "deployment-task", task.id().toString()))));
        var result = context.interaction().openReviewedWorkflow(intent, CommandReviewMode.valueOf(approval),
            rememberAuthorization, recovery, task);
        if (result.outcome() != TaskResult.SUCCEEDED)
            throw new DeploymentAiJson.CapabilityOutcomeException(result.outcome(), result.problem());
        var original = result.value().orElseThrow(() -> new IllegalStateException("Host workflow grant is absent"));
        try {
            var policy = DeploymentApprovalPolicies.open(context, task, original, confirmed, rememberAuthorization, recovery);
            return new ReviewedWorkflow(original, task, intent, rememberAuthorization, frozenRequest, recovery, policy);
        } catch (ApiException | RuntimeException failed) {
            original.close();
            throw failed;
        }
    }

    /** Exposes only the original active host handoff's nonsecret scope, never a command grant. */
    public static java.util.Map<String, Object> reviewFacts(WorkflowGrant permission, TaskContext task, ActionIntent command) {
        if (!(permission instanceof ReviewedWorkflow reviewed))
            throw new IllegalArgumentException("Workflow evidence requires its actual host-issued handoff");
        return reviewed.facts(task, command);
    }

    /** Validates a source observation against the original active handoff before any command exists. */
    public static java.util.Map<String, Object> reviewFacts(WorkflowGrant permission, TaskContext task, ServerId server) {
        if (!(permission instanceof ReviewedWorkflow reviewed))
            throw new IllegalArgumentException("Workflow evidence requires its actual host-issued handoff");
        return reviewed.facts(task, List.of(server.id()));
    }

    private static final class ReviewedWorkflow implements WorkflowGrant {
        private final WorkflowGrant original;

        private final TaskContext task;

        private final ActionIntent intent;

        private final boolean remember;

        private final String request;

        private final boolean recovery;

        private final DeploymentApprovalPolicies.Controller policyState;

        private boolean active = true;

        private boolean closed;

        private ReviewedWorkflow(WorkflowGrant original, TaskContext task, ActionIntent intent,
                                 boolean remember, String request, boolean recovery, DeploymentApprovalPolicies.Controller policyState) {
            this.original = original;
            this.task = task;
            this.intent = intent;
            this.remember = remember;
            this.request = request;
            this.recovery = recovery;
            this.policyState = policyState;
        }

        private synchronized java.util.Map<String, Object> facts(TaskContext supplied, ActionIntent command) {
            if (command.semantics().orElseThrow().targets().stream().anyMatch(target -> !intent.serverIds().contains(target.serverId())))
                throw new IllegalArgumentException("Workflow evidence is closed or belongs to another task/server");
            return facts(supplied, command.serverIds());
        }

        private synchronized java.util.Map<String, Object> facts(TaskContext supplied, List<String> servers) {
            if (!active || supplied != task || !servers.equals(intent.serverIds()))
                throw new IllegalArgumentException("Workflow evidence is closed or belongs to another task/server");
            return java.util.Map.ofEntries(java.util.Map.entry("task", task.id().toString()), java.util.Map.entry("serverTargets", intent.serverIds()),
                java.util.Map.entry("modelRevision", intent.revision()), java.util.Map.entry("approvalPolicy", policyState.confirmed().mode()),
                java.util.Map.entry("approvalPolicyRevision", policyState.confirmed().revision()), java.util.Map.entry("rememberAuthorization", remember),
                java.util.Map.entry("frozenWorkflowRequest", request), java.util.Map.entry("recovery", recovery), java.util.Map.entry("hostHandoffOpened", true),
                java.util.Map.entry("meaning", "Original active host workflow handoff only, not per-command permission, ownership or a safety verdict. modelRevision identifies the frozen AI configuration, not a deployment plan, source or candidate; it must not be compared to businessRevision or resource digests as the same object."));
        }

        @Override
        public synchronized void close() throws ApiException {
            active = false;
            if (!closed) {
                try {
                    original.close();
                } catch (ApiException | RuntimeException unconfirmed) {
                    policyState.hostCloseFailed();
                    throw unconfirmed;
                }
                try {
                    policyState.close();
                } catch (ApiException | RuntimeException unconfirmed) {
                    policyState.hostCloseFailed();
                    throw unconfirmed;
                }
                closed = true;
            }
        }
    }
}
