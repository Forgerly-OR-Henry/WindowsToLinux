package gold.debug.windowstolinux.mod.deployment.service.standard.assisted;

import gold.debug.windowstolinux.mod.deployment.ai.skill.DeploymentSkillResources;

import java.io.IOException;
import java.util.*;

import com.fasterxml.jackson.databind.JsonNode;
import gold.debug.windowstolinux.appapi.ai.model.AiPurpose;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.mod.deployment.ai.DeploymentAiJson;
import gold.debug.windowstolinux.mod.deployment.ai.json.DeploymentJsonFields;
import gold.debug.windowstolinux.mod.deployment.file.DeploymentSourceBrowser;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.input.DeploymentReviewField;

/** Bounded read-only source analysis for missing technical input, without an execution port. / 不持有执行端口、用于技术缺项的有界只读源码分析。 */
public final class DeploymentAssistedAnalysis {
    private static final String ACTION_SCHEMA = """
        {"type":"object","additionalProperties":false,"required":["action","sourceRevision","arguments"],
         "properties":{"action":{"type":"string","enum":["LIST","READ","SEARCH","ADVISE"]},
         "sourceRevision":{"type":"string","pattern":"^[0-9a-f]{64}$"},
         "arguments":{"oneOf":[
           {"type":"object","additionalProperties":false,"required":["path","offset","limit"],
            "properties":{"path":{"type":"string"},"offset":{"type":"integer","minimum":0,"maximum":1000000},"limit":{"type":"integer","minimum":1,"maximum":100}}},
           {"type":"object","additionalProperties":false,"required":["query","offset","limit"],
            "properties":{"query":{"type":"string"},"offset":{"type":"integer","minimum":0,"maximum":1000000},"limit":{"type":"integer","minimum":1,"maximum":100}}},
           {"type":"object","additionalProperties":false,"required":["suggestions","summary","unresolved"],
            "properties":{"suggestions":{"type":"array","maxItems":16,"items":{"type":"object","additionalProperties":false,"required":["field","candidate","evidence"],
              "properties":{"field":{"type":"string"},"candidate":{"type":"string"},"evidence":{"type":"array","minItems":1,"maxItems":8,"items":{"type":"string"}}}}},
              "summary":{"type":"string"},"unresolved":{"type":"array","maxItems":16,"items":{"type":"string"}}}}
         ]}}}
        """;

    /** Fixed model protocol. / 固定模型协议。 */
    private static final String POLICY = DeploymentSkillResources.text(DeploymentAssistedAnalysis.class, "source-analysis.md");

    /** Model adapter and original read-only source. / 模型适配器与原只读来源。 */
    private final DeploymentAiJson ai;

    /** Read-only original frozen source browser. / 原冻结来源只读浏览器。 */
    private final DeploymentSourceBrowser source;

    /** Exact routing revision shared by every analysis turn. / 所有分析轮次共用的精确路由修订。 */
    private final String revision;

    /** Validated suggestions and unresolved questions. / 已校验建议与未决问题。
     * @param values evidence-backed technical candidates / 有依据的技术候选
     * @param locations exact accepted source locations / 已接受的精确源码位置
     * @param summary bounded explanation / 有界说明
     * @param unresolved questions for the human / 需人工回答的问题 */
    public record Advice(Map<String, String> values, Map<String, String> locations, String summary,
                         List<String> unresolved) {
        /** Copies transient review material. / 复制临时审阅材料。
         * @param values technical candidates / 技术候选
         * @param locations actual source locations / 实际源码位置
         * @param summary explanation / 说明
         * @param unresolved human questions / 人工问题 */
        public Advice {
            values = Map.copyOf(values);
            locations = Map.copyOf(locations);
            unresolved = List.copyOf(unresolved);
            Objects.requireNonNull(summary);
        }
    }

    /** Binds only source reading and platform AI. / 仅绑定源码读取与平台 AI。
     * @param context actual Mod context / 实际 Mod 上下文
     * @param source original frozen source / 原冻结来源
     * @param revision frozen original model-routing identity / 冻结原模型路由身份 */
    public DeploymentAssistedAnalysis(ModContext context, DeploymentSourceBrowser source, String revision) {
        this.ai = new DeploymentAiJson(context);
        this.source = Objects.requireNonNull(source);
        this.revision = Objects.requireNonNull(revision);
    }

    /** Resolves only offered fields using actual source observations. / 仅根据真实来源观察补全已提供字段。
     * @param fields currently offered technical fields / 当前提供的技术字段
     * @param facts bounded deterministic facts / 有界确定性事实
     * @param task original task / 原任务
     * @return evidence-checked proposals and unresolved questions / 有依据的建议及未决问题
     * @throws Exception on invalid, cancelled or unavailable analysis / 分析无效、取消或不可用时 */
    public Advice analyze(List<DeploymentReviewField> fields, Map<String, String> facts, TaskContext task)
        throws Exception {
        var offered = new LinkedHashMap<String, DeploymentReviewField>();
        for (var field : fields)
            if (DeploymentAssistedAdvisor.allows(field.id()))
                offered.put(field.id(), field);
        if (facts.size() > 32 || facts.toString().length() > 8000)
            throw new IllegalArgumentException("Assisted facts exceed budget");
        var observations = new ArrayList<Map<String, Object>>();
        var evidence = new LinkedHashMap<String, String>();
        var queries = new HashSet<String>();
        Map<String, String> feedback = Map.of();
        int characters = 0;
        for (int decision = 0; decision < 12; decision++) {
            if (task.cancelToken().requested())
                throw new java.util.concurrent.CancellationException();
            source.verifyBoundaries();
            Map<String, Object> request = Map.of(
                "sourceRevision", source.revision(), "facts", facts, "fields", offered.values().stream()
                    .map(f -> Map.of("id", f.id(), "value", f.value(), "choices", f.choices())).toList(),
                "observations", List.copyOf(observations), "evidenceReferences", List.copyOf(evidence.keySet()),
                "feedback", feedback, "remaining", 12 - decision);
            JsonNode reply = ai.askStructured(AiPurpose.LANGUAGE, POLICY, request, revision, task, actionSchema(offered, evidence, source.revision()),
                node -> {
                    try {
                        exact(node, Set.of("action", "sourceRevision", "arguments"));
                        if (!Set.of("LIST", "READ", "SEARCH", "ADVISE").contains(DeploymentJsonFields.text(node, "action", 16))
                            || !node.path("sourceRevision").isTextual() || !node.path("arguments").isObject())
                            return "Expected action LIST/READ/SEARCH/ADVISE, sourceRevision string and arguments object";
                        String action = node.path("action").asText();
                        exact(node.path("arguments"), action.equals("ADVISE") ? Set.of("suggestions", "summary", "unresolved")
                            : Set.of(action.equals("SEARCH") ? "query" : "path", "offset", "limit"));
                        return null;
                    } catch (IllegalArgumentException invalid) {
                        return "Expected action/sourceRevision/arguments; LIST/READ arguments path/offset/limit, SEARCH query/offset/limit, ADVISE suggestions/summary/unresolved";
                    }
                });
            exact(reply, Set.of("action", "sourceRevision", "arguments"));
            source.verifyBoundaries();
            if (!source.revision().equals(DeploymentJsonFields.text(reply, "sourceRevision", 64))) {
                feedback = Map.of("code", "SOURCE_REVISION_MISMATCH", "message", "The request was discarded without reading source or accepting advice. Copy the supplied sourceRevision exactly.");
                continue;
            }
            String action = DeploymentJsonFields.text(reply, "action", 16);
            JsonNode args = reply.get("arguments");
            if (args == null || !args.isObject())
                throw new IOException("Assisted action arguments missing");
            if (action.equals("ADVISE")) {
                exact(args, Set.of("suggestions", "summary", "unresolved"));
                source.verify();
                try {
                    return advice(args, offered, evidence);
                } catch (UnobservedEvidenceException rejected) {
                    feedback = Map.of("code", "ADVICE_EVIDENCE_REJECTED", "message",
                        "No suggestion was accepted. Cite exact evidenceReferences from nonempty READ/SEARCH only; LIST, empty or excluded results and file paths are not evidence IDs. Read actual source or leave fields unresolved.");
                    continue;
                }
            }
            if (!Set.of("LIST", "READ", "SEARCH").contains(action))
                throw new SecurityException("Unsupported assisted source action");
            exact(args, Set.of(action.equals("SEARCH") ? "query" : "path", "offset", "limit"));
            String argument = DeploymentJsonFields.text(args, action.equals("SEARCH") ? "query" : "path", 500);
            var requestedOffset = number(args, "offset", 0, 1000000);
            var requestedLimit = number(args, "limit", 1, 100);
            if (requestedOffset.isEmpty() || requestedLimit.isEmpty()) {
                feedback = Map.of("code", "INVALID_PAGE", "message", "The request was discarded without reading source. offset must be an integer in 0..1000000 and limit an integer in 1..100. Use successive pages for larger reads.");
                continue;
            }
            int offset = requestedOffset.getAsInt();
            int limit = requestedLimit.getAsInt();
            String binding = action + ":" + argument + ":" + offset + ":" + limit;
            if (!queries.add(binding)) {
                feedback = Map.of("code", "DUPLICATE_QUERY", "message", "This query was already observed.");
                continue;
            }
            List<String> lines;
            try {
                lines = switch (action) {
                    case "LIST" -> source.list(argument.equals(".") ? "" : argument, offset, limit);
                    case "READ" -> source.read(argument, offset, limit);
                    case "SEARCH" -> source.search(argument, offset, limit);
                    default -> throw new SecurityException("Unsupported assisted source action");
                };
            } catch (DeploymentSourceBrowser.MissingMemberException missing) {
                feedback = Map.of("code", "FILE_NOT_FOUND", "message", missing.getMessage());
                continue;
            }
            String content = String.join("\n", lines);
            characters += content.length();
            if (characters > 48000)
                throw new IllegalStateException("Assisted evidence budget exhausted");
            String reference = "source/" + observations.size();
            observations.add(Map.of("reference", reference, "action", action, "content", content,
                action.equals("SEARCH") ? "query" : "path", argument, "offset", offset, "limit", limit));
            feedback = Map.of();
            if (!action.equals("LIST") && !lines.isEmpty()
                && lines.stream().anyMatch(line -> !line.contains("[credential content excluded]")))
                evidence.put(reference, argument + ":" + offset + "@" + source.revision());
        }
        throw new IOException("Assisted decision budget exhausted");
    }

    /** Validates evidence-backed suggestions and preserves unresolved inputs. / 校验有依据的建议并保留未决输入。
     * @param args final model arguments / 最终模型参数
     * @param offered allowed technical fields / 允许的技术字段
     * @param evidence actually observed source / 实际观察来源
     * @return checked advice / 已检查建议 */
    private static Advice advice(JsonNode args, Map<String, DeploymentReviewField> offered,
                                 Map<String, String> evidence) {
        JsonNode suggestions = args.get("suggestions");
        JsonNode unresolved = args.get("unresolved");
        if (suggestions == null || !suggestions.isArray() || suggestions.size() > 16 || unresolved == null
            || !unresolved.isArray() || unresolved.size() > 16)
            throw new IllegalArgumentException("Invalid assisted advice");
        var values = new LinkedHashMap<String, String>();
        var locations = new LinkedHashMap<String, String>();
        for (JsonNode item : suggestions) {
            exact(item, Set.of("field", "candidate", "evidence"));
            String fieldId = DeploymentJsonFields.text(item, "field", 128);
            String value = DeploymentJsonFields.text(item, "candidate", 4096);
            var field = offered.get(fieldId);
            JsonNode cites = item.get("evidence");
            if (field == null || !DeploymentAssistedAdvisor.accepts(field, value) || values.containsKey(fieldId)
                || cites == null || !cites.isArray() || cites.isEmpty() || cites.size() > 8)
                throw new SecurityException("Unsupported assisted source suggestion");
            for (JsonNode cite : cites) {
                if (!cite.isTextual() || !evidence.containsKey(cite.textValue()))
                    throw new UnobservedEvidenceException();
                locations.put(cite.textValue(), evidence.get(cite.textValue()));
            }
            values.put(fieldId, value);
        }
        var questions = new ArrayList<String>();
        for (JsonNode item : unresolved) {
            if (!item.isTextual() || item.textValue().length() > 1000)
                throw new IllegalArgumentException("Invalid unresolved input");
            questions.add(item.textValue());
        }
        return new Advice(values, locations, DeploymentJsonFields.text(args, "summary", 2000), questions);
    }

    /** Advertises only actually observed source references; empty evidence permits unresolved-only advice. */
    private static String actionSchema(Map<String, DeploymentReviewField> offered, Map<String, String> evidence, String revision) throws IOException {
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        var schema = json.readTree(ACTION_SCHEMA);
        ((com.fasterxml.jackson.databind.node.ObjectNode) schema.at("/properties/sourceRevision")).putArray("enum").add(revision);
        var suggestions = (com.fasterxml.jackson.databind.node.ObjectNode) schema.at("/properties/arguments/oneOf/2/properties/suggestions");
        if (evidence.isEmpty() || offered.isEmpty()) suggestions.put("maxItems", 0);
        else {
            var fields = (com.fasterxml.jackson.databind.node.ObjectNode) suggestions.at("/items/properties/field");
            fields.set("enum", json.valueToTree(List.copyOf(offered.keySet())));
            var references = (com.fasterxml.jackson.databind.node.ObjectNode) suggestions.at("/items/properties/evidence/items");
            references.set("enum", json.valueToTree(List.copyOf(evidence.keySet())));
        }
        return schema.toString();
    }

    /** A rejected citation is corrigible inside the original decision budget, with no partial advice. */
    private static final class UnobservedEvidenceException extends SecurityException {
        private UnobservedEvidenceException() {
            super("Unobserved assisted source evidence");
        }
    }

    /** Reads a strict bounded integer. / 读取严格有界整数。
     * @param node input object / 输入对象
     * @param key property / 属性
     * @param minimum minimum value / 最小值
     * @param maximum maximum value / 最大值
     * @return checked value or empty for invalid page parameters / 已检查值，分页参数无效时为空 */
    private static OptionalInt number(JsonNode node, String key, int minimum, int maximum) {
        JsonNode value = node.get(key);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < minimum
            || value.intValue() > maximum)
            return OptionalInt.empty();
        return OptionalInt.of(value.intValue());
    }

    /** Rejects unoffered fields rather than silently ignoring them. / 拒绝未提供字段而不默默忽略。
     * @param node protocol object / 协议对象
     * @param expected exact keys / 精确字段 */
    private static void exact(JsonNode node, Set<String> expected) {
        var keys = new HashSet<String>();
        if (!node.isObject())
            throw new IllegalArgumentException("Assisted protocol requires an object");
        node.fieldNames().forEachRemaining(keys::add);
        if (!keys.equals(expected))
            throw new IllegalArgumentException("Unexpected assisted protocol fields");
    }
}
