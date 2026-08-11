package gold.debug.windowstolinux.app.service.ai;

import gold.debug.windowstolinux.app.db.entity.StoredAiProfile;
import gold.debug.windowstolinux.shared.model.security.CredentialStorageMode;

import java.net.URI;
import java.util.Objects;

/**
 * Saved non-secret OpenAI-compatible metadata for optional structural explanations.
 *
 * <p>为可选结构解释保存的非秘密 OpenAI 兼容元数据。
 *
 * @param chatCompletionsEndpoint the {@code chatCompletionsEndpoint} value / {@code chatCompletionsEndpoint} 值
 * @param model the {@code model} value / {@code model} 值
 * @param credentialKey the {@code credentialKey} value / {@code credentialKey} 值
 * @param credentialMode the {@code credentialMode} value / {@code credentialMode} 值
 */
public record AiProfile(URI chatCompletionsEndpoint, String model, String credentialKey, CredentialStorageMode credentialMode) {
    /**
     * Creates a {@code AiProfile} instance.
     *
     * <p>创建 {@code AiProfile} 实例。
     *
     * @param chatCompletionsEndpoint the {@code chatCompletionsEndpoint} value / {@code chatCompletionsEndpoint} 值
     * @param model the {@code model} value / {@code model} 值
     * @param credentialKey the {@code credentialKey} value / {@code credentialKey} 值
     * @param credentialMode the {@code credentialMode} value / {@code credentialMode} 值
     * @throws IllegalArgumentException if an argument violates the required constraints / 参数违反必要约束时
     * @throws NullPointerException if a required argument is {@code null} / 必要参数为 {@code null} 时
     */
    public AiProfile {
        if (chatCompletionsEndpoint == null || chatCompletionsEndpoint.getHost() == null
                || chatCompletionsEndpoint.getUserInfo() != null || chatCompletionsEndpoint.getQuery() != null
                || chatCompletionsEndpoint.getFragment() != null
                || !("https".equalsIgnoreCase(chatCompletionsEndpoint.getScheme())
                || ("http".equalsIgnoreCase(chatCompletionsEndpoint.getScheme()) && !isLoopback(chatCompletionsEndpoint.getHost())))) {
            throw new IllegalArgumentException("chatCompletionsEndpoint must be a plain HTTP(S) URL");
        }
        model = Objects.requireNonNull(model, "model").trim();
        credentialKey = Objects.requireNonNull(credentialKey, "credentialKey").trim();
        credentialMode = Objects.requireNonNull(credentialMode, "credentialMode");
        if (!model.matches("[A-Za-z0-9._:/-]{1,128}") || !credentialKey.matches("ai/[a-z0-9/_-]{1,127}")) {
            throw new IllegalArgumentException("AI profile is invalid");
        }
    }

    /**
     * Stores data through {@code stored}.
     *
     * <p>通过 {@code stored} 保存数据。
     *
     * @return the operation result / 操作结果
     */
    public StoredAiProfile stored() {
        return new StoredAiProfile(chatCompletionsEndpoint.toASCIIString(), model, credentialKey, credentialMode.name());
    }

    /**
     * Creates a value through {@code fromStored}.
     *
     * <p>通过 {@code fromStored} 创建值。
     *
     * @param profile the {@code profile} value / {@code profile} 值
     * @return the operation result / 操作结果
     */
    public static AiProfile fromStored(StoredAiProfile profile) {
        return new AiProfile(URI.create(profile.endpoint()), profile.model(), profile.credentialKey(),
                CredentialStorageMode.valueOf(profile.credentialMode()));
    }

    private static boolean isLoopback(String host) {
        String normalized = host.toLowerCase(java.util.Locale.ROOT);
        return normalized.equals("localhost") || normalized.equals("127.0.0.1") || normalized.equals("::1");
    }
}
