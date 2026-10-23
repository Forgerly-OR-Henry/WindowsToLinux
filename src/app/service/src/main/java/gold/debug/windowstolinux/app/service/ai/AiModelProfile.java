package gold.debug.windowstolinux.app.service.ai;

import java.net.URI;
import java.util.Objects;

import gold.debug.windowstolinux.app.db.ai.StoredAiModelProfile;
import gold.debug.windowstolinux.app.ai.http.HttpUrlChecker;
import gold.debug.windowstolinux.app.ai.voice.VoiceProtocol;
import gold.debug.windowstolinux.app.ai.model.ModelReasoningLevel;
import gold.debug.windowstolinux.app.ai.model.ModelProtocol;

/** One complete endpoint with independently verified capabilities. / 单个完整端点，分别核验各项能力。 */
public record AiModelProfile(String id, URI endpoint, String model, String credentialKey,
                             ModelReasoningLevel reasoningEffort, ModelProtocol protocol) {
    /** Validates the explicit endpoint without guessing routes. / 校验明确端点，不猜测请求路径。 */
    public AiModelProfile {
        id = Objects.requireNonNull(id).strip();
        model = Objects.requireNonNull(model).strip();
        credentialKey = Objects.requireNonNull(credentialKey).strip();
        Objects.requireNonNull(reasoningEffort);
        Objects.requireNonNull(protocol);
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
        return new StoredAiModelProfile(id, endpoint.toASCIIString(), model, credentialKey, reasoningEffort, protocol);
    }

    /** Restores an explicitly stored endpoint. / 恢复明确保存的端点。 */
    public static AiModelProfile fromStored(StoredAiModelProfile profile) {
        return new AiModelProfile(profile.id(), URI.create(profile.endpoint()), profile.model(), profile.credentialKey(),
            profile.reasoningEffort(), profile.protocol());
    }
}
