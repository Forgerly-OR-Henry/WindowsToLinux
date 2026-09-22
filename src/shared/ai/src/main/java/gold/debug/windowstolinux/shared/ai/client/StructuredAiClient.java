package gold.debug.windowstolinux.shared.ai.client;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import gold.debug.windowstolinux.shared.ai.parser.ChatCompletionResponseParser;
import gold.debug.windowstolinux.shared.ai.provider.ProviderEndpointPolicy;
import gold.debug.windowstolinux.shared.ai.transport.*;
import gold.debug.windowstolinux.shared.model.agent.*;

/** Bounded structured provider transport without deployment policy. / 不包含部署策略的有界结构化提供者传输。 */
public final class StructuredAiClient {
    /** Bounded transport. / 有界传输。 */
    private final RoleChatTransport transport;

    /** Strict JSON decoding. / 严格 JSON 解码。 */
    private final ObjectMapper json = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    /** Uses the production transport. / 使用生产传输。 */
    public StructuredAiClient() {
        this(new HttpRoleChatTransport());
    }

    /** Binds an injectable transport. / 绑定可注入传输。
     * @param transport provider transport / 提供者传输
     */
    public StructuredAiClient(RoleChatTransport transport) {
        this.transport = Objects.requireNonNull(transport);
    }

    /** Sends the exact skill and context through the existing transport. / 通过既有传输发送精确 Skill 及上下文。
     * @param endpoint provider endpoint / 提供者端点
     * @param model model identity / 模型身份
     * @param key temporary credential / 临时凭据
     * @param owner resource owner / 资源所属类
     * @param resource exact built-in prompt resource / 精确内置提示资源
     * @param context nonsecret bounded context / 非秘密有界上下文
     * @return strict output and optional token count / 严格输出及可选 Token 数
     * @throws Exception on transport or schema failure / 传输或模式失败时
     */
    public Reply<JsonNode> request(URI endpoint, String model, char[] key, Class<?> owner, String resource,
            Map<String, Object> context) throws Exception {
        var policy = new ProviderEndpointPolicy();
        policy.validateEndpoint(endpoint);
        policy.requireModel(model);
        if (key == null || key.length == 0)
            throw new IllegalArgumentException("missing credential");
        String input = json.writeValueAsString(context);
        if (input.length() > 65536)
            throw new IllegalArgumentException("agent context too large");
        String prompt;
        try (var stream = owner.getResourceAsStream(resource)) {
            if (stream == null)
                throw new IllegalStateException("missing built-in skill");
            prompt = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        String body = json.writeValueAsString(Map.of("model", model, "temperature", 0, "messages",
                List.of(Map.of("role", "system", "content", prompt), Map.of("role", "user", "content", input))));
        char[] copy = key.clone();
        try {
            RoleChatResult response = transport.send(endpoint, copy, body);
            if (response.statusCode() < 200 || response.statusCode() >= 300)
                throw new java.io.IOException("agent provider unavailable");
            String output = new ChatCompletionResponseParser().parse(response.body()).explanation();
            if (output.length() > 65536)
                throw new IllegalArgumentException("agent output too large");
            JsonNode parsed = json.readTree(output), envelope = json.readTree(response.body());
            var usage = envelope.path("usage").path("total_tokens");
            OptionalLong tokens = usage.isIntegralNumber() && usage.canConvertToLong() && usage.longValue() >= 0
                    ? OptionalLong.of(usage.longValue())
                    : OptionalLong.empty();
            return new Reply<>(parsed, tokens);
        } finally {
            Arrays.fill(copy, '\0');
        }
    }
    /** Model result with usage only when actually reported. / 模型结果，用量仅在实际返回时记录。
     * @param value parsed result / 已解析结果
     * @param tokens provider-reported total tokens / 提供者返回的 Token 总数
     * @param <T> strict output type / 严格输出类型
     */
    public record Reply<T>(T value, OptionalLong tokens) {
    }
}
