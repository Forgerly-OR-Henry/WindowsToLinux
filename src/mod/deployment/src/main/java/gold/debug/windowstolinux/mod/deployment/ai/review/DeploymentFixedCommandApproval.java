package gold.debug.windowstolinux.mod.deployment.ai.review;

import gold.debug.windowstolinux.mod.deployment.ai.skill.DeploymentSkillResources;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import gold.debug.windowstolinux.appapi.ai.model.AiPurpose;
import gold.debug.windowstolinux.appapi.linux.action.RemoteActions;
import gold.debug.windowstolinux.appapi.linux.command.CommandRequest;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.action.ActionIntent;
import gold.debug.windowstolinux.appapi.task.action.ApprovalGrant;
import gold.debug.windowstolinux.appapi.task.action.InteractionService;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;
import gold.debug.windowstolinux.appapi.task.result.Problem;
import gold.debug.windowstolinux.appapi.task.result.ProblemCode;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.mod.deployment.ai.DeploymentAiJson;

/** Reviews the actual fixed command before obtaining its exact original host grant. / 获取精确原宿主许可前审核实际固定命令。 */
public final class DeploymentFixedCommandApproval {
    /** Exact registered deployment operation meanings. / 精确已登记部署动作含义。 */
    private static final Set<String> OPERATIONS = Set.of("deployment.helper.install.v1", "deployment.helper.install.tracked.v1", "deployment.helper.install.files.v1", "deployment.helper.install.files.custom.v1", "deployment.agent.helper.readiness.v1", "deployment.helper.install.status.v1", "deployment.helper.probe.v1",
        "deployment.candidate.host-probe.v1", "deployment.candidate.container-probe.v1", "deployment.candidate.capacity.v1", "deployment.candidate.create.v1", "deployment.candidate.query.v1", "deployment.candidate.upload.v1", "deployment.candidate.verify.v1",
        "deployment.toolchain.prepare.v1", "deployment.toolchain.bind.v1", "deployment.build.preflight.v1", "deployment.build.run.v1",
        "deployment.publication.snapshot.v1", "deployment.publication.original-status.v1", "deployment.publication.snapshot-discard.v1", "deployment.publication.config.v1", "deployment.publication.secret.v1",
        "deployment.publication.publish.v1", "deployment.publication.health.v1", "deployment.publication.stop-old.v1", "deployment.publication.inspect.v1", "deployment.publication.observe.v1",
        "deployment.publication.observe-restored.v1", "deployment.publication.rollback.v1", "deployment.publication.retain.v1", "deployment.publication.maintenance-end.v1", "deployment.publication.candidate-cleanup.v1",
        "deployment.application.lifecycle.v1", "deployment.handoff.managed-current-status.v1", "deployment.native-db.inspect.v1", "deployment.native-db.target.v1", "deployment.native-db.start.v1", "deployment.native-db.install.v1",
        "deployment.native-db.prepare.v1", "deployment.native-db.initialize.v1", "deployment.native-db.resume.v1", "deployment.native-db.status.v1", "deployment.agent.helper.v1",
        "deployment.model.command.v1", "deployment.model.command.status.v1", "deployment.agent.preparation.absence.v1", "deployment.agent.preparation.partial-observation.v1",
        "deployment.agent.toolchain.dependencies.v1", "deployment.agent.toolchain.resolve.v1", "deployment.agent.toolchain.prepare.v1", "deployment.agent.toolchain.bind.v1",
        "deployment.agent.publication-recovery.readiness.v1", "deployment.agent.publication-recovery.install.v1", "deployment.agent.publication-recovery.guarded-first-rollback.v1",
        "deployment.agent.publication-recovery-diagnostic.v1", "deployment.agent.publication-recovery-preflight.v2", "deployment.agent.publication-continuation.v1", "deployment.agent.publication-contract-diagnostic.v1", "deployment.agent.publication-contract-diagnostic.followup.v1", "deployment.agent.publication-correction.v1", "deployment.agent.isolation.preflight.v1",
        "deployment.agent.toolchain.controller.readiness.v1", "deployment.agent.toolchain.controller.install.v1", "deployment.agent.toolchain.controller.prepare.v1", "deployment.agent.toolchain.controller.bind.v1");

    /** Prevents construction. / 禁止构造。 */
    private DeploymentFixedCommandApproval() {
    }

    /** Checks an exact registered meaning without minting any permission. / 检查精确已登记含义，不产生任何权限。
     * @param intent actual original action / 实际原动作
     * @return whether its fixed operation is registered / 固定动作是否已登记 */
    public static boolean registered(ActionIntent intent) {
        return intent.semantics().filter(value -> OPERATIONS.contains(value.operation())).isPresent();
    }

    /** Validates the exact request before exposing complete executable evidence to independent AI. / 向独立AI提供完整可执行依据前校验精确请求。
     * @param context original owner capabilities / 原所有者能力
     * @param host original host review / 原宿主审核
     * @param intent exact original command intent / 精确原命令意图
     * @param request complete actual request / 完整实际请求
     * @param task authentic original task / 真实原任务
     * @param revision frozen model route / 冻结模型路由
     * @param policy original approval policy / 原审批策略
     * @param rememberAuthorization original question repetition / 原问题重复规则
     * @return actual single-use grant or classified refusal / 实际单次许可或分类拒绝
     * @throws ApiException if exact evidence or independent review is unavailable / 精确依据或独立审核不可用时 */
    public static OperationResult<ApprovalGrant> approve(ModContext context, InteractionService host, ActionIntent intent,
                                                         CommandRequest request, TaskContext task, String revision, String policy, boolean rememberAuthorization) throws ApiException {
        return approveWithEvidence(context, host, intent, request, task, revision, policy, rememberAuthorization,
            context instanceof DeploymentCurrentCommandEvidence evidence ? evidence : DeploymentCurrentCommandEvidence.NONE);
    }

    public static OperationResult<ApprovalGrant> approveWithEvidence(ModContext context, InteractionService host, ActionIntent intent,
                                                                     CommandRequest request, TaskContext task, String revision, String policy,
                                                                     boolean rememberAuthorization, DeploymentCurrentCommandEvidence evidence) throws ApiException {
        return approve(context, host, intent, request, task, revision, policy, rememberAuthorization, new DeploymentAiJson(context), evidence);
    }

    static OperationResult<ApprovalGrant> approve(ModContext context, InteractionService host, ActionIntent intent,
                                                  CommandRequest request, TaskContext task, String revision, String policy, boolean rememberAuthorization, DeploymentAiJson ai) throws ApiException {
        return approve(context, host, intent, request, task, revision, policy, rememberAuthorization, ai,
            DeploymentCurrentCommandEvidence.NONE);
    }

    static OperationResult<ApprovalGrant> approve(ModContext context, InteractionService host, ActionIntent intent,
                                                  CommandRequest request, TaskContext task, String revision, String policy,
                                                  boolean rememberAuthorization, DeploymentAiJson ai,
                                                  DeploymentCurrentCommandEvidence currentEvidence) throws ApiException {
        return approve(context, host, intent, request, task, revision, policy, rememberAuthorization,
            ai == null ? null : DeploymentCommandReviewModel.submission(ai), currentEvidence,
            ai == null || ai.supportsTrustedCommandInstructions());
    }

    static OperationResult<ApprovalGrant> approve(ModContext context, InteractionService host, ActionIntent intent,
                                                  CommandRequest request, TaskContext task, String revision, String policy,
                                                  boolean rememberAuthorization, DeploymentCommandReviewModel model,
                                                  DeploymentCurrentCommandEvidence currentEvidence, boolean trustedInstructions) throws ApiException {
        DeploymentAuthorizationPolicy.requireAllowed(policy, rememberAuthorization);
        if (!registered(intent) || !intent.type().equals("platform.ssh.execute") || intent.serverIds().size() != 1)
            throw denied(task, "DEPLOYMENT_COMMAND_UNREGISTERED");
        var actual = RemoteActions.command(new ServerId(intent.serverIds().getFirst()), intent.revision(), request);
        if (!actual.type().equals(intent.type()) || !actual.serverIds().equals(intent.serverIds())
            || !actual.target().equals(intent.target()) || !actual.revision().equals(intent.revision())
            || !actual.exactAction().equals(intent.exactAction()) || !actual.effectSummary().equals(intent.effectSummary()))
            throw denied(task, "DEPLOYMENT_COMMAND_REQUEST_CHANGED");
        var diagnostic = new LinkedHashMap<String, String>();
        diagnostic.put("task", task.id().toString());
        diagnostic.put("validationStage", "evidence");
        try {
            if (intent.semantics().orElseThrow().operation().equals("deployment.agent.isolation.preflight.v1"))
                DeploymentCommandStaticGate.requireIsolationAllowed(request,
                    gold.debug.windowstolinux.mod.deployment.config.DeploymentConfigCapture.originalTaskLayout(context.tasks(), task));
            else DeploymentCommandStaticGate.requireFixedAllowed(request.script());
            var facts = new LinkedHashMap<String, Object>(DeploymentCommandEvidence.facts(intent, request,
                gold.debug.windowstolinux.mod.deployment.config.DeploymentConfigCapture.originalTaskLayout(context.tasks(), task), context, task));
            facts.put("authorizationPolicy", policy);
            facts.put("effectSummary", intent.effectSummary());
            facts.put("presentationLocale", context.ui().environment().locale().toLanguageTag());
            requireRoute(context, task, revision);
            var current = currentEvidence.facts(intent, request, task);
            if (!current.isEmpty()) {
                facts.put("currentCommandEvidence", current);
                requireRoute(context, task, revision);
            }
            currentEvidence.verify(intent, request, task);
            String instruction = model.responseInstructions()
                + DeploymentSkillResources.text(DeploymentFixedCommandApproval.class, "command-review.md");
            String supplement = currentEvidence.reviewInstructions(intent, request, task);
            if (supplement == null) throw new IOException("Current review instructions are missing");
            if (!supplement.isEmpty()) {
                if (!trustedInstructions)
                    throw new IOException("Trusted current instructions require SYSTEM delivery; this approval conversation cannot preserve them");
                instruction += "\n" + supplement;
            }
            diagnostic.put("validationStage", "response");
            var result = model.review(instruction, facts, intent, request, revision, task);
            var review = result.decision();
            if (!review.decision().equals("APPROVE")) {
                gold.debug.windowstolinux.mod.deployment.service.feedback.DeploymentDiagnostics.commandReviewDenied(context, task, review.reason());
                var refusalData = new LinkedHashMap<String, String>(result.refusalData());
                refusalData.put("task", task.id().toString());
                refusalData.put("reviewReason", review.reason());
                refusalData.put("requiresUserConfirmation", Boolean.toString(review.requiresUserConfirmation()));
                var refusal = new ApiException(new Problem(ProblemCode.ACCESS_DENIED, "DEPLOYMENT_COMMAND_REVIEW_DENIED",
                    "The independent model refused the original command", refusalData, Optional.empty()));
                try {
                    currentEvidence.reviewed(intent, request, task, review);
                } catch (ApiException unavailable) {
                    refusal.addSuppressed(unavailable);
                }
                throw refusal;
            }
            currentEvidence.reviewed(intent, request, task, review);
            requireRoute(context, task, revision);
            currentEvidence.verify(intent, request, task);
            verifyReceiptStatus(context, task, intent, request, facts);
            diagnostic.put("validationStage", "rememberAuthorization");
            var grant = DeploymentAuthorizationPolicy.authorize(host, intent, Optional.of(request), task, policy, rememberAuthorization, review);
            requireRoute(context, task, revision);
            currentEvidence.verify(intent, request, task);
            verifyReceiptStatus(context, task, intent, request, facts);
            return grant;
        } catch (DeploymentAiJson.CapabilityOutcomeException outcome) {
            return new OperationResult<>(outcome.outcome(), Optional.empty(), outcome.problem());
        } catch (SecurityException denied) {
            throw denied(task, "DEPLOYMENT_COMMAND_STATIC_DENIED");
        } catch (IOException | IllegalArgumentException invalid) {
            diagnostic.putAll(DeploymentCommandReviewModel.failureDiagnostics(invalid));
            var error = new ApiException(new Problem(ProblemCode.INVALID_INPUT, "DEPLOYMENT_COMMAND_REVIEW_INVALID",
                "Complete independent command review was invalid", diagnostic, Optional.empty()));
            error.initCause(invalid);
            throw error;
        }
    }

    private static void verifyReceiptStatus(ModContext context, TaskContext task, ActionIntent intent,
                                            CommandRequest request, Map<String, Object> facts) throws ApiException, IOException {
        if (intent.semantics().orElseThrow().operation().equals("deployment.model.command.status.v1")
            && !java.util.Objects.equals(facts.get("modelCommandReceiptStatus"),
            gold.debug.windowstolinux.mod.deployment.linux.command.DeploymentModelCommandRunner.receiptStatusEvidence(context, task,
                intent, request, gold.debug.windowstolinux.mod.deployment.config.DeploymentConfigCapture.originalTaskLayout(context.tasks(), task))))
            throw new IOException("Original model command receipt evidence changed");
    }

    /** Requires both original model roles without silently adopting a new route. / 要求两个原模型角色，不默默采用新路由。
     * @param context original owner / 原所有者
     * @param task authentic original task / 真实原任务
     * @param revision frozen complete route / 冻结完整路由
     * @throws ApiException if either role changed or is unavailable / 任一角色变化或不可用时 */
    public static void requireRoute(ModContext context, TaskContext task, String revision) throws ApiException {
        if (!revision.equals(context.ai().configurationRevision(AiPurpose.LANGUAGE, task))
            || !revision.equals(context.ai().configurationRevision(AiPurpose.APPROVAL, task)))
            throw denied(task, "DEPLOYMENT_COMMAND_CONFIGURATION_CHANGED");
    }

    /** Reports only a stable refusal and the original task identity. / 仅报告稳定拒绝及原任务身份。
     * @param task original task / 原任务
     * @param event stable refusal / 稳定拒绝
     * @return classified original refusal / 分类原拒绝 */
    private static ApiException denied(TaskContext task, String event) {
        return new ApiException(new Problem(ProblemCode.ACCESS_DENIED, event, "Original command review was denied",
            Map.of("task", task.id().toString()), Optional.empty()));
    }
}
