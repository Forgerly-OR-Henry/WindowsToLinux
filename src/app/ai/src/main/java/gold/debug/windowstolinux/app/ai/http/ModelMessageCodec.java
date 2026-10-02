package gold.debug.windowstolinux.app.ai.http;

import java.io.IOException;
import java.nio.CharBuffer;
import java.nio.charset.*;
import java.util.*;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import gold.debug.windowstolinux.appapi.ai.*;

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
     * @param configuredTokens selected model's configured maximum / 所选模型的已配置最大值
     * @return exact UTF-8 request bytes / 精确 UTF-8 请求字节
     * @throws IllegalArgumentException if input violates a capability or budget / 输入违反能力或预算时
     */
    public static byte[] encode(String model, AiRequest request, int configuredTokens) {
        Objects.requireNonNull(request);
        if (model == null || !model.matches("[A-Za-z0-9._:/-]{1,128}"))
            throw new IllegalArgumentException("Invalid configured model name");
        if (configuredTokens < 1 || request.maxOutputTokens() > configuredTokens)
            throw new IllegalArgumentException("Output token budget exceeds model configuration");
        try {
            request.timeout().toNanos();
        } catch (ArithmeticException invalid) {
            throw new IllegalArgumentException("Model timeout is too large");
        }
        if (request.messages().isEmpty() || request.messages().size() > 1024)
            throw new IllegalArgumentException("A request requires one to 1024 messages");
        int textBytes = 0, imageCount = 0;
        var messages = JSON.createArrayNode();
        for (var message : request.messages()) {
            if (message.text().length() > 1024 * 1024)
                throw new IllegalArgumentException("Model text is too large");
            try {
                textBytes += StandardCharsets.UTF_8.newEncoder().encode(CharBuffer.wrap(message.text())).remaining();
            } catch (CharacterCodingException invalid) {
                throw new IllegalArgumentException("Model text is not valid Unicode");
            }
            if (textBytes > 1024 * 1024)
                throw new IllegalArgumentException("Model text exceeds one MiB");
            var item = messages.addObject().put("role", message.role().name().toLowerCase(Locale.ROOT));
            if (message.images().isEmpty())
                item.put("content", message.text());
            else {
                if (request.purpose() != AiPurpose.VISION)
                    throw new IllegalArgumentException("Images require VISION purpose");
                var content = item.putArray("content");
                content.addObject().put("type", "text").put("text", message.text());
                for (var image : message.images()) {
                    if (++imageCount > 4)
                        throw new IllegalArgumentException("At most four images are accepted");
                    byte[] bytes = image.content();
                    try {
                        boolean png = image.mediaType().equals("image/png") && bytes.length >= 8 && Arrays
                            .equals(Arrays.copyOf(bytes, 8), new byte[]{(byte) 137, 80, 78, 71, 13, 10, 26, 10});
                        boolean jpeg = image.mediaType().equals("image/jpeg") && bytes.length >= 3
                            && (bytes[0] & 255) == 255 && (bytes[1] & 255) == 216 && (bytes[2] & 255) == 255;
                        if (bytes.length > 10 * 1024 * 1024 || !png && !jpeg)
                            throw new IllegalArgumentException("Image type or size is invalid");
                        content.addObject().put("type", "image_url").putObject("image_url").put("url",
                            "data:" + image.mediaType() + ";base64," + Base64.getEncoder().encodeToString(bytes));
                    } finally {
                        Arrays.fill(bytes, (byte) 0);
                    }
                }
            }
        }
        var body = JSON.createObjectNode().put("model", model).put("max_tokens", request.maxOutputTokens())
            .put("stream", false);
        body.set("messages", messages);
        if (request.jsonObjectResponse())
            body.putObject("response_format").put("type", "json_object");
        try {
            return JSON.writeValueAsBytes(body);
        } catch (IOException invalid) {
            throw new IllegalArgumentException("Model request cannot be encoded");
        }
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
        if (bytes.length > MAX_RESPONSE_BYTES)
            throw new IOException("Model response exceeds its byte limit");
        var root = JSON.readTree(bytes);
        if (root == null || !root.isObject() || !root.path("choices").isArray() || root.path("choices").isEmpty())
            throw new IOException("Model response has no complete choice");
        var choice = root.path("choices").get(0);
        var content = choice.path("message").path("content");
        var finish = choice.path("finish_reason");
        if (!content.isTextual() || !finish.isTextual() || finish.textValue().isBlank()
            || finish.textValue().length() > 128)
            throw new IOException("Model response choice is incomplete");
        if (jsonObject) {
            var value = JSON.readTree(content.textValue());
            if (value == null || !value.isObject())
                throw new IOException("Model response is not a JSON object");
        }
        return new AiResponse(content.textValue(), modelId, finish.textValue(),
            new AiUsage(tokens(root.path("usage").path("prompt_tokens")),
                tokens(root.path("usage").path("completion_tokens"))));
    }

    /** Reads an optional exact nonnegative token count. / 读取可选的精确非负 Token 数。
     * @param node provider usage field / 提供者用量字段
     * @return reported count or absent / 已报告数量或缺失
     * @throws IOException if a reported value is invalid / 已报告值无效时 */
    private static Optional<Long> tokens(JsonNode node) throws IOException {
        if (node.isMissingNode() || node.isNull())
            return Optional.empty();
        if (!node.isIntegralNumber() || !node.canConvertToLong() || node.longValue() < 0)
            throw new IOException("Model usage is invalid");
        return Optional.of(node.longValue());
    }
}
