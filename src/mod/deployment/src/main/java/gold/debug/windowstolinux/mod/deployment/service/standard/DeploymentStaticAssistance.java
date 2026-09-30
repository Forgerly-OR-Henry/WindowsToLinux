package gold.debug.windowstolinux.mod.deployment.service.standard;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.CancellationException;

import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.server.ServerRef;
import gold.debug.windowstolinux.appapi.task.*;
import gold.debug.windowstolinux.mod.deployment.ai.DeploymentAiJson;
import gold.debug.windowstolinux.mod.deployment.service.core.DeploymentReferenceFlow;
import gold.debug.windowstolinux.mod.deployment.service.standard.DeploymentStaticWorkflow.*;
import gold.debug.windowstolinux.mod.deployment.service.standard.deploy.assistance.DeploymentAssistedAdvisor;
import gold.debug.windowstolinux.mod.deployment.service.standard.deploy.assistance.DeploymentAssistedBuildCorrection;
import gold.debug.windowstolinux.mod.deployment.service.standard.deploy.contract.*;
import gold.debug.windowstolinux.mod.deployment.service.standard.deploy.input.DeploymentStaticRuntimeResolver;
import gold.debug.windowstolinux.mod.deployment.service.standard.deploy.input.DeploymentReviewField;
import gold.debug.windowstolinux.mod.deployment.source.*;

/** Keeps source advice, human review and assisted publication identity within the deterministic workflow. / 在确定性流程内组织来源建议、人工审阅及辅助发布身份。 */
public final class DeploymentStaticAssistance {
    /** Original owner capabilities and frozen dispatch scope. / 原所有者能力及冻结发送作用域。 */
    private final ModContext context;

    /** Existing deterministic field validator. / 既有确定性字段校验器。 */
    private final DeploymentStaticWorkflow workflow;

    /** Binds review to the actual original workflow without admitting another task. / 将审阅绑定真实原流程，不准入第二项任务。
     * @param context original capabilities / 原能力
     * @param workflow deterministic original workflow / 确定性原流程 */
    public DeploymentStaticAssistance(ModContext context, DeploymentStaticWorkflow workflow) {
        this.context = Objects.requireNonNull(context);
        this.workflow = Objects.requireNonNull(workflow);
    }

    /** Keeps one original form available for explicit typed build correction. / 保留一份原表单供明确类型化构建纠正。
     * @param prompt source-backed original form / 源码依据原表单
     * @param initial actually reviewed answer / 实际审阅答案
     * @param reviewer original human view / 原人工视图
     * @param advisor task-local bounded advisor / 任务内部有界建议器
     * @return same-source correction callback / 同一来源纠正回调 */
    public DeploymentAssistedBuildCorrection correction(Prompt prompt, Answers initial, Reviewer reviewer, DeploymentAssistedAdvisor advisor) {
        var prior = new java.util.concurrent.atomic.AtomicReference<>(initial);
        return (previous, failure, task) -> {
            var before = prior.get(); var request = previous.request();
            var values = new LinkedHashMap<>(prompt.defaults()); values.putAll(before.values());
            var fields = List.of("primary", "secondary", "version", "jvmTarget").stream()
                    .map(key -> DeploymentStaticRuntimeResolver.field(prompt.componentId(), key, values.getOrDefault(key, ""), List.of())).toList();
            String phase = failure.context().getOrDefault("recoveryPhase", "BUILD");
            if (!Set.of("BUILD", "ROLLBACK_VERIFIED").contains(phase)) throw new IllegalArgumentException("Unsupported fixed correction boundary");
            var retry = advisor.afterFailure(request.server(), phase, new OperationResult<Void>(Outcome.FAILED, Optional.empty(), Optional.of(failure)),
                    request.material().snapshot().sha256(), fields, task);
            if (!retry.retry()) return new OperationResult<>(Outcome.FAILED, Optional.empty(), Optional.of(failure));
            var answers = reviewer.reviewBuildCorrection(request.server(), request.material().snapshot().sha256(),
                    new Prompt(prompt.componentId(), prompt.applicationId(), prompt.projectType(), values, fields), before, task);
            if (answers.outcome() != Outcome.SUCCEEDED) return new OperationResult<>(answers.outcome(), Optional.empty(), answers.problem());
            var after = answers.value().orElseThrow();
            var nextValues = new LinkedHashMap<>(values); nextValues.putAll(after.values());
            for (var entry : nextValues.entrySet())
                if (!Set.of("primary", "secondary", "version", "jvmTarget").contains(entry.getKey()) && !Objects.equals(values.get(entry.getKey()), entry.getValue()))
                    throw new SecurityException("Build correction changed an immutable original field");
            if (!before.files().equals(after.files()) || !before.limits().equals(after.limits()) || before.containerRisk() != after.containerRisk() || before.adapterRisk() != after.adapterRisk())
                throw new SecurityException("Build correction changed original resources or limits");
            var definition = new DeploymentStaticRuntimeResolver().runtime(prompt.projectType(), nextValues);
            boolean changed = !definition.equals(request.runtime());
            int branch = changed ? 0 : failure.eventCode().equals("DEPLOYMENT_BUILD_COMPATIBLE_FAILURE") ? 1 : -1;
            if (branch < 0) return new OperationResult<>(Outcome.FAILED, Optional.empty(), Optional.of(failure));
            var consent = new DeploymentReviewConsent(request.facts().applicationId(), request.server().id(), request.material().snapshot().sha256(), request.archive().sha256(), java.time.Instant.now());
            var corrected = new ReviewedDeploymentRequest(request.server(), request.facts(), request.material(), request.archive(), request.configuration(),
                    request.secretReferences(), request.resources(), definition, request.userAccessUrl(), request.limits(), consent, request.containerDaemonRiskAccepted(), request.experimentalAdapterRiskAccepted());
            var actual = new gold.debug.windowstolinux.mod.deployment.service.standard.deploy.plan.ReviewedDeploymentPlanner().plan(corrected);
            var checked = new gold.debug.windowstolinux.mod.deployment.service.standard.deploy.DeploymentStandardPlanner().render(actual, task);
            if (checked.outcome() != Outcome.SUCCEEDED) return new OperationResult<>(checked.outcome(), Optional.empty(), checked.problem());
            prior.set(new Answers(Map.copyOf(nextValues), before.files(), before.limits(), before.containerRisk(), before.adapterRisk()));
            return new OperationResult<>(Outcome.SUCCEEDED, Optional.of(new DeploymentAssistedBuildCorrection.Correction(actual, values, nextValues, branch)), Optional.empty());
        };
    }

    /** Gives a known pure form rejection one bounded, explicitly approved review retry. / 为已知纯表单拒绝提供一次有界且明确批准的审阅重试。
     * @param server original target / 原目标
     * @param revision frozen source / 冻结来源
     * @param prompts source-backed forms / 有来源依据的表单
     * @param reviewer original human view / 原人工视图
     * @param advice optional bounded assistance / 可选有界辅助
     * @param task original task / 原任务
     * @return checked human answers or original refusal / 已检查人工答案或原拒绝
     * @throws Exception on unknown advice, cancellation or invalid second review / 建议未知、取消或二次审阅无效时 */
    public OperationResult<Map<String, Answers>> review(ServerRef server, String revision, List<Prompt> prompts,
            Reviewer reviewer, DeploymentAssistedAdvisor advice, TaskContext task) throws Exception {
        for (int attempt = 0; attempt < 2; attempt++) {
            var reviewed = reviewer.review(server, revision, prompts, task);
            if (reviewed.outcome() != Outcome.SUCCEEDED)
                return reviewed;
            var answers = reviewed.value().orElseThrow();
            if (advice != null)
                answers.forEach((component, answer) -> {
                    var qualified = new LinkedHashMap<String, String>();
                    answer.values().forEach((key, value) -> qualified.put(component + "/" + key, value));
                    advice.humanAnswers(qualified);
                });
            try {
                if (!answers.keySet()
                        .equals(prompts.stream().map(Prompt::componentId).collect(java.util.stream.Collectors.toSet())))
                    throw new IllegalArgumentException("Original form answer set differs");
                for (var prompt : prompts)
                    workflow.validateReview(prompt, answers.get(prompt.componentId()));
                return reviewed;
            } catch (IllegalArgumentException invalid) {
                if (advice == null || attempt != 0)
                    throw invalid;
                var failure = new OperationResult<Void>(Outcome.FAILED, Optional.empty(),
                        Optional.of(new Problem(ProblemCode.INVALID_INPUT, "DEPLOYMENT_ASSISTED_PREFLIGHT_REJECTED",
                                "Deterministic runtime form rejected the supplied fields", Map.of(),
                                Optional.empty())));
                var retry = advice.afterFailure(server, "PREFLIGHT", failure, revision,
                        prompts.stream().flatMap(prompt -> prompt.fields().stream()).toList(), task);
                if (!retry.retry())
                    return new OperationResult<>(Outcome.FAILED, Optional.empty(), failure.problem());
                if (!retry.values().isEmpty())
                    throw new SecurityException("AI cannot replace human runtime answers");
            }
        }
        throw new IllegalStateException("Assisted form retry budget exhausted");
    }

    /** Marks the actual assisted engine and retains unknown source scopes without replaying its author. / 标记真实辅助引擎并保留未知来源作用域，不重放作者。
     * @param prepared original deterministic preparation / 原确定性准备
     * @return same one-shot publication with assisted mode / 带辅助模式的同一次性发布 */
    public static OperationResult<DeploymentReferenceFlow.PreparedPublication> publication(
            OperationResult<DeploymentReferenceFlow.PreparedPublication> prepared) {
        var ready = prepared.value().orElseThrow();
        var original = ready.plan();
        var plan = new gold.debug.windowstolinux.mod.deployment.service.core.DeploymentPublicationPlan(
                original.executionId(), original.project(), original.applicationId(), original.server(),
                gold.debug.windowstolinux.mod.deployment.db.DeploymentExecutionIdentity.DeploymentExecutionMode.ASSISTED,
                original.reviewedRevision(), original.references(), original.createdAt());
        var result = new java.util.concurrent.atomic.AtomicReference<Outcome>(Outcome.UNKNOWN);
        var guarded = new DeploymentReferenceFlow.PreparedPublication(plan, task -> {
            var actual = ready.author().run(task);
            result.set(actual.outcome());
            return actual;
        }, () -> {
            if (result.get() != Outcome.UNKNOWN)
                ready.cleanup().close();
        });
        return new OperationResult<>(Outcome.SUCCEEDED, Optional.of(guarded), Optional.empty());
    }

    /** Adds bounded source-backed proposals only for genuinely missing technical fields. / 仅对真实缺失的技术字段加入有界来源建议。
     * @param server original target / 原目标
     * @param prompt deterministic review fields / 确定性审阅字段
     * @param advice optional assisted session / 可选辅助会话
     * @param material frozen source / 冻结来源
     * @param task original task / 原任务
     * @return still-human-reviewed prompt / 仍需人工审阅的问题
     * @throws Exception on uncertain source or model evidence / 来源或模型依据不明时 */
    public Prompt prompt(ServerRef server, Prompt prompt, DeploymentAssistedAdvisor advice,
            DeploymentSourceMaterial material, TaskContext task) throws Exception {
        if (advice == null || prompt.fields().stream()
                .noneMatch(field -> field.value().isBlank() && DeploymentAssistedAdvisor.allows(field.id())))
            return prompt;
        var browser = new DeploymentSourceBrowser(material.snapshot().directory(), material.snapshot().sha256());
        gold.debug.windowstolinux.mod.deployment.service.standard.deploy.assistance.DeploymentAssistedAnalysis.Advice suggested;
        try {
            suggested = advice.preflight(browser, prompt.fields(), Map.of("component", prompt.componentId(),
                    "projectType", prompt.projectType().name(), "sourceRevision", browser.revision()), task);
        } catch (DeploymentAiJson.CapabilityOutcomeException unavailable) {
            if (unavailable.outcome() == Outcome.UNKNOWN)
                throw new ApiException(unavailable.problem()
                        .orElseGet(() -> new Problem(ProblemCode.UNKNOWN, "DEPLOYMENT_ASSISTED_AI_UNKNOWN",
                                "Assisted model result is unknown", Map.of(), Optional.empty())));
            if (unavailable.outcome() == Outcome.CANCELLED)
                throw new CancellationException();
            var problem = unavailable.problem().orElseGet(() -> new Problem(ProblemCode.UNAVAILABLE,
                    "DEPLOYMENT_ASSISTED_AI_UNAVAILABLE", "Assisted model is unavailable", Map.of(), Optional.empty()));
            if (!advice.manualContinuation(server, "PREFLIGHT", problem, browser.revision(), task))
                throw new CancellationException();
            return prompt;
        } catch (SecurityException invalid) {
            throw invalid;
        } catch (ApiException unavailable) {
            if (unavailable.problem().code() == ProblemCode.UNKNOWN)
                throw unavailable;
            if (unavailable.problem().code() == ProblemCode.CANCELLED)
                throw new CancellationException();
            if (!advice.manualContinuation(server, "PREFLIGHT", unavailable.problem(), browser.revision(), task))
                throw new CancellationException();
            return prompt;
        } catch (IOException unavailable) {
            var problem = new Problem(ProblemCode.IO_FAILURE, "DEPLOYMENT_ASSISTED_ANALYSIS_INVALID",
                    "Assisted source analysis was unavailable", Map.of(), Optional.empty());
            if (!advice.manualContinuation(server, "PREFLIGHT", problem, browser.revision(), task))
                throw new CancellationException();
            return prompt;
        }
        if (!suggested.unresolved().isEmpty()) {
            String detail = String.join("\n", suggested.unresolved());
            var review = context.interaction().approve(new ActionIntent("deployment.assisted.unresolved",
                    List.of(server.id()), prompt.componentId(), browser.revision(), detail,
                    "Review unresolved source analysis before the deterministic deployment form"), task);
            if (review.outcome() == Outcome.UNKNOWN)
                throw new ApiException(review.problem().orElseThrow());
            if (review.outcome() != Outcome.SUCCEEDED)
                throw new CancellationException();
        }
        var fields = prompt.fields().stream()
                .map(field -> suggested.values().containsKey(field.id())
                        ? new DeploymentReviewField(field.id(), field.titleKey(), field.helpKey(),
                                suggested.values().get(field.id()), field.choices())
                        : field)
                .toList();
        return new Prompt(prompt.componentId(), prompt.applicationId(), prompt.projectType(), prompt.defaults(),
                fields);
    }

}
