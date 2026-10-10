package gold.debug.windowstolinux.app.ai.model;

import java.io.IOException;
import java.nio.CharBuffer;
import java.nio.charset.*;
import java.util.*;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import gold.debug.windowstolinux.app.ai.vision.VisionMessageCodec;
import gold.debug.windowstolinux.appapi.ai.*;
import gold.debug.windowstolinux.appapi.ai.model.AiRequest;
import gold.debug.windowstolinux.appapi.ai.model.AiResponse;
import gold.debug.windowstolinux.appapi.ai.model.AiUsage;

/** Encodes generic model messages and validates provider envelopes without business policy. / 编码通用模型消息并校验提供者信封，不包含业务策略。 */
public final class ModelMessageCodec {
    /** Maximum bytes retained from one complete response. / 单次完整响应最多保留的字节数。 */
    public static final int MAX_RESPONSE_BYTES = 8 * 1024 * 1024;

    /** Strict bounded JSON codec shared after immutable configuration. / 不可变配置后共享的严格有界 JSON 编解码器。 */
    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
        .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(64)
            .maxStringLength(MAX_RESPONSE_BYTES).maxNumberLength(32).build())
        .build()).enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    /** Prevents instances of the stateless codec. / 禁止无状态编解码器实例。 */
    private ModelMessageCodec() {
    }

    /**
     * Encodes one validated request with explicit model and output budget. / 使用明确模型及输出预算编码一个已校验请求。
     * @param model configured provider model name / 已配置提供者模型名
     * @param request author-supplied immutable messages and budgets / 作者提供的不可变消息和预算
     * @param effectiveMaxOutputTokens host-resolved positive output budget / 宿主解析后的正数输出额度
     * @return exact UTF-8 request bytes / 精确 UTF-8 请求字节
     * @throws IllegalArgumentException if input violates a capability or budget / 输入违反能力或预算时
     */
    public static byte[] encode(String model, AiRequest request, int effectiveMaxOutputTokens) {
        return encode(model, request, effectiveMaxOutputTokens, ModelReasoningLevel.DEFAULT);
    }

    /** Encodes an optional, explicitly selected reasoning effort without identifying the vendor. / 编码明确选择的可选推理强度，不识别厂商。
     * @param model configured model name / 已配置模型名
     * @param request original immutable request / 原不可变请求
     * @param effectiveMaxOutputTokens host-resolved positive output budget / 宿主解析后的正数输出额度
     * @param effort optional configured effort / 可选已配置强度
     * @return exact request bytes / 精确请求字节 */
    public static byte[] encode(String model, AiRequest request, int effectiveMaxOutputTokens, ModelReasoningLevel effort) {
        return new ChatCompletionsCodec().encode(model, request, effectiveMaxOutputTokens, effort, Map.of());
    }

    /**
     * Reads one complete response while preserving missing usage as unknown. / 读取一个完整响应，将缺失用量保留为未知。
     * @param bytes bounded provider response / 有界提供者响应
     * @param modelId selected platform configuration ID / 所选平台配置标识
     * @param jsonObject whether object output was requested / 是否请求对象输出
     * @return provider text and actual envelope metadata / 提供者文本及实际信封元数据
     * @throws IOException if the response violates the wire contract / 响应违反传输契约时
     */
    public static AiResponse decode(byte[] bytes, String modelId, boolean jsonObject) throws IOException {
        return new ChatCompletionsCodec().decode(bytes, modelId, jsonObject).response();
    }

    /** Reports only response shape after a rejected envelope, without model text or reasoning. / 信封被拒后仅报告响应形状，不暴露模型正文或思考文本。
     * @param bytes bounded provider envelope / 有界提供方信封
     * @return non-secret validation metadata / 非秘密校验元数据 */
    public static Map<String, String> responseShape(byte[] bytes) {
        try {
            var root = JSON.readTree(bytes);
            if (root == null || !root.isObject()) return Map.of("responseEnvelope", "INVALID");
            var choice = root.path("choices").path(0);
            var message = choice.path("message");
            var facts = new LinkedHashMap<String, String>();
            String finish = choice.path("finish_reason").asText("");
            facts.put("finishReason", Set.of("stop", "length", "content_filter", "tool_calls", "insufficient_system_resource")
                .contains(finish) ? finish : "OTHER");
            facts.put("contentCharacters", Integer.toString(message.path("content").isTextual() ? message.path("content").textValue().length() : 0));
            long auxiliaryCharacters = 0;
            var messageFields = message.fields();
            while (messageFields.hasNext()) {
                var field = messageFields.next();
                if (!Set.of("content", "role").contains(field.getKey()) && field.getValue().isTextual())
                    auxiliaryCharacters += field.getValue().textValue().length();
            }
            facts.put("auxiliaryTextCharacters", Long.toString(auxiliaryCharacters));
            if (message.path("content").isTextual()) {
                String content = message.path("content").textValue().strip();
                facts.put("contentForm", content.startsWith("{") ? "OBJECT_PREFIX" : content.startsWith("```") ? "CODE_FENCE" : "OTHER");
                try {
                    var object = JSON.readTree(content);
                    facts.put("jsonObject", object != null && object.isObject() ? "VALID" : "INVALID");
                    if (object == null || !object.isObject()) facts.put("jsonFailureKind", "NON_OBJECT");
                } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
                    facts.put("jsonObject", "INVALID");
                    facts.put("jsonFailureKind", jsonFailureKind(content));
                    if (invalid.getLocation() != null) {
                        facts.put("jsonErrorLine", Integer.toString(invalid.getLocation().getLineNr()));
                        facts.put("jsonErrorColumn", Integer.toString(invalid.getLocation().getColumnNr()));
                    }
                }
            }
            var usage = usage(root);
            usage.inputTokens().ifPresent(value -> facts.put("inputTokens", Long.toString(value)));
            usage.outputTokens().ifPresent(value -> facts.put("outputTokens", Long.toString(value)));
            usage.cachedInputTokens().ifPresent(value -> facts.put("cachedInputTokens", Long.toString(value)));
            usage.cacheMissInputTokens().ifPresent(value -> facts.put("cacheMissInputTokens", Long.toString(value)));
            usage.reasoningOutputTokens().ifPresent(value -> facts.put("reasoningOutputTokens", Long.toString(value)));
            return Map.copyOf(facts);
        } catch (IOException invalid) {
            return Map.of("responseEnvelope", "INVALID");
        }
    }

    /** Normalizes reported usage fields without model-name routing or inferred missing counts. / 不按模型名称分流、不推断缺失计数，规范化报告用量。
     * @param root complete provider response / 完整提供方响应
     * @return validated optional usage / 已校验可选用量
     * @throws IOException if reported counts conflict or are invalid / 报告计数冲突或无效时 */
    static AiUsage usage(JsonNode root) throws IOException {
        var usage = root.path("usage");
        var cached = tokens(usage.path("prompt_tokens_details").path("cached_tokens"));
        var alternate = tokens(usage.path("prompt_cache_hit_tokens"));
        if (cached.isPresent() && alternate.isPresent() && !cached.equals(alternate))
            throw new IOException("Model cache usage aliases conflict");
        try {
            return new AiUsage(tokens(usage.path("prompt_tokens")), tokens(usage.path("completion_tokens")),
                cached.isPresent() ? cached : alternate, tokens(usage.path("prompt_cache_miss_tokens")),
                tokens(usage.path("completion_tokens_details").path("reasoning_tokens")));
        } catch (IllegalArgumentException invalid) {
            throw new IOException("Model usage is inconsistent", invalid);
        }
    }

    /** Classifies rejected JSON without exposing or selecting any response value. / 对被拒JSON分类，不暴露或选择任何响应值。
     * @param content original rejected text / 原被拒文本
     * @return fixed nonsecret shape classification / 固定非秘密形状分类 */
    private static String jsonFailureKind(String content) {
        try (var parser = JSON.getFactory().createParser(content)) {
            if (parser.nextToken() == null) return "MALFORMED_JSON";
            parser.skipChildren();
            return parser.nextToken() == null ? "MALFORMED_JSON" : "TRAILING_JSON_VALUE";
        } catch (IOException rejected) {
            return "MALFORMED_JSON";
        }
    }

    /** Reads an optional exact nonnegative token count. / 读取可选的精确非负 Token 数。
     * @param node provider usage field / 提供者用量字段
     * @return reported count or absent / 已报告数量或缺失
     * @throws IOException if a reported value is invalid / 已报告值无效时 */
    static Optional<Long> tokens(JsonNode node) throws IOException {
        if (node.isMissingNode() || node.isNull())
            return Optional.empty();
        if (!node.isIntegralNumber() || !node.canConvertToLong() || node.longValue() < 0)
            throw new IOException("Model usage is invalid");
        return Optional.of(node.longValue());
    }
}
