package gold.debug.windowstolinux.app.service.ai;

import java.net.URI;
import java.util.Objects;
import java.util.Map;

import gold.debug.windowstolinux.app.ai.provider.ModelProviderType;
import gold.debug.windowstolinux.appapi.ai.model.AiPurpose;
import gold.debug.windowstolinux.appapi.ai.model.AiReasoningMode;

import gold.debug.windowstolinux.app.db.ai.StoredAiModelProfile;
import gold.debug.windowstolinux.app.ai.http.HttpUrlChecker;
import gold.debug.windowstolinux.app.ai.voice.VoiceProtocol;
import gold.debug.windowstolinux.app.ai.model.ModelReasoningLevel;
import gold.debug.windowstolinux.app.ai.model.ModelProtocol;

/** One complete endpoint with independently verified capabilities. / 单个完整端点，分别核验各项能力。
 * @param id exact platform model configuration identity / 精确平台模型配置标识
 * @param endpoint captured endpoint at this validated model boundary / 此已校验模型边界捕获的 endpoint
 * @param model configured model identity or captured model metadata / 已配置型号或捕获的模型信息
 * @param credentialKey captured credentialKey at this validated model boundary / 此已校验模型边界捕获的 credentialKey
 * @param reasoningEffort legacy single-value migration seed / 旧单值迁移初值
 * @param protocol explicit wire protocol / 显式线协议
 * @param provider explicit provider adapter identity / 显式厂商适配标识
 * @param reasoningDefaults independent defaults for language, approval and vision / 语言、审批和视觉的独立默认值
 */
public record AiModelProfile(String id, URI endpoint, String model, String credentialKey,
                             ModelReasoningLevel reasoningEffort, ModelProtocol protocol,
                             ModelProviderType provider, Map<AiPurpose, AiReasoningMode> reasoningDefaults) {
    /** Captures inputs at the AiModelProfile ownership boundary. / 在 AiModelProfile 所有权边界捕获输入。
     * @param id exact platform model configuration identity / 精确平台模型配置标识
     * @param endpoint captured endpoint at this validated model boundary / 此已校验模型边界捕获的 endpoint
     * @param model configured model identity or captured model metadata / 已配置型号或捕获的模型信息
     * @param credentialKey captured credentialKey at this validated model boundary / 此已校验模型边界捕获的 credentialKey
     * @param reasoningEffort legacy single-value migration seed / 旧单值迁移初值
     * @param protocol explicit wire protocol / 显式线协议
     */
    public AiModelProfile(String id, URI endpoint, String model, String credentialKey,
                          ModelReasoningLevel reasoningEffort, ModelProtocol protocol) {
        this(id, endpoint, model, credentialKey, reasoningEffort, protocol, ModelProviderType.THIRD_PARTY, reasoningEffort.defaults());
    }

    /** Validates the explicit connection and freezes independent purpose choices. / 校验显式连接并冻结独立用途选择。
     * @param id platform configuration identity / 平台配置标识
     * @param endpoint complete configured endpoint / 完整配置端点
     * @param model provider model identifier / 提供方型号
     * @param credentialKey opaque credential reference / 不透明凭据引用
     * @param reasoningEffort legacy migration seed / 旧值迁移初值
     * @param protocol explicit wire protocol / 显式线协议
     * @param provider explicit manufacturer adapter / 显式厂商适配
     * @param reasoningDefaults independent text and vision purpose preferences / 独立文字及视觉用途偏好
     */
    public AiModelProfile {
        id = Objects.requireNonNull(id).strip();
        model = Objects.requireNonNull(model).strip();
        credentialKey = Objects.requireNonNull(credentialKey).strip();
        Objects.requireNonNull(reasoningEffort);
        Objects.requireNonNull(protocol);
        Objects.requireNonNull(provider);
        reasoningDefaults = Map.copyOf(reasoningDefaults);
        if (reasoningDefaults.containsKey(AiPurpose.VOICE))
            throw new IllegalArgumentException("Voice has no reasoning setting");
        Objects.requireNonNull(endpoint);
        if (!id.matches("[a-z][a-z0-9-]{0,63}") || !model.matches("[A-Za-z0-9._:/-]{1,128}")
            || !credentialKey.matches("ai/[a-z0-9/_-]{1,127}"))
            throw new IllegalArgumentException("Invalid model settings");
        if ("ws".equalsIgnoreCase(endpoint.getScheme()) || "wss".equalsIgnoreCase(endpoint.getScheme()))
            VoiceProtocol.validateEndpoint(endpoint);
        else HttpUrlChecker.validate(endpoint);
    }

    /** Uses explicit provider defaults. / 使用明确的提供方默认配置。 */
    public AiModelProfile(String id, URI endpoint, String model, String credentialKey) {
        this(id, endpoint, model, credentialKey, ModelReasoningLevel.DEFAULT, ModelProtocol.OPENAI_CHAT_COMPLETIONS);
    }

    /** Selects reasoning with the default wire protocol. / 选择推理强度及默认线协议。 */
    public AiModelProfile(String id, URI endpoint, String model, String credentialKey, ModelReasoningLevel effort) {
        this(id, endpoint, model, credentialKey, effort, ModelProtocol.OPENAI_CHAT_COMPLETIONS);
    }

    /** Converts non-secret configuration for storage. / 转换非秘密配置供持久保存。 */
    public StoredAiModelProfile stored() {
        return new StoredAiModelProfile(id, endpoint.toASCIIString(), model, credentialKey, reasoningEffort, protocol, provider, reasoningDefaults);
    }

    /** Restores an explicitly stored endpoint. / 恢复明确保存的端点。 */
    public static AiModelProfile fromStored(StoredAiModelProfile profile) {
        return new AiModelProfile(profile.id(), URI.create(profile.endpoint()), profile.model(), profile.credentialKey(),
            profile.reasoningEffort(), profile.protocol(), profile.provider(), profile.reasoningDefaults());
    }
}
