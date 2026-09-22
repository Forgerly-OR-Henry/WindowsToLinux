package gold.debug.windowstolinux.shared.standard.deploy.assistance;

import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;

import gold.debug.windowstolinux.shared.deploy.contract.AutomaticDeploymentInteraction;
import gold.debug.windowstolinux.shared.model.deployment.*;
import gold.debug.windowstolinux.shared.model.message.LocalizedMessage;

/** Evidence-backed analysis and checkpoint advice, without command authority. / 基于证据的分析及节点建议，不持有命令权限。 */
public final class AssistedDeploymentAdvisor {
    /** Explicit deployment-purpose gateway. / 显式部署用途网关。 */
    private final AssistedAdvicePort ai;

    /** Human decisions for uncertainty and provider failure. / 不确定性及提供者失败时的人工决定。 */
    private final AutomaticDeploymentInteraction interaction;

    /** Visible explanatory output. / 可见解释输出。 */
    private final Consumer<LocalizedMessage> progress;

    /** True only after explicit user acceptance of manual continuation. / 仅在用户明确同意人工继续后为真。 */
    private boolean manual;

    /** Independent read-only analysis model. / 独立只读分析模型。 */
    private final AssistedAnalysisModelPort analysisModel;

    /** Shared task decision allowance. / 共享任务决策额度。 */
    private final AssistedDecisionBudget budget;

    /** Bound frozen source analysis. / 已绑定冻结源码分析。 */
    private AssistedAnalysisSession analysis;

    /** Bounded nonsecret facts supplied by the standard workflow. / 标准流程提供的有界非秘密事实。 */
    private final Map<String, String> facts = new LinkedHashMap<>();

    /** Last fully checked preflight input. / 上次完整检查的预检输入。 */
    private Map<String, String> checkedPreflight;

    /** Fields explicitly answered by the user rather than inferred. / 用户明确回答而非推断得到的字段。 */
    private final Set<String> humanFields = new HashSet<>();
    /** Binds one task's fixed assistance policy. / 绑定一个任务的固定辅助策略。
     * @param ai purpose gateway / 用途网关
     * @param interaction human interaction / 人工交互
     * @param progress progress sink / 进度端口
     */
    public AssistedDeploymentAdvisor(AssistedAdvicePort ai, AutomaticDeploymentInteraction interaction,
            Consumer<LocalizedMessage> progress) {
        this(ai, interaction, progress, null, new AssistedDecisionBudget());
    }

    /** Binds independent analysis and checkpoint advice to one shared allowance. / 将独立分析及节点建议绑定至共享额度。
     * @param ai fixed-checkpoint advice / 固定节点建议
     * @param interaction human input / 人工输入
     * @param progress transient progress / 临时进度
     * @param analysisModel source-only model / 纯源码模型
     * @param budget task allowance / 任务额度
     */
    public AssistedDeploymentAdvisor(AssistedAdvicePort ai, AutomaticDeploymentInteraction interaction,
            Consumer<LocalizedMessage> progress, AssistedAnalysisModelPort analysisModel,
            AssistedDecisionBudget budget) {
        this.ai = ai;
        this.interaction = interaction;
        this.progress = progress;
        this.analysisModel = analysisModel;
        this.budget = budget;
    }

    /** Supplies only the frozen read capability to analysis. / 仅向分析提供冻结只读能力。
     * @param source read-only snapshot / 只读快照
     */
    public void source(gold.debug.windowstolinux.shared.source.browse.SourceReadPort source) {
        if (analysisModel != null)
            analysis = new AssistedAnalysisSession(source, analysisModel, budget);
    }

    /** Updates an observed nonsecret standard fact. / 更新已观察的非秘密标准事实。
     * @param key trusted fact identity / 可信事实标识
     * @param value bounded sanitized observation / 有界脱敏观察
     */
    public void fact(String key, String value) {
        if (value.length() > 4096)
            value = value.substring(0, 4096);
        facts.put(key, gold.debug.windowstolinux.shared.ai.redaction.AgentEvidenceText.redact(value));
    }

    /** Completes evidenced supported parameters; missing business intent remains human. / 补齐有证据的受支持参数，缺失业务意图仍交人工。
     * @param fields requested fields / 请求字段
     * @return checked field values / 已检查字段值
     */
    public Map<String, String> complete(List<DeploymentInputField> fields) {
        if (fields.isEmpty())
            return Map.of();
        var adopted = new LinkedHashMap<>(suggest(fields));
        var pending = fields.stream().filter(field -> !adopted.containsKey(field.id())).toList();
        var answers = gold.debug.windowstolinux.shared.standard.deploy.input.DeploymentInputAnswers.ask(pending,
                interaction);
        humanFields.addAll(answers.keySet());
        adopted.putAll(answers);
        return Map.copyOf(adopted);
    }

    /** Retains user-input priority during later correction. / 在后续修正时保留用户输入优先级。
     * @param field qualified field identity / 完整字段标识
     * @return whether the user supplied the value / 值是否由用户提供
     */
    public boolean humanSupplied(String field) {
        return humanFields.contains(field);
    }

    /** Stops automatic recovery after manual fallback or budget exhaustion. / 人工接管或预算耗尽后停止自动恢复。
     * @return whether another assisted recovery decision is admitted / 是否准入下一次辅助恢复决策
     */
    public boolean canRecover() {
        return !manual && budget.remaining() > 0;
    }

    /** Proposes source-backed changes without executing or silently requesting new permissions. / 提议有源码依据的修改，不执行或静默请求新权限。
     * @param fields currently permitted technical fields / 当前允许的技术字段
     * @return proposals for subsequent standard validation / 待标准校验的提案
     */
    public Map<String, String> suggest(List<DeploymentInputField> fields) {
        var allowed = fields.stream().filter(field -> AssistedParameterPolicy.allows(field.id())).toList();
        if (manual || allowed.isEmpty() || budget.remaining() == 0)
            return Map.of();
        AssistedDeploymentAdvice advice;
        try {
            if (analysis == null)
                return Map.of();
            advice = analysis.analyze(allowed, Map.copyOf(facts));
        } catch (SecurityException invalid) {
            throw invalid;
        } catch (Exception unavailable) {
            if (Thread.currentThread().isInterrupted() || unavailable instanceof CancellationException)
                throw new CancellationException();
            if (!interaction.confirm("deployment.assisted.manualFallback", Map.of("phase", "ANALYSIS")))
                throw new CancellationException();
            manual = true;
            return Map.of();
        }
        if (advice == null)
            return Map.of();
        progress.accept(LocalizedMessage.of("deployment.assisted.advice", Map.of("phase", "ANALYSIS", "summary",
                advice.summary(), "unresolved", String.join("\n", advice.unresolved()))));
        if (!advice.unresolved().isEmpty())
            return Map.of();
        var adopted = new LinkedHashMap<String, String>();
        for (var suggestion : advice.suggestions()) {
            var field = allowed.stream().filter(value -> value.id().equals(suggestion.field())).findFirst()
                    .orElseThrow();
            if (!AssistedParameterPolicy.accepts(field, suggestion.candidate())
                    || adopted.putIfAbsent(suggestion.field(), suggestion.candidate()) != null)
                throw new SecurityException("invalid assisted parameter");
            progress.accept(LocalizedMessage.of("deployment.assisted.parameter",
                    Map.of("field", suggestion.field(), "value", suggestion.candidate(), "evidence",
                            suggestion.evidence().stream()
                                    .map(reference -> analysis.locations().getOrDefault(reference, reference))
                                    .collect(java.util.stream.Collectors.joining(",")))));
        }
        return Map.copyOf(adopted);
    }

    /** Explains rejected source without overriding deterministic admission. / 解释被拒绝源码，不覆盖确定性准入。
     * @param evidence actual standard-analysis rejection / 实际标准分析拒绝
     */
    public void explainRejection(Map<String, String> evidence) {
        if (analysis == null || manual || budget.remaining() == 0)
            return;
        evidence.forEach(this::fact);
        try {
            var advice = analysis.analyze(List.of(), Map.copyOf(facts));
            progress.accept(LocalizedMessage.of("deployment.assisted.advice", Map.of("phase", "ANALYSIS", "summary",
                    advice.summary(), "unresolved", String.join("\n", advice.unresolved()))));
        } catch (SecurityException invalid) {
            throw invalid;
        } catch (Exception unavailable) {
            if (Thread.currentThread().isInterrupted() || unavailable instanceof CancellationException)
                throw new CancellationException();
            progress.accept(LocalizedMessage.of("deployment.assisted.manual"));
        }
    }

    /** Reviews nonsecret observations before execution or after failure. / 在执行前或失败后审阅非秘密观察。
     * @param phase fixed checkpoint / 固定节点
     * @param evidence bounded nonsecret facts / 有界非秘密事实
     */
    public void check(String phase, Map<String, String> evidence) {
        if (phase.equals("PREFLIGHT") && checkedPreflight != null && checkedPreflight.equals(evidence))
            return;
        var advice = invoke(phase, Map.of(), evidence);
        if (advice != null && !phase.equals("FAILURE") && !advice.unresolved().isEmpty() && !interaction
                .confirm("deployment.assisted.unresolved", Map.of("issues", String.join("\n", advice.unresolved()))))
            throw new CancellationException();
        if (phase.equals("PREFLIGHT"))
            checkedPreflight = Map.copyOf(evidence);
    }

    /** Calls once at the checkpoint, exposing every failure before manual continuation. / 在节点调用一次，人工继续前明确显示失败。
     * @param phase fixed checkpoint / 固定节点
     * @param candidates nonsecret candidates / 非秘密候选
     * @param evidence observed facts / 观察事实
     * @return valid advice or null after explicit manual continuation / 有效建议，明确人工继续后可为空
     */
    private AssistedDeploymentAdvice invoke(String phase, Map<String, List<String>> candidates,
            Map<String, String> evidence) {
        if (manual)
            return null;
        try {
            budget.consume();
            var advice = ai.advise(phase, candidates, evidence);
            progress.accept(LocalizedMessage.of("deployment.assisted.advice", Map.of("phase", phase, "summary",
                    advice.summary(), "unresolved", String.join("\n", advice.unresolved()))));
            return advice;
        } catch (Exception unavailable) {
            if (Thread.currentThread().isInterrupted())
                throw new CancellationException();
            if (!interaction.confirm("deployment.assisted.manualFallback", Map.of("phase", phase)))
                throw new CancellationException();
            manual = true;
            progress.accept(LocalizedMessage.of("deployment.assisted.manual"));
            return null;
        }
    }
}
