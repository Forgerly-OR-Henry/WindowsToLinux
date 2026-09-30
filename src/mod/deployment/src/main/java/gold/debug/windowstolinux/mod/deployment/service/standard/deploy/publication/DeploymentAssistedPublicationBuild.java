package gold.debug.windowstolinux.mod.deployment.service.standard.deploy.publication;

import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;
import com.fasterxml.jackson.databind.ObjectMapper;
import gold.debug.windowstolinux.appapi.ai.AiPurpose;
import gold.debug.windowstolinux.appapi.linux.RemoteSession;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.task.*;
import gold.debug.windowstolinux.mod.deployment.db.*;
import gold.debug.windowstolinux.mod.deployment.service.core.model.DeploymentUnitIdentity;
import gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentToolchainBindingCodec;
import gold.debug.windowstolinux.mod.deployment.service.standard.deploy.assistance.DeploymentAssistedBuildCorrection;
import gold.debug.windowstolinux.mod.deployment.service.standard.deploy.build.*;
import gold.debug.windowstolinux.mod.deployment.service.standard.deploy.contract.ReviewedDeploymentPlan;
import gold.debug.windowstolinux.mod.deployment.service.standard.deploy.plan.ReviewedPlanIdentity;

/** Executes only confirmed build corrections inside one original candidate, formal execution and bounded retry budget. / 仅在一个原候选、正式执行及有界重试预算内执行已确认构建纠正。 */
final class DeploymentAssistedPublicationBuild {
    /** Original frozen Mod scope. / 原冻结Mod作用域。 */
    private final ModContext context;
    /** Original formal execution repository. / 原正式执行仓库。 */
    private final DeploymentExecutionRepository executions;
    /** Original candidate implementation. / 原候选实现。 */
    private final DeploymentStandardCandidateExecutor candidates;
    /** Actual consumed retries shared by every component of this publication. / 本次发布所有组件共用的实际已消费重试次数。 */
    private int retries;
    /** Repeated actual executed input and failure identities. / 重复的实际执行输入及失败身份。 */
    private final Set<String> failures = new HashSet<>();
    /** Last actual selection for each original unit, carrying its unchanged execution clock. / 各原单元最近实际选择，承载其不变执行时钟。 */
    private final Map<String, DeploymentStandardCandidateExecutor.Prepared> selections = new LinkedHashMap<>();

    /** Binds existing implementations without submitting another task. / 绑定既有实现，不提交另一项任务。
     * @param context original Mod scope / 原Mod作用域
     * @param candidates original candidate executor / 原候选执行器 */
    DeploymentAssistedPublicationBuild(ModContext context, DeploymentStandardCandidateExecutor candidates) {
        this.context = Objects.requireNonNull(context); this.candidates = Objects.requireNonNull(candidates);
        executions = new DeploymentExecutionRepository(context.db());
    }

    /** Carries the actual last formally confirmed plan together with the observed build outcome. / 将实际最近正式确认计划与已观察构建结果一同传递。
     * @param plan last formally confirmed source-bound plan / 最近正式确认来源绑定计划
     * @param result actual candidate build result / 实际候选构建结果 */
    record Completed(ReviewedDeploymentPlan plan, OperationResult<DeploymentVerifiedBuildResult> result) {}

    /** Charges an original minimum-version fallback to the same bounded retry allowance. / 将原最低版本回退计入同一有界重试额度。
     * @param prepared actually prepared selection / 实际准备选择
     * @return whether the cumulative allowance remains valid / 累计额度是否仍有效 */
    boolean prepared(DeploymentStandardCandidateExecutor.Prepared prepared) { retries += prepared.branch(); return retries <= 2; }

    /** Gates the original fixed preflight fallback before it can consume another retry. / 原固定预检回退消费另一重试前实施门禁。
     * @return whether one shared retry remains / 是否仍有一次共用重试 */
    boolean fallbackAvailable() { return retries < 2; }

    /** Reads the actual original shared allowance without starting another loop. / 读取实际原共用额度，不开启另一循环。
     * @return actual consumed corrections / 实际已消费纠正 */
    int retriesSpent() { return retries; }

    /** Retains the physically observed last selection instead of reconstructing a new budget. / 保留物理观察最近选择，不重建新预算。
     * @param unit exact original unit / 精确原单元
     * @return original actual preparation / 原实际准备 */
    DeploymentStandardCandidateExecutor.Prepared selection(String unit) { return Objects.requireNonNull(selections.get(unit)); }

    /** Charges one distinct explicitly reviewed operation to the same global bound. / 将一次独立明确审阅操作计入同一全局边界。
     * @param binding actual executed input and known failure / 实际执行输入及已知失败
     * @return whether the original correction can proceed / 原纠正是否可推进 */
    boolean operation(String binding) { if (retries >= 2 || !failures.add(binding)) return false; retries++; return true; }

    /** Builds once, then admits at most two genuinely changed corrections after complete physical failure receipts. / 构建一次，仅在完整物理失败回执后准入最多两次真实变化纠正。
     * @param initial original installed selection / 原安装选择
     * @param correction exact human typed correction callback / 精确人工类型化纠正回调
     * @param unit exact original managed owner / 精确原受管归属
     * @param journal original child checkpoint / 原子检查点
     * @param root optional original graph checkpoint / 可选原图检查点
     * @param graph complete current graph framing or empty / 完整当前图分界或空值
     * @param session original borrowed connection / 原借用连接
     * @param task authentic original task / 真实原任务
     * @return last actual plan and observed outcome / 最近实际计划及已观察结果
     * @throws Exception if original persistence or review remains unconfirmed / 原持久确认或审阅仍不明时 */
    Completed build(DeploymentStandardCandidateExecutor.Prepared initial, DeploymentAssistedBuildCorrection correction,
            DeploymentUnitIdentity unit, DeploymentPublicationCleanupJournal journal, DeploymentGraphPublicationJournal root,
            Supplier<String> graph, RemoteSession session, TaskContext task) throws Exception {
        var current = initial;
        selections.put(unit.id(), current);
        if (!journal.buildStarting(ReviewedPlanIdentity.from(current.plan().request()), sha(current.script()), sha(DeploymentToolchainBindingCodec.encode(current.tools()))))
            return new Completed(current.plan(), uncertain("DEPLOYMENT_ASSISTED_BUILD_BINDING_UNCONFIRMED"));
        var result = candidates.build(current, session, task, false);
        while (result.outcome() == Outcome.FAILED && retries < 2 && current.remainingSeconds() > 60 && !task.cancellation().requested()) {
            var failure = result.problem().orElseThrow();
            if (!eligible(failure)) break;
            var inactive = candidates.verifyRetained(current, session, task);
            if (inactive.outcome() != Outcome.SUCCEEDED) return new Completed(current.plan(), uncertain("DEPLOYMENT_ASSISTED_BUILD_EXIT_UNCONFIRMED"));
            if (!journal.buildFailed(ReviewedPlanIdentity.from(current.plan().request()), sha(evidence(failure))))
                return new Completed(current.plan(), uncertain("DEPLOYMENT_ASSISTED_FAILURE_BINDING_UNCONFIRMED"));
            String actualBinding = sha(current.script() + "\n" + DeploymentToolchainBindingCodec.encode(current.tools()) + "\n" + evidence(failure));
            if (!failures.add(actualBinding)) break;
            var diagnosis = new LinkedHashMap<>(failure.context()); diagnosis.put("inputBinding", actualBinding);
            var boundFailure = new Problem(failure.code(), failure.eventCode(), failure.message(), diagnosis, failure.diagnosticId());
            var input = correction.review(current.plan(), boundFailure, task);
            if (input.outcome() != Outcome.SUCCEEDED) {
                if (input.outcome() == Outcome.UNKNOWN || input.outcome() == Outcome.CANCELLED)
                    return new Completed(current.plan(), new OperationResult<>(Outcome.UNKNOWN, Optional.empty(), input.problem()));
                break;
            }
            var corrected = input.value().orElseThrow();
            var intent = intent(current, corrected, failure, graph.get(), task);
            var original = executions.findForTask(task).orElseThrow();
            if (!journal.actualBuild(intent)) return new Completed(current.plan(), uncertain("DEPLOYMENT_ASSISTED_EXECUTED_INPUT_CHANGED"));
            executions.recordBuildIntent(task, original.identity().executionId(), intent, Instant.now());
            retries++;
            if (!journal.recoveryStarting(intent)) return new Completed(current.plan(), uncertain("DEPLOYMENT_ASSISTED_ENVIRONMENT_BOUNDARY_UNCONFIRMED"));
            var recovered = candidates.recoverSelection(current, corrected.plan(), corrected.catalogBranch(), session, context.interaction(), task);
            if (recovered.outcome() != Outcome.SUCCEEDED) return new Completed(current.plan(), uncertain("DEPLOYMENT_ASSISTED_ENVIRONMENT_UNCONFIRMED"));
            var next = recovered.value().orElseThrow();
            if (!journal.recoveryObserved(sha(DeploymentToolchainBindingCodec.encode(next.tools()))))
                return new Completed(current.plan(), uncertain("DEPLOYMENT_ASSISTED_ENVIRONMENT_RECEIPT_UNCONFIRMED"));
            var review = reviewed(intent, next, task);
            if (review == null) break;
            if (!journal.reviewPending(intent) || root != null && !root.reviewPending(intent))
                return new Completed(current.plan(), uncertain("DEPLOYMENT_ASSISTED_REVIEW_POINTER_UNCONFIRMED"));
            executions.adoptBuildReview(task, original.identity().executionId(), review, Instant.now());
            var confirmed = executions.findForTask(task).orElseThrow();
            var chain = executions.buildReviews(confirmed);
            if (!confirmed.identity().reviewedRevision().equals(review.nextRoot()) || chain.isEmpty() || !chain.getLast().encode().equals(review.encode())
                    || !journal.reviewConfirmed(review) || root != null && !root.reviewConfirmed(review))
                return new Completed(current.plan(), uncertain("DEPLOYMENT_ASSISTED_REVIEW_CAS_UNCONFIRMED"));
            current = next;
            selections.put(unit.id(), current);
            if (!journal.buildStarting(ReviewedPlanIdentity.from(current.plan().request()), sha(current.script()), sha(DeploymentToolchainBindingCodec.encode(current.tools()))))
                return new Completed(current.plan(), uncertain("DEPLOYMENT_ASSISTED_BUILD_BINDING_UNCONFIRMED"));
            result = candidates.rebuild(current, session, task);
        }
        return new Completed(current.plan(), result);
    }

    /** Forms complete source and typed consent before any installation effect. / 任一安装效果前形成完整来源及类型化同意。
     * @param previous actual failed selection / 实际失败选择
     * @param next actual human correction / 实际人工纠正
     * @param failure complete confirmed receipt / 完整已确认回执
     * @param graph complete current graph or empty / 完整当前图或空值
     * @param task original task / 原任务
     * @return full exactly approved intent / 完整精确审批意图
     * @throws Exception if original formal scope or human consent rejects / 原正式范围或人工同意拒绝时 */
    private DeploymentAssistedBuildIntent intent(DeploymentStandardCandidateExecutor.Prepared previous, DeploymentAssistedBuildCorrection.Correction next,
            Problem failure, String graph, TaskContext task) throws Exception {
        String before = DeploymentAssistedBuildReviewCodec.encodeDescription(ReviewedPlanIdentity.description(previous.plan(), next.previousInputs()));
        String after = DeploymentAssistedBuildReviewCodec.encodeDescription(ReviewedPlanIdentity.description(next.plan(), next.nextInputs()));
        var original = executions.findForTask(task).orElseThrow();
        String nextRoot = ReviewedPlanIdentity.from(next.plan().request());
        if (!graph.isEmpty()) {
            var framed = DeploymentAssistedBuildReviewCodec.decodeGraph(graph); var ids = new LinkedHashMap<>(framed.identities());
            String component = framed.namespaces().entrySet().stream().filter(entry -> entry.getValue().equals(previous.plan().request().facts().applicationId())).map(Map.Entry::getKey).findFirst().orElseThrow();
            ids.put(component, nextRoot);
            nextRoot = DeploymentReviewedPlanDigest.graphFrom(new DeploymentReviewedPlanDigest.GraphValues(framed.applicationId(), framed.healthOwner(), framed.health(), framed.order(), framed.namespaces(), framed.dependencies(), ids));
        }
        String model = context.ai().configurationRevision(AiPurpose.LANGUAGE, task);
        String exact = "previous=" + original.identity().reviewedRevision() + "\nnext=" + nextRoot + "\nsource=" + previous.plan().request().archive().sha256()
                + "\nunit=" + previous.plan().request().facts().applicationId() + "\ncatalogBranch=" + next.catalogBranch() + "\ninputs=" + new TreeMap<>(next.nextInputs())
                + "\nconfiguration=" + previous.plan().request().configuration().sha256() + "\nfailure=" + failure.eventCode();
        approve(new ActionIntent("deployment.assisted.environment-recovery", List.of(previous.plan().request().server().id()), previous.plan().request().facts().applicationId(), nextRoot,
                exact, "Confirm only the four typed corrections and fixed catalog recovery before any installation"), task);
        return new DeploymentAssistedBuildIntent(executions.buildReviews(original).size() + 1, original.identity().reviewedRevision(), nextRoot, graph,
                previous.plan().request().facts().applicationId(), before, after, previous.script(), DeploymentToolchainBindingCodec.encode(previous.tools()),
                evidence(failure), next.catalogBranch(), sha(exact), model, Instant.now());
    }

    /** Requires exact final script and actual tool consent before formal CAS. / 正式CAS前要求精确最终脚本及实际工具同意。
     * @param intent original persisted recovery consent / 原持久恢复同意
     * @param next actual recovered selection / 实际恢复选择
     * @param task original task / 原任务
     * @return complete observed formal review / 完整已观察正式审阅
     * @throws Exception if tools did not change or exact consent fails / 工具未变化或精确同意失败时 */
    private DeploymentAssistedBuildReview reviewed(DeploymentAssistedBuildIntent intent, DeploymentStandardCandidateExecutor.Prepared next, TaskContext task) throws Exception {
        String tools = DeploymentToolchainBindingCodec.encode(next.tools());
        var before = DeploymentAssistedBuildReviewCodec.decodeDescription(intent.previousPlan());
        var after = DeploymentAssistedBuildReviewCodec.decodeDescription(intent.nextPlan());
        var oldTools = DeploymentToolchainBindingCodec.decode(intent.previousTools());
        boolean corrected = !before.inputs().equals(after.inputs()) && !before.runtime().equals(after.runtime());
        boolean changed = !oldTools.selections().stream().map(value -> List.of(value.requirement().ecosystem().name(), value.requirement().purpose().name(), value.version().text(), value.directory(), value.origin().name(), value.source(), value.sha256())).toList()
                .equals(next.tools().selections().stream().map(value -> List.of(value.requirement().ecosystem().name(), value.requirement().purpose().name(), value.version().text(), value.directory(), value.origin().name(), value.source(), value.sha256())).toList());
        if ((!corrected && !changed) || intent.previousScript().equals(next.script()) && intent.previousTools().equals(tools)) return null;
        String exact = "previous=" + intent.previousRoot() + "\nnext=" + intent.nextRoot() + "\nplan=" + sha(intent.nextPlan()) + "\nscript=" + sha(next.script()) + "\ntools=" + sha(tools);
        approve(new ActionIntent("deployment.assisted.build-review", List.of(next.plan().request().server().id()), intent.unitId(), intent.nextRoot(), exact,
                "Confirm the exact corrected plan, fixed script and physically observed tools before rebuilding the original candidate"), task);
        if (!intent.modelRevision().equals(context.ai().configurationRevision(AiPurpose.LANGUAGE, task))) throw new ApiException(new Problem(ProblemCode.ACCESS_DENIED,
                "DEPLOYMENT_ASSISTED_CONFIGURATION_CHANGED", "Original review configuration changed", Map.of("task", task.id().toString()), Optional.of(task.id())));
        return new DeploymentAssistedBuildReview(intent.attempt(), intent.previousRoot(), intent.nextRoot(), intent.originalGraph(), intent.unitId(), intent.previousPlan(), intent.nextPlan(),
                intent.previousScript(), next.script(), intent.previousTools(), tools, DeploymentAssistedBuildReview.failureBinding(intent.previousPlan(), intent.previousScript(), intent.previousTools(), intent.failureEvidence()),
                intent.failureEvidence(), sha(exact), intent.approvalAction(), intent.modelRevision(), Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS));
    }

    /** Preserves host review outcomes without manufacturing a grant or retry. / 保留宿主审阅结果，不虚构授权或重试。
     * @param intent exact fixed business review / 精确固定业务审阅
     * @param task authentic original task / 真实原任务
     * @throws ApiException if exact consent is denied, cancelled or unknown / 精确同意被拒、取消或未知时 */
    private void approve(ActionIntent intent, TaskContext task) throws ApiException {
        var result = context.interaction().approve(intent, task);
        if (result.outcome() != Outcome.SUCCEEDED) throw new ApiException(result.problem().orElseGet(() -> new Problem(ProblemCode.ACCESS_DENIED,
                "DEPLOYMENT_ASSISTED_REVIEW_DENIED", "Original fixed review was denied", Map.of("task", task.id().toString()), Optional.of(task.id()))));
    }

    /** Recognizes only physically evidenced registered build failures. / 仅识别具物理依据的已登记构建失败。
     * @param failure actual public failure / 实际公开失败
     * @return whether the complete receipt is eligible / 完整回执是否可准入 */
    private static boolean eligible(Problem failure) {
        return Set.of("DEPLOYMENT_BUILD_FAILED", "DEPLOYMENT_BUILD_LIMIT", "DEPLOYMENT_BUILD_COMPATIBLE_FAILURE", "DEPLOYMENT_BUILD_CANDIDATES_EXHAUSTED").contains(failure.eventCode())
                && failure.context().keySet().containsAll(Set.of("exit", "outputBytes", "outputSha256", "diagnostic", "uid", "engine", "tool"));
    }

    /** Encodes the exact already confirmed physical receipt with no diagnostic text duplication. / 编码精确已确认物理回执，不重复诊断文本。
     * @param failure actual physical failure / 实际物理失败
     * @return full bounded strict receipt / 完整有界严格回执 */
    private static String evidence(Problem failure) {
        var values = failure.context();
        return new ObjectMapper().createObjectNode().put("formatVersion", 1).put("exit", Integer.parseInt(values.get("exit"))).put("bytes", Long.parseLong(values.get("outputBytes")))
                .put("outputSHA", values.get("outputSha256")).put("uid", values.get("uid")).put("engine", values.get("engine")).put("tool", values.get("tool"))
                .put("diagnosticSHA", sha(values.get("diagnostic"))).put("event", failure.eventCode()).toString();
    }

    /** Hashes exact non-secret framing. / 哈希精确非秘密分界。
     * @param value original text / 原文本
     * @return lowercase SHA / 小写SHA */
    private static String sha(String value) {
        try { return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException unavailable) { throw new IllegalStateException("JDK SHA-256 unavailable", unavailable); }
    }

    /** Retains every original physical resource after an unconfirmed transition. / 未确认转换后保留每项原物理资源。
     * @param event stable public event / 稳定公开事件
     * @return retained unknown build / 保留未知构建 */
    private static OperationResult<DeploymentVerifiedBuildResult> uncertain(String event) {
        return new OperationResult<>(Outcome.UNKNOWN, Optional.empty(), Optional.of(new Problem(ProblemCode.UNKNOWN, event, "Original assisted build requires observation", Map.of(), Optional.empty())));
    }
}
