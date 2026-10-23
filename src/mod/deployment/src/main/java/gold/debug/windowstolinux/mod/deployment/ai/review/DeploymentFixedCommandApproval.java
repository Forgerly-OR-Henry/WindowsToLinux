package gold.debug.windowstolinux.mod.deployment.ai.review;

import gold.debug.windowstolinux.mod.deployment.ai.skill.DeploymentSkillResources;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

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
    /** Prevents construction. / 禁止构造。 */
    private DeploymentFixedCommandApproval() {
    }

    /** Checks an exact registered meaning without minting any permission. / 检查精确已登记含义，不产生任何权限。
     * @param intent actual original action / 实际原动作
     * @return whether its fixed operation is registered / 固定动作是否已登记 */
    public static boolean registered(ActionIntent intent) {
        return intent.semantics().filter(value -> DeploymentFixedCommandProtocol.registeredOperation(value.operation())).isPresent();
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
        return approve(context, host, intent, request, task, revision, policy, rememberAuthorization, model, currentEvidence,
            trustedInstructions, new DeploymentFixedCommandProtocol(context));
    }

    static OperationResult<ApprovalGrant> approve(ModContext context, InteractionService host, ActionIntent intent,
                                                  CommandRequest request, TaskContext task, String revision, String policy,
                                                  boolean rememberAuthorization, DeploymentCommandReviewModel model,
                                                  DeploymentCurrentCommandEvidence currentEvidence, boolean trustedInstructions,
                                                  DeploymentCommandProtocol protocol) throws ApiException {
        policy = DeploymentApprovalPolicies.boundary(task, policy);
        DeploymentAuthorizationPolicy.requireAllowed(policy, rememberAuthorization);
        if (!protocol.registered(intent) || !intent.type().equals("platform.ssh.execute") || intent.serverIds().size() != 1)
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
            var layout = gold.debug.windowstolinux.mod.deployment.config.DeploymentConfigCapture.originalTaskLayout(context.tasks(), task);
            String protocolSnapshot = DeploymentAiJson.facts(protocol.inspect(intent, request, layout, task), 1048576);
            var facts = new LinkedHashMap<String, Object>(protocolFacts(protocolSnapshot));
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
            verifyProtocol(context, protocol, intent, request, layout, task, protocolSnapshot);
            diagnostic.put("validationStage", "rememberAuthorization");
            var grant = DeploymentAuthorizationPolicy.authorize(host, intent, Optional.of(request), task, policy, rememberAuthorization, review);
            requireRoute(context, task, revision);
            currentEvidence.verify(intent, request, task);
            verifyProtocol(context, protocol, intent, request, layout, task, protocolSnapshot);
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

    private static void verifyProtocol(ModContext context, DeploymentCommandProtocol protocol, ActionIntent intent, CommandRequest request,
                                       gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout layout,
                                       TaskContext task, String reviewedSnapshot) throws ApiException, IOException {
        var currentLayout = gold.debug.windowstolinux.mod.deployment.config.DeploymentConfigCapture.originalTaskLayout(context.tasks(), task);
        if (!layout.equals(currentLayout)) throw new IOException("Original command remote layout changed");
        String current = DeploymentAiJson.facts(protocol.inspect(intent, request, currentLayout, task), 1048576);
        if (!reviewedSnapshot.equals(current)) throw new IOException("Original command protocol evidence changed");
    }

    /** Detaches review input from the protocol's mutable object graph; the canonical string remains the comparison identity. */
    private static Map<String, Object> protocolFacts(String snapshot) throws IOException {
        var decoded = new com.fasterxml.jackson.databind.ObjectMapper().readValue(snapshot,
            new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
            });
        var result = new LinkedHashMap<String, Object>();
        decoded.forEach((key, value) -> result.put(key, immutableValue(value)));
        return java.util.Collections.unmodifiableMap(result);
    }

    private static Object immutableValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            var result = new LinkedHashMap<String, Object>();
            map.forEach((key, item) -> result.put((String) key, immutableValue(item)));
            return java.util.Collections.unmodifiableMap(result);
        }
        if (value instanceof java.util.List<?> list)
            return list.stream().map(DeploymentFixedCommandApproval::immutableValue).toList();
        return value;
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
