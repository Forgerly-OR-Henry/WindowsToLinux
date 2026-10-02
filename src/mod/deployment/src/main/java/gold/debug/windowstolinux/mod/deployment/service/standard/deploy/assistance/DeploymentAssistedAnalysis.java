package gold.debug.windowstolinux.mod.deployment.service.standard.deploy.assistance;

import java.io.IOException;
import java.util.*;

import com.fasterxml.jackson.databind.JsonNode;
import gold.debug.windowstolinux.appapi.ai.AiPurpose;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.task.TaskContext;
import gold.debug.windowstolinux.mod.deployment.ai.DeploymentAiJson;
import gold.debug.windowstolinux.mod.deployment.service.standard.deploy.input.DeploymentReviewField;
import gold.debug.windowstolinux.mod.deployment.file.DeploymentSourceBrowser;

/** Bounded read-only source analysis for missing technical input, without an execution port. / 不持有执行端口、用于技术缺项的有界只读源码分析。 */
public final class DeploymentAssistedAnalysis {
    /** Fixed model protocol. / 固定模型协议。 */
    private static final String POLICY = "You are a bounded read-only deployment analyst. Return exactly one JSON "
        + "object per turn with keys action, sourceRevision, arguments and no prose. sourceRevision must equal "
        + "the supplied SHA-256. Allowed actions: LIST with arguments {path,offset,limit}; READ with "
        + "arguments {path,offset,limit}; SEARCH with arguments {query,offset,limit}; ADVISE with arguments "
        + "{suggestions,summary,unresolved}. offset is a zero-based integer; limit is 1..100. LIST root path is dot. "
        + "ADVISE suggestions is an array of {field,candidate,evidence}; evidence is a nonempty array of "
        + "reference IDs from actual READ or SEARCH results. unresolved is an array of human questions. "
        + "Only propose offered technical field IDs, cite real observed evidence, and leave business intent, "
        + "credentials and permissions unresolved. Never request commands, writes or secret content. "
        + "Example LIST: {\"action\":\"LIST\",\"sourceRevision\":\"<supplied SHA-256>\","
        + "\"arguments\":{\"path\":\".\",\"offset\":0,\"limit\":50}}.";

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
        var observations = new ArrayList<Map<String, String>>();
        var evidence = new LinkedHashMap<String, String>();
        var queries = new HashSet<String>();
        int characters = 0;
        for (int decision = 0; decision < 12; decision++) {
            if (task.cancelToken().requested())
                throw new java.util.concurrent.CancellationException();
            source.verifyBoundaries();
            Map<String, Object> request = Map.of(
                "sourceRevision", source.revision(), "facts", facts, "fields", offered.values().stream()
                    .map(f -> Map.of("id", f.id(), "value", f.value(), "choices", f.choices())).toList(),
                "observations", List.copyOf(observations), "remaining", 12 - decision);
            JsonNode reply = ai.ask(AiPurpose.LANGUAGE, POLICY, request, revision, task);
            exact(reply, Set.of("action", "sourceRevision", "arguments"));
            source.verifyBoundaries();
            if (!source.revision().equals(DeploymentAiJson.text(reply, "sourceRevision", 64)))
                throw new SecurityException("Assisted source revision changed");
            String action = DeploymentAiJson.text(reply, "action", 16);
            JsonNode args = reply.get("arguments");
            if (args == null || !args.isObject())
                throw new IOException("Assisted action arguments missing");
            if (action.equals("ADVISE")) {
                exact(args, Set.of("suggestions", "summary", "unresolved"));
                source.verify();
                return advice(args, offered, evidence);
            }
            exact(args, Set.of(action.equals("SEARCH") ? "query" : "path", "offset", "limit"));
            String argument = DeploymentAiJson.text(args, action.equals("SEARCH") ? "query" : "path", 500);
            int offset = number(args, "offset", 0, 1000000);
            int limit = number(args, "limit", 1, 100);
            String binding = action + ":" + argument + ":" + offset + ":" + limit;
            if (!queries.add(binding))
                throw new IllegalStateException("Assisted analysis made no progress");
            List<String> lines = switch (action) {
                case "LIST" -> source.list(argument.equals(".") ? "" : argument, offset, limit);
                case "READ" -> source.read(argument, offset, limit);
                case "SEARCH" -> source.search(argument, offset, limit);
                default -> throw new SecurityException("Unsupported assisted source action");
            };
            String content = String.join("\n", lines);
            characters += content.length();
            if (characters > 48000)
                throw new IllegalStateException("Assisted evidence budget exhausted");
            String reference = "source/" + observations.size();
            observations.add(Map.of("reference", reference, "action", action, "content", content));
            if (!action.equals("LIST") && !lines.isEmpty()
                && lines.stream().anyMatch(line -> !line.contains("[credential content excluded]")))
                evidence.put(reference, argument + ":" + offset + "@" + source.revision());
        }
        throw new IllegalStateException("Assisted decision budget exhausted");
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
            String fieldId = DeploymentAiJson.text(item, "field", 128);
            String value = DeploymentAiJson.text(item, "candidate", 4096);
            var field = offered.get(fieldId);
            JsonNode cites = item.get("evidence");
            if (field == null || !DeploymentAssistedAdvisor.accepts(field, value) || values.containsKey(fieldId)
                || cites == null || !cites.isArray() || cites.isEmpty() || cites.size() > 8)
                throw new SecurityException("Unsupported assisted source suggestion");
            for (JsonNode cite : cites) {
                if (!cite.isTextual() || !evidence.containsKey(cite.textValue()))
                    throw new SecurityException("Unobserved assisted source evidence");
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
        return new Advice(values, locations, DeploymentAiJson.text(args, "summary", 2000), questions);
    }

    /** Reads a strict bounded integer. / 读取严格有界整数。
     * @param node input object / 输入对象
     * @param key property / 属性
     * @param minimum minimum value / 最小值
     * @param maximum maximum value / 最大值
     * @return checked value / 已检查值 */
    private static int number(JsonNode node, String key, int minimum, int maximum) {
        JsonNode value = node.get(key);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < minimum
            || value.intValue() > maximum)
            throw new IllegalArgumentException("Invalid assisted source page");
        return value.intValue();
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
