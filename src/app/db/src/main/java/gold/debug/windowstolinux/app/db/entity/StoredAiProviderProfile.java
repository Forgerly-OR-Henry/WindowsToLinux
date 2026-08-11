package gold.debug.windowstolinux.app.db.entity;

import java.util.Objects;

/**
 * Named non-secret AI provider metadata; only its opaque credential-store key is persisted.
 *
 * <p>命名的非秘密 AI 提供者元数据；仅持久化其不透明的凭据存储键。
 *
 * @param id stable profile identifier / 稳定配置标识
 * @param endpoint OpenAI-compatible endpoint / OpenAI 兼容端点
 * @param model selected model name / 所选模型名称
 * @param credentialKey platform-secret-store key / 平台秘密存储键
 * @param credentialMode selected platform-secret-store mode / 已选择的平台秘密存储模式
 */
public record StoredAiProviderProfile(
        String id,
        String endpoint,
        String model,
        String credentialKey,
        String credentialMode
) {
    /**
     * Creates a {@code StoredAiProviderProfile} instance.
     *
     * <p>创建 {@code StoredAiProviderProfile} 实例。
     */
    public StoredAiProviderProfile {
        id = requireIdentifier(id, "id");
        endpoint = requireText(endpoint, "endpoint");
        model = requireText(model, "model");
        credentialKey = requireText(credentialKey, "credentialKey");
        credentialMode = requireText(credentialMode, "credentialMode");
    }

    private static String requireIdentifier(String value, String name) {
        value = requireText(value, name);
        if (!value.matches("[a-z][a-z0-9-]{0,63}")) {
            throw new IllegalArgumentException(name + " must be a lowercase identifier");
        }
        return value;
    }

    private static String requireText(String value, String name) {
        value = Objects.requireNonNull(value, name).trim();
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
