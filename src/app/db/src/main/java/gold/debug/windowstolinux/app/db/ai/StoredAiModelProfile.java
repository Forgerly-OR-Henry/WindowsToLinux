package gold.debug.windowstolinux.app.db.ai;

import java.util.Objects;

import gold.debug.windowstolinux.app.ai.model.ModelReasoningLevel;
import gold.debug.windowstolinux.app.ai.model.ModelProtocol;

/** Single complete endpoint and opaque credential reference. / 单个完整端点及不透明凭据引用。 */
public record StoredAiModelProfile(String id, String endpoint, String model, String credentialKey,
                                   ModelReasoningLevel reasoningEffort, ModelProtocol protocol) {
    /** Rejects absent model identities or endpoints. / 拒绝缺少模型身份或端点。 */
    public StoredAiModelProfile {
        id = Objects.requireNonNull(id).strip();
        endpoint = Objects.requireNonNull(endpoint).strip();
        model = Objects.requireNonNull(model).strip();
        credentialKey = Objects.requireNonNull(credentialKey).strip();
        Objects.requireNonNull(reasoningEffort);
        Objects.requireNonNull(protocol);
        if (!id.matches("[a-z][a-z0-9-]{0,63}") || endpoint.isEmpty() || model.isEmpty() || credentialKey.isEmpty())
            throw new IllegalArgumentException("Invalid model connection metadata");
    }

    /** Uses explicit provider defaults. / 使用明确的提供方默认配置。 */
    public StoredAiModelProfile(String id, String endpoint, String model, String credentialKey) {
        this(id, endpoint, model, credentialKey, ModelReasoningLevel.DEFAULT, ModelProtocol.OPENAI_CHAT_COMPLETIONS);
    }

    /** Selects reasoning with the default wire protocol. / 选择推理强度及默认线协议。 */
    public StoredAiModelProfile(String id, String endpoint, String model, String credentialKey, ModelReasoningLevel effort) {
        this(id, endpoint, model, credentialKey, effort, ModelProtocol.OPENAI_CHAT_COMPLETIONS);
    }
}
