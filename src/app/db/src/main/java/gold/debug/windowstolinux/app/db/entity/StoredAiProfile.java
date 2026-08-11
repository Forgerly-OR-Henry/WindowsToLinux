package gold.debug.windowstolinux.app.db.entity;

import java.util.Objects;

/**
 * Non-secret OpenAI-compatible endpoint metadata; the API key remains in app/secret.
 *
 * <p>非秘密 OpenAI 兼容端点元数据；API 密钥保留在 app/secret 中。
 *
 * @param endpoint the {@code endpoint} value / {@code endpoint} 值
 * @param model the {@code model} value / {@code model} 值
 * @param credentialKey the {@code credentialKey} value / {@code credentialKey} 值
 * @param credentialMode the {@code credentialMode} value / {@code credentialMode} 值
 */
public record StoredAiProfile(String endpoint, String model, String credentialKey, String credentialMode) {
    /**
     * Creates a {@code StoredAiProfile} instance.
     *
     * <p>创建 {@code StoredAiProfile} 实例。
     *
     * @param endpoint the {@code endpoint} value / {@code endpoint} 值
     * @param model the {@code model} value / {@code model} 值
     * @param credentialKey the {@code credentialKey} value / {@code credentialKey} 值
     * @param credentialMode the {@code credentialMode} value / {@code credentialMode} 值
     */
    public StoredAiProfile {
        endpoint = requireText(endpoint, "endpoint");
        model = requireText(model, "model");
        credentialKey = requireText(credentialKey, "credentialKey");
        credentialMode = requireText(credentialMode, "credentialMode");
    }

    private static String requireText(String value, String name) {
        value = Objects.requireNonNull(value, name).trim();
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
