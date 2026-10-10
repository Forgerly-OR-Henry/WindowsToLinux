package gold.debug.windowstolinux.app.db.ai;

import java.util.Objects;

import gold.debug.windowstolinux.app.ai.model.ModelReasoningLevel;
import gold.debug.windowstolinux.app.ai.model.ModelProtocol;

/** One model's optional protocol addresses and opaque credential reference. / 同一模型的可选协议地址及不透明凭据引用。
 * @param id stable model identity / 稳定模型身份
 * @param endpoint optional chat address / 可选文字地址
 * @param model provider model identifier / 提供方模型标识
 * @param credentialKey opaque protected credential reference / 不透明受保护凭据引用
 * @param fileEndpoint optional complete-audio address / 可选完整音频地址
 * @param realtimeEndpoint optional realtime address / 可选实时地址
 * @param reasoningEffort optional text generation setting / 可选文字生成设置 */
public record StoredAiModelProfile(String id, String endpoint, String model, String credentialKey,
                                   String fileEndpoint, String realtimeEndpoint, ModelReasoningLevel reasoningEffort,
                                   ModelProtocol protocol) {
    public StoredAiModelProfile(String id, String endpoint, String model, String credentialKey,
                                String fileEndpoint, String realtimeEndpoint, ModelReasoningLevel reasoningEffort) {
        this(id, endpoint, model, credentialKey, fileEndpoint, realtimeEndpoint, reasoningEffort,
            ModelProtocol.OPENAI_CHAT_COMPLETIONS);
    }

    /** Validates one model without requiring chat support. / 校验模型，不要求支持文字。
     * @param id identity / 身份
     * @param endpoint chat address or empty / 文字地址或空
     * @param model model name / 模型名
     * @param credentialKey protected key reference / 受保护凭据引用
     * @param fileEndpoint file address or empty / 文件地址或空
     * @param realtimeEndpoint realtime address or empty / 实时地址或空
     * @param reasoningEffort optional text generation setting / 可选文字生成设置 */
    public StoredAiModelProfile {
        id = text(id);
        endpoint = text(endpoint);
        model = text(model);
        credentialKey = text(credentialKey);
        fileEndpoint = text(fileEndpoint);
        realtimeEndpoint = text(realtimeEndpoint);
        Objects.requireNonNull(reasoningEffort);
        Objects.requireNonNull(protocol);
        if (!id.matches("[a-z][a-z0-9-]{0,63}") || model.isEmpty() || credentialKey.isEmpty()
            || endpoint.isEmpty() && fileEndpoint.isEmpty() && realtimeEndpoint.isEmpty())
            throw new IllegalArgumentException("Invalid model connection metadata");
    }

    /** Creates a chat-only configuration. / 创建仅文字配置。
     * @param id identity / 身份
     * @param endpoint chat endpoint / 文字端点
     * @param model provider model / 提供方模型
     * @param credentialKey protected reference / 受保护引用 */
    public StoredAiModelProfile(String id, String endpoint, String model, String credentialKey) {
        this(id, endpoint, model, credentialKey, "", "");
    }

    /** Preserves provider defaults for existing protocol configurations. / 为已有协议配置保留提供者默认值。
     * @param id identity / 身份
     * @param endpoint chat address / 文字地址
     * @param model provider model / 提供者模型
     * @param credentialKey protected reference / 受保护引用
     * @param fileEndpoint file address / 文件地址
     * @param realtimeEndpoint realtime address / 实时地址 */
    public StoredAiModelProfile(String id, String endpoint, String model, String credentialKey,
                                String fileEndpoint, String realtimeEndpoint) {
        this(id, endpoint, model, credentialKey, fileEndpoint, realtimeEndpoint, ModelReasoningLevel.DEFAULT);
    }

    /** Normalizes non-secret metadata. / 规范化非秘密元数据。
     * @param value required string / 必需字符串
     * @return trimmed string / 去空白字符串 */
    private static String text(String value) {
        return Objects.requireNonNull(value).strip();
    }
}
