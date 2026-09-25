package gold.debug.windowstolinux.shared.standard.deploy.assistance;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import gold.debug.windowstolinux.shared.model.deployment.AssistedDeploymentAdvice;
import gold.debug.windowstolinux.shared.model.deployment.DeploymentInputField;
import gold.debug.windowstolinux.shared.source.browse.SourceReadPort;

/** Independent bounded analysis over a read-only source capability. / 基于只读源码能力的独立有界分析。 */
public final class AssistedAnalysisSession {
    /** Narrow source capability, without a writable path or executor. / 不包含可写路径或执行器的窄源码能力。 */
    private final SourceReadPort source;

    /** Analysis-specific model protocol. / 分析专用模型协议。 */
    private final AssistedAnalysisModelPort model;

    /** Shared task allowance. / 共享任务额度。 */
    private final AssistedDecisionBudget budget;

    /** Last accepted source locations, excluding raw source content. / 最近采纳的源码位置，不含原始源码正文。 */
    private Map<String, String> locations = Map.of();

    /** Binds analysis-only capabilities. / 绑定仅供分析的能力。
     * @param source read-only snapshot / 只读快照
     * @param model independent analysis model / 独立分析模型
     * @param budget task-wide budget / 任务预算
     */
    public AssistedAnalysisSession(SourceReadPort source, AssistedAnalysisModelPort model,
            AssistedDecisionBudget budget) {
        this.source = Objects.requireNonNull(source);
        this.model = Objects.requireNonNull(model);
        this.budget = Objects.requireNonNull(budget);
    }

    /** Resolves ambiguity using only actually observed source evidence. / 仅使用实际观察的源码证据解决歧义。
     * @param fields allowed unresolved technical fields / 允许补全的技术字段
     * @param facts standard facts and current nonsecret values / 标准事实及当前非秘密值
     * @return verified advice retaining unresolved questions / 经过验证且保留未决问题的建议
     * @throws Exception when the boundary, budget or model fails / 边界、预算或模型失败时
     */
    public AssistedDeploymentAdvice analyze(List<DeploymentInputField> fields, Map<String, String> facts)
            throws Exception {
        var offered = new LinkedHashMap<String, DeploymentInputField>();
        fields.stream().filter(field -> AssistedParameterPolicy.allows(field.id()))
                .forEach(field -> offered.put(field.id(), field));
        source.verify();
        var context = Map.<String, Object>of("sourceRevision", source.revision(), "fields",
                List.copyOf(offered.values()), "facts", Map.copyOf(facts));
        var observations = new ArrayList<Map<String, String>>();
        var evidence = new LinkedHashMap<String, Map<String, String>>();
        var queries = new java.util.HashSet<String>();
        int totalCharacters = 0;
        while (true) {
            if (Thread.currentThread().isInterrupted())
                throw new java.util.concurrent.CancellationException();
            source.verify();
            var decision = model.analyze(context, List.copyOf(observations), budget.consume());
            source.verify();
            if (!decision.sourceRevision().equals(source.revision()))
                throw new SecurityException("analysis revision mismatch");
            if (decision.action().equals("ADVISE")) {
                var used = new java.util.HashSet<String>();
                for (var suggestion : decision.advice().suggestions()) {
                    var field = offered.get(suggestion.field());
                    if (field == null || !used.add(suggestion.field())
                            || !AssistedParameterPolicy.accepts(field, suggestion.candidate())
                            || suggestion.evidence().isEmpty() || !evidence.keySet().containsAll(suggestion.evidence()))
                        throw new SecurityException("unsupported assisted source suggestion");
                }
                var accepted = new LinkedHashMap<String, String>();
                evidence.forEach((key, value) -> accepted.put(key, value.get("locations") + "@" + source.revision()));
                locations = Map.copyOf(accepted);
                return decision.advice();
            }
            String query = decision.action() + ":" + decision.argument() + ":" + decision.offset() + ":"
                    + decision.limit();
            if (!queries.add(query) || queries.size() > 12)
                throw new IllegalStateException("analysis made no further progress");
            List<String> lines = switch (decision.action()) {
                case "LIST" -> source.list(decision.argument(), decision.offset(), decision.limit());
                case "READ" -> source.read(decision.argument(), decision.offset(), decision.limit());
                case "SEARCH" -> source.search(decision.argument(), decision.offset(), decision.limit());
                default -> throw new SecurityException("unsupported source capability");
            };
            String content = String.join("\n", lines);
            totalCharacters += content.length();
            if (totalCharacters > 48000)
                throw new IllegalStateException("analysis evidence budget exhausted");
            String reference = "source/" + observations.size();
            String readLocations = decision.action().equals("READ")
                    ? decision.argument() + ":" + (decision.offset() + 1) + "-" + (decision.offset() + lines.size())
                    : decision.action().equals("SEARCH")
                            ? lines.stream().map(line -> line.substring(0, line.indexOf(": ")))
                                    .collect(java.util.stream.Collectors.joining(","))
                            : "";
            var observation = Map.of("reference", reference, "action", decision.action(), "pathOrQuery",
                    decision.argument(), "offset", Integer.toString(decision.offset()), "sourceRevision",
                    source.revision(), "content", content, "locations", readLocations);
            observations.add(observation);
            if (!decision.action().equals("LIST") && !lines.isEmpty()
                    && lines.stream().anyMatch(line -> !line.contains("[credential content excluded]")))
                evidence.put(reference, observation);
        }
    }

    /** Exposes exact source locations for transient user review. / 暴露精确源码位置供临时用户审阅。
     * @return accepted evidence locations and revisions / 已采纳证据位置及修订
     */
    public Map<String, String> locations() {
        return locations;
    }
}
