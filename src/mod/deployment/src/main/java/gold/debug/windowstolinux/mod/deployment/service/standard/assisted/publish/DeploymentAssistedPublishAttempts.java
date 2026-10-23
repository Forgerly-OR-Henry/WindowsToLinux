package gold.debug.windowstolinux.mod.deployment.service.standard.assisted.publish;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

import gold.debug.windowstolinux.appapi.ai.model.AiPurpose;
import gold.debug.windowstolinux.appapi.linux.session.RemoteSession;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.task.action.ActionIntent;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;
import gold.debug.windowstolinux.appapi.task.result.Problem;
import gold.debug.windowstolinux.appapi.task.result.ProblemCode;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.mod.deployment.db.execution.DeploymentAssistedBuildReviewCodec;
import gold.debug.windowstolinux.mod.deployment.db.execution.DeploymentExecutionStore;
import gold.debug.windowstolinux.mod.deployment.db.execution.DeploymentPublishAttemptIntent;
import gold.debug.windowstolinux.mod.deployment.db.execution.DeploymentPublishAttemptProof;
import gold.debug.windowstolinux.mod.deployment.db.execution.DeploymentPublishAttemptReview;
import gold.debug.windowstolinux.mod.deployment.db.execution.DeploymentPublishAttemptStore;
import gold.debug.windowstolinux.mod.deployment.db.execution.DeploymentReviewedPlanDigest;
import gold.debug.windowstolinux.mod.deployment.service.core.model.application.DeploymentUnitIdentity;
import gold.debug.windowstolinux.mod.deployment.service.core.model.build.DeploymentRemoteWorkspace;
import gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentToolchainBindingCodec;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.contract.DeploymentAssistedBuildCorrection;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.build.DeploymentStandardCandidateRunner;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.contract.ReviewedDeploymentPlan;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.plan.ReviewedPlanIdentity;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.publish.attempt.DeploymentPublishRestorationProof;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.publish.cleanup.DeploymentPublishCleanupActionLog;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.publish.graph.DeploymentStandardGraphPublishActionLog;

/** Opens one distinct bounded publication operation only after every original effect and session closed. / 仅在各原效果及会话关闭后开启一次独立有界发布操作。 */
final class DeploymentAssistedPublishAttempts {
    /** Actual frozen business policy and original capabilities. / 实际冻结业务策略及原能力。 */
    private final ModContext context;

    /** Shared original build clocks and correction allowance. / 共用原构建时钟及纠正额度。 */
    private final DeploymentAssistedPublishBuild loop;

    /** Existing fixed candidate implementation. / 既有固定候选实现。 */
    private final DeploymentStandardCandidateRunner candidates;

    /** Binds only existing original implementations. / 仅绑定既有原实现。
     * @param context authentic original Mod scope / 真实原Mod作用域
     * @param loop unchanged original correction loop / 不变原纠正循环
     * @param candidates fixed candidate executor / 固定候选执行器 */
    DeploymentAssistedPublishAttempts(ModContext context, DeploymentAssistedPublishBuild loop,
                                      DeploymentStandardCandidateRunner candidates) {
        this.context = Objects.requireNonNull(context);
        this.loop = Objects.requireNonNull(loop);
        this.candidates = Objects.requireNonNull(candidates);
    }

    /** Carries only preparations bound to the actual next session after formal and checkpoint confirmation. / 仅承载正式及检查点确认后绑定实际下一会话的准备。
     * @param session actual owned next session / 实际所属下一会话
     * @param plans complete current reviewed plans / 完整当前审阅计划
     * @param prepared actual single-use preparations / 实际一次性准备 */
    record Continuation(RemoteSession session, Map<String, ReviewedDeploymentPlan> plans,
                        Map<String, DeploymentStandardCandidateRunner.Prepared> prepared) {
        /** Copies the complete graph without transferring the authentic root task. / 复制完整图，不转移真实原根任务。
         * @param session actual next session / 实际下一会话
         * @param plans complete current plans / 完整当前计划
         * @param prepared actual current preparations / 实际当前准备 */
        Continuation {
            Objects.requireNonNull(session);
            plans = Map.copyOf(plans);
            prepared = Map.copyOf(prepared);
        }
    }

    /** Re-reviews only a known restored failure; unknown transitions retain original resources without another build. / 仅重审已知恢复失败；未知转换保留原资源且不再次构建。
     * @param plans complete actually executed plans / 完整实际执行计划
     * @param units exact original owners / 精确原归属
     * @param corrections original typed human reviewers / 原类型化人工审阅器
     * @param selected exact original component selected for correction / 选定纠正的精确原组件
     * @param graph complete current graph or empty / 完整当前图或空值
     * @param root original graph journal or absence / 原图账本或不存在
     * @param journals all original closed child journals / 所有原已关闭子账本
     * @param failure actual known publication failure / 实际已知发布失败
     * @param task authentic unchanged root task / 真实不变原根任务
     * @return confirmed next operation, known stop or retained unknown / 已确认下一操作、已知停止或保留未知 */
    OperationResult<Continuation> advance(Map<String, ReviewedDeploymentPlan> plans, Map<String, DeploymentUnitIdentity> units,
                                          Map<String, DeploymentAssistedBuildCorrection> corrections, String selected, String graph,
                                          DeploymentStandardGraphPublishActionLog root, Map<String, DeploymentPublishCleanupActionLog> journals,
                                          Problem failure, TaskContext task) {
        if (task.cancelToken().requested() || !loop.fallbackAvailable() || !corrections.containsKey(selected)
            || !units.keySet().equals(plans.keySet()) || !units.keySet().equals(corrections.keySet()))
            return stopped(failure);
        RemoteSession nextSession = null;
        try {
            var previous = loop.selection(units.get(selected).id());
            if (previous.remainingSeconds() <= 60 || journals.values().stream().anyMatch(value -> !value.facts().has("previousRunning")))
                return stopped(failure);
            var proof = DeploymentPublishRestorationProof.read(context, task, units, journals, root, loop::selection, loop.retriesSpent(), failure);
            var bound = new LinkedHashMap<>(failure.context());
            bound.put("recoveryPhase", "ROLLBACK_VERIFIED");
            String binding = failureBinding(proof, units.get(selected).id());
            bound.put("inputBinding", binding);
            var input = corrections.get(selected).review(previous.plan(), new Problem(failure.code(), failure.eventCode(), failure.message(), bound, failure.diagnosticId()), task);
            if (input.outcome() == TaskResult.UNKNOWN)
                return unknown("DEPLOYMENT_ASSISTED_OPERATION_REVIEW_UNCONFIRMED");
            if (input.outcome() != TaskResult.SUCCEEDED) return stopped(failure);
            var corrected = input.value().orElseThrow();
            var intent = intent(previous, corrected, proof, graph, task);
            if (!loop.operation(binding)) return stopped(failure);
            var executions = new DeploymentExecutionStore(context.db());
            var original = executions.findForTask(task).orElseThrow();
            var attempts = new DeploymentPublishAttemptStore(context.db());
            attempts.recordIntent(task, original.identity().executionId(), intent, proof, Instant.now());
            for (var journal : journals.values())
                if (!journal.attemptBoundary(intent))
                    return unknown("DEPLOYMENT_ASSISTED_OPERATION_POINTER_UNCONFIRMED");
            if (root != null && !root.attemptPending(intent))
                return unknown("DEPLOYMENT_ASSISTED_OPERATION_ROOT_UNCONFIRMED");
            var current = new LinkedHashMap<>(plans);
            current.put(selected, corrected.plan());
            nextSession = context.linux().connect(original.identity().server(), task);
            var prepared = prepare(current, units, selected, intent, journals, nextSession, task);
            var review = reviewed(intent, proof, prepared.get(selected), task);
            attempts.adoptReview(task, original.identity().executionId(), review, Instant.now());
            var actual = executions.findForTask(task).orElseThrow();
            var reviews = attempts.reviews(actual);
            if (!attempts.operation(actual).equals(intent.nextOperation()) || !actual.identity().reviewedRevision().equals(intent.nextRoot())
                || attempts.intent(actual).isPresent() || reviews.isEmpty() || !reviews.getLast().encode().equals(review.encode()))
                return unknown("DEPLOYMENT_ASSISTED_OPERATION_CAS_UNCONFIRMED");
            for (var entry : current.entrySet())
                if (!journals.get(entry.getKey()).attemptConfirmed(review,
                    ReviewedPlanIdentity.from(entry.getValue().request()), entry.getValue().request().runtime()))
                    return unknown("DEPLOYMENT_ASSISTED_OPERATION_CHILD_UNCONFIRMED");
            if (root != null && !root.attemptConfirmed(review))
                return unknown("DEPLOYMENT_ASSISTED_OPERATION_ROOT_UNCONFIRMED");
            var result = new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(new Continuation(nextSession, current, prepared)), Optional.<Problem>empty());
            nextSession = null;
            return result;
        } catch (Exception | LinkageError | AssertionError unconfirmed) {
            return unknown("DEPLOYMENT_ASSISTED_OPERATION_UNCONFIRMED");
        } finally {
            if (nextSession != null) try {
                nextSession.close();
            } catch (Exception unconfirmed) { /* Original physical resources remain retained. / 原物理资源继续保留。 */ }
        }
    }

    /** Persists exact typed human consent before preparing any next tool environment. / 准备任一下一工具环境前持久化精确类型化人工同意。
     * @param previous actual old prepared build / 实际旧准备构建
     * @param corrected actual typed human review / 实际类型化人工审阅
     * @param proof complete closed old operation / 完整已关闭旧操作
     * @param graph original complete graph framing / 原完整图分界
     * @param task authentic original task / 真实原任务
     * @return complete consent for a distinct operation / 独立操作完整同意
     * @throws Exception if exact source, root or consent rejects / 精确来源、根或同意拒绝时 */
    private DeploymentPublishAttemptIntent intent(DeploymentStandardCandidateRunner.Prepared previous,
                                                  DeploymentAssistedBuildCorrection.Correction corrected, DeploymentPublishAttemptProof proof, String graph, TaskContext task) throws Exception {
        var layout = gold.debug.windowstolinux.mod.deployment.config.DeploymentConfigCapture.originalTaskLayout(context.tasks(), task);
        if (!previous.plan().request().layout().equals(layout) || !corrected.plan().request().layout().equals(layout))
            throw new IllegalArgumentException("Assisted publication correction changed original remote layout");
        String before = DeploymentAssistedBuildReviewCodec.encodeDescription(ReviewedPlanIdentity.description(previous.plan(), corrected.previousInputs()));
        String after = DeploymentAssistedBuildReviewCodec.encodeDescription(ReviewedPlanIdentity.description(corrected.plan(), corrected.nextInputs()));
        String nextRoot = ReviewedPlanIdentity.from(corrected.plan().request());
        if (!graph.isEmpty()) {
            var original = DeploymentAssistedBuildReviewCodec.decodeGraph(graph);
            var ids = new LinkedHashMap<>(original.identities());
            String id = original.namespaces().entrySet().stream().filter(value -> value.getValue().equals(previous.workspace().applicationId())).map(Map.Entry::getKey).findFirst().orElseThrow();
            ids.put(id, nextRoot);
            nextRoot = DeploymentReviewedPlanDigest.graphFrom(new DeploymentReviewedPlanDigest.GraphValues(original.applicationId(), original.healthOwner(), original.health(), original.order(), original.namespaces(), original.dependencies(), ids));
        }
        UUID operation = UUID.randomUUID();
        String proofSha = DeploymentPublishRestorationProof.digest(proof.encode());
        String exact = "previous=" + proof.reviewedRevision() + "\nnext=" + nextRoot + "\noperation=" + operation + "\nsource=" + previous.workspace().archiveSha256()
            + "\nunit=" + previous.workspace().applicationId() + "\ninputs=" + new TreeMap<>(corrected.nextInputs()) + "\nbranch=" + corrected.catalogBranch() + "\nproof=" + proofSha;
        String model = context.ai().configurationRevision(AiPurpose.LANGUAGE, task);
        approve(new ActionIntent("deployment.assisted.environment-recovery", List.of(previous.plan().request().server().id()), previous.workspace().applicationId(), nextRoot, exact,
            "Confirm the four typed inputs and fixed tools after verified original restoration"), task);
        return new DeploymentPublishAttemptIntent(loop.retriesSpent() + 1, proof.reviewedRevision(), nextRoot, proof.operation(), operation, graph,
            previous.workspace().applicationId(), before, after, proofSha, corrected.catalogBranch(), DeploymentPublishRestorationProof.digest(exact), model, Instant.now());
    }

    /** Prepares all units under the same task, original clocks and new explicit candidate operation. / 在同一任务、原时钟及新明确候选操作下准备全部单元。
     * @param plans complete newly reviewed plans / 完整新审阅计划
     * @param units original exact owners / 原精确归属
     * @param selected corrected component / 已纠正组件
     * @param intent actual durable consent / 实际持久同意
     * @param journals all original pending pointers / 所有原待定指针
     * @param session actual next session / 实际下一会话
     * @param task unchanged authentic task / 不变真实任务
     * @return complete actually observed selections / 完整实际观察选择
     * @throws Exception if any preparation or receipt is not confirmed / 任一准备或回执未确认时 */
    private Map<String, DeploymentStandardCandidateRunner.Prepared> prepare(Map<String, ReviewedDeploymentPlan> plans,
                                                                            Map<String, DeploymentUnitIdentity> units, String selected, DeploymentPublishAttemptIntent intent,
                                                                            Map<String, DeploymentPublishCleanupActionLog> journals, RemoteSession session, TaskContext task) throws Exception {
        var result = new LinkedHashMap<String, DeploymentStandardCandidateRunner.Prepared>();
        long required = 0, available = Long.MAX_VALUE;
        for (var entry : plans.entrySet()) {
            var old = loop.selection(units.get(entry.getKey()).id());
            var workspace = new DeploymentRemoteWorkspace(old.workspace().applicationId(), old.workspace().archiveSha256(), Optional.of(intent.nextOperation()), old.workspace().layout());
            int branch = entry.getKey().equals(selected) ? intent.catalogBranch() : old.branch();
            var prepared = candidates.prepare(entry.getValue(), session, context.interaction(), task, false, branch, old.budget(), workspace);
            if (prepared.outcome() != TaskResult.SUCCEEDED)
                throw new IllegalArgumentException("Next fixed environment remains unconfirmed");
            var actual = prepared.value().orElseThrow();
            if (!entry.getKey().equals(selected)
                && !actual.tools().selections().equals(old.tools().selections()))
                throw new IllegalArgumentException("Unselected original component tools changed before final review");
            if (actual.branch() != branch || !journals.get(entry.getKey()).attemptObserved(DeploymentPublishRestorationProof.digest(DeploymentToolchainBindingCodec.encode(actual.tools()))))
                throw new IllegalArgumentException("Next actual tool receipt changed its fixed branch");
            required = Math.addExact(required, actual.requiredSpace());
            available = Math.min(available, actual.availableBytes());
            result.put(entry.getKey(), actual);
        }
        if (required > available) throw new IllegalArgumentException("Complete original graph lacks candidate space");
        return result;
    }

    /** Requires actual changed tools or runtime and exact final fixed script approval before formal CAS. / 正式CAS前要求实际变化工具或运行时与精确最终固定脚本审批。
     * @param intent durable prior human consent / 持久原人工同意
     * @param proof complete original executed bindings / 完整原执行绑定
     * @param prepared actual newly observed selection / 实际新观察选择
     * @param task authentic unchanged task / 真实不变任务
     * @return full actual final review / 完整实际最终审阅
     * @throws Exception if actual change, model or exact approval is not confirmed / 实际变化、模型或精确审批未确认时 */
    private DeploymentPublishAttemptReview reviewed(DeploymentPublishAttemptIntent intent, DeploymentPublishAttemptProof proof,
                                                    DeploymentStandardCandidateRunner.Prepared prepared, TaskContext task) throws Exception {
        var layout = gold.debug.windowstolinux.mod.deployment.config.DeploymentConfigCapture.originalTaskLayout(context.tasks(), task);
        if (!DeploymentAssistedBuildReviewCodec.decodeDescription(intent.previousPlan()).layout().equals(layout)
            || !DeploymentAssistedBuildReviewCodec.decodeDescription(intent.nextPlan()).layout().equals(layout)
            || !prepared.plan().request().layout().equals(layout))
            throw new IllegalArgumentException("Assisted publication review changed original remote layout");
        var child = proof.children().stream().filter(value -> value.unitId().equals(intent.unitId())).findFirst().orElseThrow();
        String script = DeploymentPublishRestorationProof.digest(prepared.script());
        String tools = DeploymentToolchainBindingCodec.encode(prepared.tools());
        String exact = "root=" + intent.nextRoot() + "\noperation=" + intent.nextOperation() + "\nplan=" + DeploymentPublishRestorationProof.digest(intent.nextPlan())
            + "\nscript=" + script + "\ntools=" + DeploymentPublishRestorationProof.digest(tools);
        var review = new DeploymentPublishAttemptReview(intent.retry(), intent.previousRoot(), intent.nextRoot(), intent.previousOperation(), intent.nextOperation(), intent.originalGraph(),
            intent.unitId(), intent.previousPlan(), intent.nextPlan(), child.checkpoint().path("buildScript").asText(), script, child.checkpoint().path("buildTools").asText(), tools,
            intent.proofSha256(), DeploymentPublishRestorationProof.digest(exact), intent.approvalAction(), intent.modelRevision(), Instant.now().truncatedTo(ChronoUnit.MILLIS));
        approve(new ActionIntent("deployment.assisted.build-review", List.of(prepared.plan().request().server().id()), intent.unitId(), intent.nextRoot(), exact,
            "Confirm the new exact operation, complete runtime, fixed script and physically observed tools before any build"), task);
        if (!intent.modelRevision().equals(context.ai().configurationRevision(AiPurpose.LANGUAGE, task)))
            throw new IllegalArgumentException("Original frozen model route changed");
        return review;
    }

    /** Keeps host exact approval and the original frozen business policy in the same scope. / 在同一作用域保留宿主精确审批及原冻结业务策略。
     * @param intent actual bounded fixed action / 实际有界固定动作
     * @param task authentic original root / 真实原根
     * @throws ApiException if approval is not known successful / 审批未明确成功时 */
    private void approve(ActionIntent intent, TaskContext task) throws ApiException {
        var result = context.interaction().approve(intent, task);
        if (result.outcome() != TaskResult.SUCCEEDED)
            throw new ApiException(result.problem().orElseGet(() -> new Problem(ProblemCode.ACCESS_DENIED,
                "DEPLOYMENT_ASSISTED_OPERATION_DENIED", "Exact bounded operation approval was denied", Map.of("task", task.id().toString()), Optional.of(task.id()))));
    }

    /** Removes nonce and incidental evidence changes from the actual repeated failure identity. / 从实际重复失败身份中去除随机标识及附带依据变化。
     * @param proof complete restored original operation / 完整已恢复原操作
     * @param unit exact selected original unit / 精确选定原单元
     * @return stable executed failure binding / 稳定已执行失败绑定 */
    private static String failureBinding(DeploymentPublishAttemptProof proof, String unit) {
        var child = proof.children().stream().filter(value -> value.unitId().equals(unit)).findFirst().orElseThrow();
        return DeploymentPublishRestorationProof.digest(unit + "\n" + child.checkpoint().path("archiveSha256").asText() + "\n" + child.checkpoint().path("buildScript").asText()
            + "\n" + child.checkpoint().path("buildTools").asText() + "\n" + proof.failureCode() + "\n" + proof.failureEvent());
    }

    /** Preserves the actual already restored known failure without another publication. / 保留实际已恢复已知失败，不再次发布。
     * @param failure original known failure / 原已知失败
     * @return known stop / 已知停止 */
    private static OperationResult<Continuation> stopped(Problem failure) {
        return new OperationResult<>(TaskResult.FAILED, Optional.empty(), Optional.of(failure));
    }

    /** Retains the original formal execution and every unconfirmed resource. / 保留原正式执行及各未确认资源。
     * @param event stable public event / 稳定公开事件
     * @return explicit retained unknown / 明确保留未知 */
    private static OperationResult<Continuation> unknown(String event) {
        return new OperationResult<>(TaskResult.UNKNOWN, Optional.empty(), Optional.of(new Problem(ProblemCode.UNKNOWN,
            event, "Original bounded publication operation requires observation", Map.of(), Optional.empty())));
    }
}
