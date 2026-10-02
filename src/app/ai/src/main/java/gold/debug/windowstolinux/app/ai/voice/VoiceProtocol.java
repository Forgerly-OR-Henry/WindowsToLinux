package gold.debug.windowstolinux.app.ai.voice;

import java.io.IOException;
import java.net.URI;
import java.util.*;
import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import gold.debug.windowstolinux.appapi.ai.*;
import gold.debug.windowstolinux.appapi.task.*;

/** Bounded OpenAI transcription protocol values without business routing. / 不含业务路由的有界 OpenAI 转写协议值。 */
public final class VoiceProtocol {
    /** Maximum bytes of one provider event or file response. / 单个提供方事件或文件响应字节上限。 */
    public static final int MAX_RESPONSE_BYTES = 1_048_576;
    /** Strict shared JSON parser. / 严格共用 JSON 解析器。 */
    static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder().streamReadConstraints(
        StreamReadConstraints.builder().maxNestingDepth(16).maxStringLength(MAX_RESPONSE_BYTES)
            .maxNumberLength(32).build()).build()).enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private VoiceProtocol() { }

    /** Checks the configured WebSocket URL; only intent=transcription may appear in its query. / 检查已配置 WebSocket 地址；查询参数只允许 intent=transcription。
     * @param endpoint complete configured endpoint / 已配置完整端点 */
    public static void validateEndpoint(URI endpoint) {
        if (endpoint == null || endpoint.getHost() == null || endpoint.getUserInfo() != null
            || endpoint.getRawFragment() != null || !("wss".equalsIgnoreCase(endpoint.getScheme())
            || "ws".equalsIgnoreCase(endpoint.getScheme()))
            || endpoint.getRawQuery() != null && !endpoint.getRawQuery().equals("intent=transcription"))
            throw new IllegalArgumentException("Realtime endpoint must be complete WS(S), without credentials or fragment");
        if ("ws".equalsIgnoreCase(endpoint.getScheme()) && !Set.of("localhost", "127.0.0.1", "::1", "[::1]")
            .contains(endpoint.getHost().toLowerCase(Locale.ROOT)))
            throw new IllegalArgumentException("Remote realtime endpoints require WSS");
    }

    /** Builds the explicit transcription intent URL without changing host or path. / 构造明确转写用途地址，不改变主机或路径。
     * @param endpoint approved complete realtime address / 已审阅完整实时地址
     * @return same address with required intent / 带必需 intent 的同一地址 */
    public static URI transcriptionEndpoint(URI endpoint) {
        validateEndpoint(endpoint);
        return endpoint.getRawQuery() == null ? URI.create(endpoint.toASCIIString() + "?intent=transcription") : endpoint;
    }

    /** Parses complete JSON text, rejecting an absent transcript. / 解析完整 JSON 文字，拒绝缺少转写结果。
     * @param bytes bounded provider JSON / 有界提供方 JSON
     * @param modelId platform selection / 平台所选身份
     * @return actual file transcript / 实际文件转写
     * @throws IOException on invalid provider response / 提供方响应无效时 */
    public static VoiceResponse decode(byte[] bytes, String modelId) throws IOException {
        var root = JSON.readTree(bytes);
        if (root == null || !root.isObject() || !root.path("text").isTextual())
            throw new IOException("Missing transcript");
        String text = root.path("text").textValue();
        if (text.length() > MAX_RESPONSE_BYTES)
            throw new IOException("Transcript exceeds bound");
        return new VoiceResponse(text, modelId, List.of());
    }

    /** Recognizes only explicit protocol incompatibility codes. / 只识别协议明确不兼容错误码。 */
    static boolean unsupported(JsonNode error) {
        return Set.of("unsupported_model", "unsupported_audio", "unsupported_value").contains(error.path("code").asText());
    }

    /** Safe diagnostic without audio, transcripts or credentials. / 不含音频、转写或凭据的安全诊断。
     * @param outcome actual transport outcome / 实际传输结果
     * @param code stable category / 稳定类别
     * @param event stable event / 稳定事件
     * @param message safe explanation / 安全说明
     * @param modelId selected profile / 所选配置
     * @param confirmed already confirmed parts / 已确认片段
     * @return classified result / 已分类结果 */
    public static OperationResult<VoiceResponse> failure(Outcome outcome, ProblemCode code, String event,
        String message, String modelId, Optional<VoiceResponse> confirmed) {
        return new OperationResult<>(outcome, confirmed, Optional.of(
            new Problem(code, event, message, Map.of("modelId", modelId), Optional.empty())));
    }
}
