package gold.debug.windowstolinux.app.service.ai;

import java.net.URI;
import java.util.Objects;
import gold.debug.windowstolinux.app.db.ai.StoredAiProviderProfile;
import gold.debug.windowstolinux.app.ai.http.ModelHttpCall;
import gold.debug.windowstolinux.app.ai.voice.VoiceProtocol;

/** One model with explicitly configured protocol endpoints. / 同一模型及明确配置的分协议端点。
 * @param id stable identity / 稳定身份
 * @param chatCompletionsEndpoint optional chat endpoint / 可选文字端点
 * @param model provider model / 提供方模型
 * @param credentialKey opaque protected reference / 不透明受保护引用
 * @param fileTranscriptionEndpoint optional complete audio endpoint / 可选完整音频端点
 * @param realtimeTranscriptionEndpoint optional realtime endpoint / 可选实时端点 */
public record AiProviderProfile(String id, URI chatCompletionsEndpoint, String model, String credentialKey,
    URI fileTranscriptionEndpoint, URI realtimeTranscriptionEndpoint) {
    /** Checks configured protocols without assuming capability support. / 检查已配置协议，不推定能力支持。
     * @param id identity / 身份
     * @param chatCompletionsEndpoint chat address or null / 文字地址或空
     * @param model model name / 模型名
     * @param credentialKey protected reference / 受保护引用
     * @param fileTranscriptionEndpoint file address or null / 文件地址或空
     * @param realtimeTranscriptionEndpoint realtime address or null / 实时地址或空 */
    public AiProviderProfile {
        id = Objects.requireNonNull(id).strip(); model = Objects.requireNonNull(model).strip();
        credentialKey = Objects.requireNonNull(credentialKey).strip();
        if (!id.matches("[a-z][a-z0-9-]{0,63}") || !model.matches("[A-Za-z0-9._:/-]{1,128}")
            || !credentialKey.matches("ai/[a-z0-9/_-]{1,127}")
            || chatCompletionsEndpoint == null && fileTranscriptionEndpoint == null && realtimeTranscriptionEndpoint == null)
            throw new IllegalArgumentException("Invalid model settings");
        if (chatCompletionsEndpoint != null) ModelHttpCall.validateEndpoint(chatCompletionsEndpoint);
        if (fileTranscriptionEndpoint != null) ModelHttpCall.validateEndpoint(fileTranscriptionEndpoint);
        if (realtimeTranscriptionEndpoint != null) VoiceProtocol.validateEndpoint(realtimeTranscriptionEndpoint);
    }
    /** Creates chat-only settings. / 创建仅文字设置。
     * @param id identity / 身份
     * @param chatCompletionsEndpoint chat address / 文字地址
     * @param model model name / 模型名
     * @param credentialKey protected reference / 受保护引用 */
    public AiProviderProfile(String id, URI chatCompletionsEndpoint, String model, String credentialKey) {
        this(id, chatCompletionsEndpoint, model, credentialKey, null, null);
    }
    /** Converts optional addresses to their storage representation. / 将可选地址转成存储表达。
     * @return non-secret metadata / 非秘密元数据 */
    public StoredAiProviderProfile stored() {
        return new StoredAiProviderProfile(id, address(chatCompletionsEndpoint), model, credentialKey,
            address(fileTranscriptionEndpoint), address(realtimeTranscriptionEndpoint));
    }
    /** Restores explicit saved protocol addresses. / 恢复明确保存的协议地址。
     * @param profile saved metadata / 已存元数据
     * @return validated settings / 已校验设置 */
    public static AiProviderProfile fromStored(StoredAiProviderProfile profile) {
        return new AiProviderProfile(profile.id(), uri(profile.endpoint()), profile.model(), profile.credentialKey(),
            uri(profile.fileEndpoint()), uri(profile.realtimeEndpoint()));
    }
    /** Encodes an optional URI without guessing a path. / 编码可选URI，不猜测路径。
     * @param value optional address / 可选地址
     * @return saved address or empty / 已存地址或空 */
    private static String address(URI value) { return value == null ? "" : value.toASCIIString(); }
    /** Decodes an explicitly configured address. / 解码明确配置的地址。
     * @param value saved address / 已存地址
     * @return configured URI or null / 已配置URI或空 */
    private static URI uri(String value) { return value.isEmpty() ? null : URI.create(value); }
}
