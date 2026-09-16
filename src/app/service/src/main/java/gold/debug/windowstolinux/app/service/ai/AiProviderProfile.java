package gold.debug.windowstolinux.app.service.ai;

import gold.debug.windowstolinux.app.db.entity.StoredAiProviderProfile;
import gold.debug.windowstolinux.shared.model.security.CredentialStorageMode;

import java.net.URI;
import java.util.Objects;

/**
 * Named non-secret AI provider metadata; runtime order is owned by the global invocation chain.
 *
 * <p>命名的非秘密 AI 提供者元数据，运行顺序由全局调用链负责。
 *
 * @param id stable selection identifier / 稳定选择标识
 * @param chatCompletionsEndpoint OpenAI-compatible endpoint / OpenAI 兼容端点
 * @param model selected model / 所选模型
 * @param credentialKey platform-secret-store key / 平台秘密存储键
 * @param credentialMode selected credential storage mode / 所选凭据存储模式
 */
public record AiProviderProfile(
        String id,
        URI chatCompletionsEndpoint,
        String model,
        String credentialKey,
        CredentialStorageMode credentialMode
) {
    /**
     * Creates an {@code AiProviderProfile} instance.
     *
     * <p>创建 {@code AiProviderProfile} 实例。
     */
    public AiProviderProfile {
        id = Objects.requireNonNull(id, "id").trim();
        if (!id.matches("[a-z][a-z0-9-]{0,63}")) {
            throw new IllegalArgumentException("id must be a lowercase provider identifier");
        }
        new AiProfile(chatCompletionsEndpoint, model, credentialKey, credentialMode);
    }

    /**
     * Converts this named metadata to the existing selected-provider client shape.
     *
     * <p>将此命名元数据转换为既有的所选提供者客户端形态。
     */
    public AiProfile selectedProfile() {
        return new AiProfile(chatCompletionsEndpoint, model, credentialKey, credentialMode);
    }

    /**
     * Converts this profile to non-secret database metadata.
     *
     * <p>将此配置转换为非秘密数据库元数据。
     */
    public StoredAiProviderProfile stored() {
        return new StoredAiProviderProfile(id, chatCompletionsEndpoint.toASCIIString(), model, credentialKey, credentialMode.name());
    }

    /**
     * Creates a named profile from non-secret database metadata.
     *
     * <p>从非秘密数据库元数据创建命名配置。
     */
    public static AiProviderProfile fromStored(StoredAiProviderProfile profile) {
        return new AiProviderProfile(profile.id(), URI.create(profile.endpoint()), profile.model(), profile.credentialKey(),
                CredentialStorageMode.valueOf(profile.credentialMode()));
    }
}
