package gold.debug.windowstolinux.mod.deployment.service.standard.assisted;

import gold.debug.windowstolinux.mod.deployment.ai.skill.DeploymentSkillResources;

import java.io.IOException;
import java.util.*;

import gold.debug.windowstolinux.appapi.ai.model.AiPurpose;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.task.action.ActionIntent;
import gold.debug.windowstolinux.appapi.task.action.ActionSemantics;
import gold.debug.windowstolinux.appapi.task.action.ApprovalGrant;
import gold.debug.windowstolinux.appapi.task.action.InteractionService;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;
import gold.debug.windowstolinux.appapi.task.result.Problem;
import gold.debug.windowstolinux.appapi.task.result.ProblemCode;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.mod.deployment.ai.DeploymentAiJson;

/** Applies the selected independent model policy only to registered fixed deployment effects, preserving exact host grants. / 仅对已登记固定部署效果应用所选独立模型策略，保留精确宿主授权。 */
final class DeploymentAssistedApproval {
    /** Fixed standard operations; unclassified SSH or transfer effects never enter automatic review. / 固定标准动作；未分类SSH或传输效果绝不进入自动审核。 */
    private static final Set<String> OPERATIONS = Set.of("deployment.helper.install.v1", "deployment.helper.install.tracked.v1", "deployment.helper.install.status.v1", "deployment.helper.probe.v1",
        "deployment.candidate.host-probe.v1", "deployment.candidate.container-probe.v1", "deployment.candidate.capacity.v1", "deployment.candidate.create.v1", "deployment.candidate.query.v1", "deployment.candidate.upload.v1", "deployment.candidate.verify.v1",
        "deployment.toolchain.prepare.v1", "deployment.toolchain.bind.v1", "deployment.build.preflight.v1", "deployment.build.run.v1",
        "deployment.publication.snapshot.v1", "deployment.publication.original-status.v1", "deployment.publication.snapshot-discard.v1", "deployment.publication.config.v1", "deployment.publication.secret.v1",
        "deployment.publication.publish.v1", "deployment.publication.health.v1", "deployment.publication.stop-old.v1", "deployment.publication.inspect.v1", "deployment.publication.observe.v1",
        "deployment.publication.observe-restored.v1", "deployment.publication.rollback.v1", "deployment.publication.retain.v1", "deployment.publication.maintenance-end.v1", "deployment.publication.candidate-cleanup.v1",
        "deployment.application.lifecycle.v1", "deployment.native-db.inspect.v1", "deployment.native-db.target.v1", "deployment.native-db.start.v1", "deployment.native-db.install.v1",
        "deployment.native-db.prepare.v1", "deployment.native-db.initialize.v1", "deployment.native-db.resume.v1", "deployment.native-db.status.v1");

    /** Explicit human business reviews remain human even under an automatic effect policy. / 自动效果策略下明确人工业务审阅仍由人工完成。 */
    private static final Set<String> HUMAN = Set.of("deployment.assisted.manual-continuation", "deployment.assisted.unresolved");

    /** Prevents construction. / 禁止构造。 */
    private DeploymentAssistedApproval() {
    }

    /** Reviews a registered actual intent without changing any field submitted to the host. / 审核已登记实际意图，不改变提交宿主的任何字段。
     * @param context frozen task capabilities / 冻结任务能力
     * @param host original exact host approval / 原精确宿主审批
     * @param intent exact actual effect / 精确实际效果
     * @param task authentic original task / 真实原任务
     * @param revision frozen complete model revision / 冻结完整模型修订
     * @param policy selected original policy / 所选原策略
     * @param rememberAuthorization selected original question repetition / 所选原问题重复规则
     * @return original host grant or retained refusal / 原宿主授权或保留拒绝
     * @throws ApiException if the fixed action or independent review is unavailable, denied or unknown / 固定动作或独立审阅不可用、拒绝或未知时 */
    static OperationResult<ApprovalGrant> approve(ModContext context, InteractionService host, ActionIntent intent, TaskContext task, String revision, String policy, boolean rememberAuthorization) throws ApiException {
        context.ai().configurationRevision(AiPurpose.LANGUAGE, task);
        if (HUMAN.contains(intent.type())) return host.approve(intent, task);
        var decisions = Set.of("deployment.assisted.retry", "deployment.assisted.build-review", "deployment.assisted.environment-recovery");
        if (decisions.contains(intent.type()) && intent.semantics().isEmpty()) {
            var target = intent.target();
            intent = intent.withSemantics(new ActionSemantics(intent.type(), intent.serverIds().stream()
                .map(server -> new gold.debug.windowstolinux.appapi.task.action.ActionTarget(server, "deployment-review", target))
                .collect(java.util.stream.Collectors.toUnmodifiableSet())));
        }
        if (!decisions.contains(intent.type())
            && (intent.semantics().isEmpty() || !OPERATIONS.contains(intent.semantics().orElseThrow().operation())))
            throw denied(task, "DEPLOYMENT_ASSISTED_ACTION_UNREGISTERED");
        if (!revision.equals(context.ai().configurationRevision(AiPurpose.APPROVAL, task)))
            throw denied(task, "DEPLOYMENT_ASSISTED_CONFIGURATION_CHANGED");
        try {
            var facts = new LinkedHashMap<String, Object>();
            facts.put("operation", intent.semantics().map(ActionSemantics::operation).orElse(intent.type()));
            facts.put("serverTargets", intent.serverIds());
            facts.put("resourceTargets", intent.semantics().map(ActionSemantics::targets).orElse(Set.of()).stream()
                .sorted(java.util.Comparator.comparing(gold.debug.windowstolinux.appapi.task.action.ActionTarget::serverId)
                    .thenComparing(gold.debug.windowstolinux.appapi.task.action.ActionTarget::kind)
                    .thenComparing(gold.debug.windowstolinux.appapi.task.action.ActionTarget::id)).toList());
            facts.put("businessRevision", intent.revision());
            facts.put("businessRevisionMeaning", "Identity of the reviewed business state; not automatically the digest of every resource. Compare each digest to the expected value of that same object.");
            facts.put("exactAction", intent.exactAction());
            facts.put("authorizationPolicy", policy);
            var scope = context instanceof DeploymentAssistedScope value ? value : null;
            if (scope != null) facts.put("currentCommandEvidence", scope.effectFacts(intent, task));
            var response = new DeploymentAiJson(context).askCommand(
                gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentReviewDecision.INSTRUCTION
                    + DeploymentSkillResources.text(DeploymentAssistedApproval.class, "fixed-effect-review.md"),
                facts, revision, task, gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentReviewDecision::malformed);
            var review = gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentReviewDecision.parse(response);
            if (!review.decision().equals("APPROVE")) {
                gold.debug.windowstolinux.mod.deployment.service.feedback.DeploymentDiagnostics.assistedReviewDenied(context, task, review.reason());
                throw new ApiException(new Problem(ProblemCode.ACCESS_DENIED, "DEPLOYMENT_ASSISTED_REVIEW_DENIED",
                    "Original fixed deployment review was denied", Map.of("task", task.id().toString(), "reviewReason", review.reason(),
                    "requiresUserConfirmation", Boolean.toString(review.requiresUserConfirmation())), Optional.of(task.id())));
            }
            context.ai().configurationRevision(AiPurpose.APPROVAL, task);
            if (scope != null) scope.effectFacts(intent, task);
            var actual = gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentAuthorizationPolicy.authorize(host,
                intent, Optional.empty(), task, policy, rememberAuthorization, review);
            context.ai().configurationRevision(AiPurpose.APPROVAL, task);
            if (scope != null) scope.effectFacts(intent, task);
            return actual;
        } catch (DeploymentAiJson.CapabilityOutcomeException outcome) {
            return new OperationResult<>(outcome.outcome(), Optional.empty(), outcome.problem());
        } catch (IOException invalid) {
            var failure = new ApiException(new Problem(ProblemCode.INVALID_INPUT, "DEPLOYMENT_ASSISTED_REVIEW_INVALID", "Independent fixed-action review was invalid",
                Map.of("task", task.id().toString()), Optional.of(task.id())));
            failure.initCause(invalid);
            throw failure;
        }
    }

    /** Preserves original task identity for a known fixed-policy refusal. / 固定策略已知拒绝保留原任务身份。
     * @param task original task / 原任务
     * @param event stable public refusal / 稳定公开拒绝
     * @return structured exact refusal / 结构化精确拒绝 */
    private static ApiException denied(TaskContext task, String event) {
        return new ApiException(new Problem(ProblemCode.ACCESS_DENIED, event, "Original fixed deployment review was denied", Map.of("task", task.id().toString()), Optional.of(task.id())));
    }
}
