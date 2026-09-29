package gold.debug.windowstolinux.mod.deployment.service.standard.deploy.assistance;

import java.io.IOException;
import java.util.*;

import com.fasterxml.jackson.databind.JsonNode;
import gold.debug.windowstolinux.appapi.ai.AiPurpose;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.server.ServerRef;
import gold.debug.windowstolinux.appapi.task.*;
import gold.debug.windowstolinux.mod.deployment.ai.DeploymentAiJson;
import gold.debug.windowstolinux.mod.deployment.service.standard.deploy.input.DeploymentReviewField;
import gold.debug.windowstolinux.mod.deployment.source.DeploymentSourceBrowser;

/** Offers bounded advice only after a verified deterministic failure. / 仅在确定性步骤出现已证实失败后提供有界建议。 */
public final class DeploymentAssistedAdvisor {
    /** Technical fields that the standard planner must still validate. / 标准计划器仍须验证的技术字段。 */
    private static final Set<String> TECHNICAL = Set.of("type", "primary", "secondary", "version", "jvmTarget",
            "dependencies", "healthOwner", "port", "healthMode", "healthEndpoint", "expectedStatus");

    /** Fixed AI instruction; output has no command authority. / 固定 AI 指令；输出不具命令权限。 */
    private static final String POLICY = "You advise on one failed deployment checkpoint. Return only JSON with keys "
            + "decision (RETRY or STOP), reason, suggestions (object of offered field IDs to values). "
            + "Use only evidence supplied. Never invent commands, credentials, permissions, or an unoffered field.";

    /** Real platform and deployment AI adapter. / 真实平台与部署 AI 适配器。 */
    private final ModContext context;

    /** Strict deployment JSON adapter using the platform capability. / 使用平台能力的严格部署 JSON 适配器。 */
    private final DeploymentAiJson ai;

    /** Repeated failure bindings for this original task. / 原任务中重复的失败绑定。 */
    private final Set<String> seen = new HashSet<>();

    /** Overall decisions, unaffected by retry. / 不受重试影响的总决策次数。 */
    private int decisions;

    /** Explicit human answers always take priority over later model suggestions. / 人工明确答案始终优先于后续模型建议。 */
    private final Set<String> humanFields = new HashSet<>();

    /** An explicit user-approved manual continuation stops later AI recovery. / 经用户明确批准的人工继续将停止后续 AI 恢复。 */
    private boolean manual;

    /** Frozen routing revision for all decisions and host reviews. / 所有决策和宿主审核使用的冻结路由修订。 */
    private String revision;

    /** Proposed values await deterministic validation and exact host review. / 建议值仍待确定性校验及宿主精确审核。
     * @param retry whether another deterministic attempt was explicitly accepted / 是否明确接受再试一次确定性步骤
     * @param values checked technical suggestions / 已检查技术建议
     * @param reason bounded explanation / 有界说明 */
    public record Advice(boolean retry, Map<String, String> values, String reason) {
        /** Copies immutable advice. / 复制不可变建议。
         * @param retry accepted retry / 已接受重试
         * @param values checked suggestions / 已检查建议
         * @param reason explanation / 说明 */
        public Advice {
            values = Map.copyOf(values);
            Objects.requireNonNull(reason);
        }
    }

    /** Binds the active Mod without invoking a model. / 绑定活动 Mod 而不调用模型。
     * @param context actual platform context / 实际平台上下文 */
    public DeploymentAssistedAdvisor(ModContext context) {
        this.context = Objects.requireNonNull(context);
        this.ai = new DeploymentAiJson(context);
    }

    /** Records fields actually confirmed by a human reviewer. / 记录由人工审阅者实际确认的字段。
     * @param answers exact field IDs and bounded nonsecret values / 精确字段与有界非秘密值 */
    public void humanAnswers(Map<String, String> answers) {
        for (var answer : answers.entrySet()) {
            if (answer.getKey().length() > 128 || answer.getValue().length() > 4096)
                throw new IllegalArgumentException("Invalid assisted human answer");
            humanFields.add(answer.getKey());
        }
    }

    /** Returns whether a value belongs to the human rather than AI. / 返回某值是否属于人工而非 AI。
     * @param field qualified field ID / 完整字段标识
     * @return true for confirmed human input / 人工确认时为真 */
    public boolean humanSupplied(String field) {
        return humanFields.contains(field);
    }

    /** Uses only frozen read-only source observations for an incomplete technical review. / 仅利用冻结只读源码观察补全不完整的技术审阅。
     * @param source original read-only browser / 原只读浏览器
     * @param fields currently offered fields / 当前提供字段
     * @param facts bounded deterministic facts / 有界确定性事实
     * @param task original task / 原任务
     * @return source-backed suggestions and unresolved human questions / 有来源依据的建议与未决人工问题
     * @throws Exception on unavailable, unknown or invalid model evidence / 模型依据不可用、不明或无效时 */
    public DeploymentAssistedAnalysis.Advice analyze(DeploymentSourceBrowser source, List<DeploymentReviewField> fields,
            Map<String, String> facts, TaskContext task) throws Exception {
        if (manual || decisions >= 3)
            return new DeploymentAssistedAnalysis.Advice(Map.of(), Map.of(), "Assisted budget exhausted", List.of());
        var pending = fields.stream().filter(field -> allows(field.id()) && !humanFields.contains(field.id())).toList();
        if (pending.isEmpty())
            return new DeploymentAssistedAnalysis.Advice(Map.of(), Map.of(), "No technical field is pending",
                    List.of());
        decisions++;
        return new DeploymentAssistedAnalysis(context, source, requireRevision(task)).analyze(pending, facts, task);
    }

    /** Admits an AI preflight only for a deterministically incomplete technical review. / 仅为确定性不完整技术审阅准入 AI 预检。
     * @param source original read-only browser / 原只读浏览器
     * @param missing actual missing or uncertain technical fields / 实际缺失或不确定技术字段
     * @param facts bounded observed nonsecret facts / 有界已观察非秘密事实
     * @param task original task / 原任务
     * @return source-backed values and unresolved questions / 有来源依据的值与未决问题
     * @throws Exception on unconfirmed AI result / AI 结果不明时 */
    public DeploymentAssistedAnalysis.Advice preflight(DeploymentSourceBrowser source,
            List<DeploymentReviewField> missing, Map<String, String> facts, TaskContext task) throws Exception {
        return analyze(source, missing.stream().filter(field -> field.value().isBlank()).toList(), facts, task);
    }

    /** Requires an exact host decision before a model failure becomes manual continuation. / 在模型故障转为人工继续之前要求宿主精确决定。
     * @param server original selected target / 原所选目标
     * @param phase fixed checkpoint / 固定节点
     * @param failure actual model failure code / 真实模型失败代码
     * @param sourceRevision frozen source revision / 冻结来源修订
     * @param task original task / 原任务
     * @return whether manual continuation was explicitly accepted / 是否明确接受人工继续
     * @throws Exception on uncertain host review / 宿主审阅不明时 */
    public boolean manualContinuation(ServerRef server, String phase, Problem failure, String sourceRevision,
            TaskContext task) throws Exception {
        if (manual)
            return true;
        String facts = "phase=" + phase + "\nsource=" + sourceRevision + "\nproblem=" + failure.eventCode();
        var review = context.interaction()
                .approve(
                        new ActionIntent("deployment.assisted.manual-continuation", List.of(server.id()), server.id(),
                                sourceRevision, facts, "Review the actual model failure before continuing manually"),
                        task);
        if (review.outcome() == Outcome.UNKNOWN || review.outcome() == Outcome.CANCELLED)
            throw new DeploymentAiJson.CapabilityOutcomeException(review.outcome(), review.problem());
        manual = review.outcome() == Outcome.SUCCEEDED;
        return manual;
    }

    /** Checks one real failure and requests exact host review before a bounded retry. / 检查真实失败，并在有界重试前请求宿主精确审核。
     * @param server original admitted server / 原已准入服务器
     * @param phase fixed failed checkpoint / 固定失败节点
     * @param failure observed deterministic result / 已观察的确定性结果
     * @param sourceRevision immutable source digest / 不可变来源摘要
     * @param fields currently offered technical fields / 当前提供的技术字段
     * @param task original task / 原任务
     * @return approved bounded advice or a stop decision / 已批准的有界建议或停止决定
     * @throws Exception on unknown outcome, cancelled task, invalid advice or uncertain review / 未知结果、取消、无效建议或审核不明时 */
    public Advice afterFailure(ServerRef server, String phase, OperationResult<?> failure, String sourceRevision,
            List<DeploymentReviewField> fields, TaskContext task) throws Exception {
        Objects.requireNonNull(server);
        Objects.requireNonNull(task);
        if (failure.outcome() == Outcome.UNKNOWN)
            throw new DeploymentAiJson.CapabilityOutcomeException(failure.outcome(), failure.problem());
        if (failure.outcome() != Outcome.FAILED || task.cancellation().requested())
            return new Advice(false, Map.of(), "No confirmed retryable failure");
        if (!Set.of("ANALYSIS", "PREFLIGHT", "BUILD", "HEALTH", "ROLLBACK_VERIFIED").contains(phase))
            throw new IllegalArgumentException("Unsupported assisted checkpoint");
        if (!sourceRevision.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("Invalid frozen source revision");
        if (manual || decisions >= 3)
            return new Advice(false, Map.of(), "Assisted decision budget exhausted");
        String event = failure.problem().orElseThrow().eventCode();
        String binding = sourceRevision + ":" + phase + ":" + event;
        if (!seen.add(binding))
            return new Advice(false, Map.of(), "Repeated failure evidence");
        var offered = new LinkedHashMap<String, DeploymentReviewField>();
        for (var field : fields)
            if (TECHNICAL.contains(field.id().substring(field.id().lastIndexOf('/') + 1))
                    && !humanFields.contains(field.id()))
                offered.put(field.id(), field);
        Map<String, Object> facts = Map.of("phase", phase, "sourceRevision", sourceRevision, "eventCode", event,
                "problemCode", failure.problem().orElseThrow().code().name(), "fields", offered.values().stream()
                        .map(f -> Map.of("id", f.id(), "value", f.value(), "choices", f.choices())).toList());
        decisions++;
        String frozen = requireRevision(task);
        JsonNode answer = ai.ask(AiPurpose.LANGUAGE, POLICY, facts, frozen, task);
        var keys = new HashSet<String>();
        answer.fieldNames().forEachRemaining(keys::add);
        if (!keys.equals(Set.of("decision", "reason", "suggestions")))
            throw new IOException("Invalid assisted failure decision shape");
        String decision = DeploymentAiJson.text(answer, "decision", 16);
        String reason = DeploymentAiJson.text(answer, "reason", 2000);
        if (decision.equals("STOP"))
            return new Advice(false, Map.of(), reason);
        if (!decision.equals("RETRY"))
            throw new IOException("Unsupported assisted decision");
        JsonNode suggestions = answer.get("suggestions");
        if (suggestions == null || !suggestions.isObject() || suggestions.size() > 16)
            throw new IOException("Invalid assisted suggestion set");
        var values = new LinkedHashMap<String, String>();
        suggestions.fields().forEachRemaining(entry -> {
            var field = offered.get(entry.getKey());
            if (field == null || !entry.getValue().isTextual() || !accepts(field, entry.getValue().textValue()))
                throw new IllegalArgumentException("Unsupported assisted suggestion");
            values.put(entry.getKey(), entry.getValue().textValue());
        });
        String exact = "checkpoint=" + phase + "\nsource=" + sourceRevision + "\nproblem=" + event + "\nvalues="
                + new TreeMap<>(values);
        var intent = new ActionIntent("deployment.assisted.retry", List.of(server.id()), server.id(), sourceRevision,
                exact, "Review suggested values and retry only the confirmed failed deployment checkpoint");
        var review = context.interaction().approve(intent, task);
        if (review.outcome() != Outcome.SUCCEEDED)
            throw new DeploymentAiJson.CapabilityOutcomeException(review.outcome(), review.problem());
        requireRevision(task);
        return new Advice(true, values, reason);
    }

    /** Freezes once and rejects a later model, policy or routing change. / 冻结一次，并拒绝后续模型、策略或路由变化。
     * @param task original task / 原任务
     * @return unchanged platform revision / 未变化的平台修订
     * @throws ApiException if the binding cannot be verified / 无法验证绑定时 */
    private String requireRevision(TaskContext task) throws ApiException {
        String current = context.ai().configurationRevision(AiPurpose.LANGUAGE, task);
        if (revision == null)
            revision = current;
        if (!revision.equals(current))
            throw new ApiException(new Problem(ProblemCode.ACCESS_DENIED, "DEPLOYMENT_ASSISTED_CONFIGURATION_CHANGED",
                    "Assisted configuration changed", Map.of(), Optional.empty()));
        return revision;
    }

    /** Checks only a technical suggestion shape; standard validation still decides semantics. / 仅检查技术建议形状；语义仍由标准校验决定。
     * @param field offered field / 已提供字段
     * @param value proposed value / 建议值
     * @return whether shape is permitted / 格式是否允许 */
    static boolean accepts(DeploymentReviewField field, String value) {
        if (value.isBlank() || value.length() > 4096 || value.chars().anyMatch(Character::isISOControl)
                || value.matches(".*[;$`|&<>].*") || value.contains("..") || value.contains("\\"))
            return false;
        if (!field.choices().isEmpty() && !field.choices().contains(value))
            return false;
        String key = field.id().substring(field.id().lastIndexOf('/') + 1);
        if (key.equals("port")) {
            try {
                int port = Integer.parseInt(value);
                return port > 0 && port <= 65535;
            } catch (NumberFormatException invalid) {
                return false;
            }
        }
        if (key.equals("expectedStatus"))
            return value.matches("[1-5][0-9]{2}");
        if (key.equals("healthEndpoint"))
            return value.startsWith("http://127.0.0.1:") || value.startsWith("http://localhost:");
        return true;
    }

    /** Restricts AI completion to old technical field names. / 将 AI 补全限制于旧技术字段名。
     * @param id component-qualified field / 带组件前缀的字段
     * @return whether advice may mention the field / 建议是否可提及该字段 */
    public static boolean allows(String id) {
        return TECHNICAL.contains(id.substring(id.lastIndexOf('/') + 1));
    }
}
