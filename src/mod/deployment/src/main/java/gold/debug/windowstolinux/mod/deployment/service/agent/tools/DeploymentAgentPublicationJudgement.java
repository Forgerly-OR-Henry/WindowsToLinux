package gold.debug.windowstolinux.mod.deployment.service.agent.tools;

import java.util.*;

import com.fasterxml.jackson.databind.JsonNode;
import gold.debug.windowstolinux.mod.deployment.ai.agent.DeploymentModelToolLoop;
import gold.debug.windowstolinux.mod.deployment.service.agent.protocol.DeploymentAgentAssessment;
import gold.debug.windowstolinux.mod.deployment.service.agent.protocol.DeploymentAgentEvidence;
import gold.debug.windowstolinux.mod.deployment.service.agent.recovery.evidence.DeploymentAgentActionLog;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;

/** Publication decisions assess original sealed inputs and actual tool results. / 发布决策评估原封存输入和真实工具结果。 */
final class DeploymentAgentPublicationJudgement implements DeploymentModelToolLoop.SelectionReview {
    /** Actual source evidence / 实际来源证据。
     */
    private final DeploymentAgentEvidence evidence = new DeploymentAgentEvidence();

    /** Exact current revision / 精确当前修订。
     */
    private final String revision;

    /** Original admitted task / 原已准入任务。
     */
    private final TaskContext task;

    /** Original durable task journal / 原持久任务日志。
     */
    private final DeploymentAgentActionLog journal;

    private final Map<String, String> contractIds = new LinkedHashMap<>();

    private final Map<String, Map<String, Object>> contractPages = new LinkedHashMap<>();

    /** Binds original task capabilities / 绑定原任务能力。
     * @param revision Exact current revision / 精确当前修订
     * @param task Original admitted task / 原已准入任务
     * @param journal Original durable task journal / 原持久任务日志
     */
    DeploymentAgentPublicationJudgement(String revision, TaskContext task, DeploymentAgentActionLog journal) {
        this.revision = revision;
        this.task = task;
        this.journal = journal;
        String id = evidence.observe("", revision, 0, "SEALED_PUBLICATION_INPUTS", "", true);
        evidence.output(id, "SEALED_PUBLICATION_INPUTS revision=" + revision
            + "; exact sealed graph, candidate and runtime bindings are in current state.publication."
            + " This observation does not attest staged inputs, activation or health.");
    }

    /** Identifies critical publication selections / 标识关键发布选择。
     * @param name registered action name / 已登记动作名称
     * @return checked current result / 已校验当前结果
     */
    @Override
    public boolean required(String name) {
        return Set.of("command", "stage", "publish", "verify", "finish", "finish_failure", "continue_deployment").contains(name);
    }

    /** Evaluates current actual observations before the selected action / 在选定动作前评估当前实际观察。
     * @param name registered action name / 已登记动作名称
     * @param summary Nonsecret public summary / 非秘密公开摘要
     * @throws Exception if original evidence or admission is unconfirmed / 原依据或准入未确认时
     */
    @Override
    public void assess(String name, JsonNode summary) throws Exception {
        var value = DeploymentAgentAssessment.parse(summary);
        evidence.assess(name, value, Map.of(), revision, 0);
        journal.event(task, "PUBLICATION_ASSESSMENT", name,
            gold.debug.windowstolinux.mod.deployment.service.agent.contract.DeploymentAgentInputs.hash(value.toString()));
    }

    /** Retains actual publication feedback and its identity / 保留实际发布反馈及身份。
     * @param name registered action name / 已登记动作名称
     * @param output Actual nonsecret feedback / 实际非秘密反馈
     * @return checked current result / 已校验当前结果
     */
    @Override
    public String observed(String name, String output) {
        if (name.equals("skill")) return "PRODUCT_SKILL";
        if (name.equals("observation")) return output.substring(0, output.indexOf(' '));
        if (name.equals("evidence_index")) return "EVIDENCE_INDEX";
        if (name.equals("contract") && contractIds.containsKey(output)) return contractIds.get(output);
        String path = "";
        com.fasterxml.jackson.databind.JsonNode page = null;
        if (name.equals("contract")) {
            try {
                page = new com.fasterxml.jackson.databind.ObjectMapper().readTree(output);
            } catch (java.io.IOException invalid) {
                throw new IllegalArgumentException("Invalid contract page metadata", invalid);
            }
            path = page.path("path").asText();
        }
        String id = evidence.observe("", revision, 0, name, path, required(name));
        evidence.output(id, output);
        if (page != null) {
            evidence.page(id, path, page.path("offset").asInt(), page.path("requestedLimit").asInt(), false);
            contractIds.put(output, id);
            contractPages.put(id, Map.of("id", id, "path", path, "offset", page.path("offset").asInt(),
                "totalLines", page.path("totalLines").asInt(), "nextLine", page.path("nextLine").asInt(),
                "hasMore", page.path("hasMore").asBoolean(), "returnedLines", page.path("returnedLines").asInt(),
                "complete", true));
        }
        return id;
    }

    /** Returns bounded current decision evidence / 返回有界当前决策证据。
     * @return checked current result / 已校验当前结果
     */
    @Override
    public Map<String, Object> state() {
        var state = new HashMap<String, Object>(evidence.state());
        state.put("evidenceIndex", evidence.latestIndex());
        state.put("contractPages", List.copyOf(contractPages.values()));
        return Map.copyOf(state);
    }

    @Override
    public String observation(String id, int offset, int limit) {
        String output = evidence.read(id, offset, limit);
        while (output.length() > 5900 && limit > 1) {
            limit = Math.max(1, limit - (output.length() - 5900));
            output = evidence.read(id, offset, limit);
        }
        return output;
    }

    @Override
    public Map<String, Object> evidenceIndex(int offset, int limit) {
        return evidence.index(offset, limit);
    }

    /** Fixed bundled contracts and previously sanitized reads contain no live credential values. / 固定随包合同及已脱敏观察读取不包含现场凭据值。 */
    public String feedback(String name, String output) throws Exception {
        if (Set.of("contract", "observation", "evidence_index", "skill").contains(name)) return output;
        var publication = DeploymentAgentPublicationReceipts.safeFeedback(name, output);
        return publication.isPresent() ? publication.orElseThrow()
            : gold.debug.windowstolinux.mod.deployment.service.agent.DeploymentAgentFeedback.safe(name, output);
    }
}
