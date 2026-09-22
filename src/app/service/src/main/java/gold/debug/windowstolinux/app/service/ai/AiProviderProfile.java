package gold.debug.windowstolinux.app.service.ai;

import java.net.URI;
import java.util.Objects;

import gold.debug.windowstolinux.app.db.entity.StoredAiProviderProfile;
import gold.debug.windowstolinux.shared.model.security.CredentialStorageMode;

/**
 * Named non-secret AI provider metadata; runtime order is owned by the global invocation chain.
 *
 *  <p>命名的非秘密 AI 提供者元数据，运行顺序由全局调用链负责。
 *
 * @param id stable selection identifier / 稳定选择标识
 * @param chatCompletionsEndpoint OpenAI-compatible endpoint / OpenAI 兼容端点
 * @param model selected model / 所选模型
 * @param credentialKey platform-secret-store key / 平台秘密存储键
 * @param credentialMode selected credential storage mode / 所选凭据存储模式
 */
public record AiProviderProfile(String id, URI chatCompletionsEndpoint, String model, String credentialKey,
        CredentialStorageMode credentialMode) {
    /**
     * Validates and binds the inputs required by ai provider profile.
     * <p>校验并绑定AI提供者配置资料所需输入。
     *
     * @param id stable identifier within the owning registry / 所属登记表内的稳定标识
     * @param chatCompletionsEndpoint OpenAI-compatible endpoint / OpenAI 兼容端点
     * @param model configured model identifier sent to the provider / 发送给提供者的已配置模型标识
     * @param credentialKey opaque lookup key in the platform secret store / 平台秘密存储中的不透明查找键
     * @param credentialMode selected platform credential-storage mode / 所选平台凭据存储模式
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public AiProviderProfile {
        id = Objects.requireNonNull(id, "id").trim();
        if (!id.matches("[a-z][a-z0-9-]{0,63}")) {
            throw new IllegalArgumentException("id must be a lowercase provider identifier");
        }
        if (chatCompletionsEndpoint == null || chatCompletionsEndpoint.getHost() == null
                || chatCompletionsEndpoint.getUserInfo() != null || chatCompletionsEndpoint.getQuery() != null
                || chatCompletionsEndpoint.getFragment() != null
                || !("https".equalsIgnoreCase(chatCompletionsEndpoint.getScheme())
                        || ("http".equalsIgnoreCase(chatCompletionsEndpoint.getScheme())
                                && !isLoopback(chatCompletionsEndpoint.getHost())))) {
            throw new IllegalArgumentException("chatCompletionsEndpoint must be a plain HTTP(S) URL");
        }
        String checkedModel = Objects.requireNonNull(model, "model").trim();
        String checkedCredentialKey = Objects.requireNonNull(credentialKey, "credentialKey").trim();
        credentialMode = Objects.requireNonNull(credentialMode, "credentialMode");
        if (!checkedModel.matches("[A-Za-z0-9._:/-]{1,128}")
                || !checkedCredentialKey.matches("ai/[a-z0-9/_-]{1,127}")) {
            throw new IllegalArgumentException("AI profile is invalid");
        }
    }

    /**
     * Converts this profile to non-secret database metadata.
     *
     *  <p>将此配置转换为非秘密数据库元数据。
     *
     * @return constructed or resolved stored ai provider profile / 构造或解析得到的已存储AI提供者配置资料
     */
    public StoredAiProviderProfile stored() {
        return new StoredAiProviderProfile(id, chatCompletionsEndpoint.toASCIIString(), model, credentialKey,
                credentialMode.name());
    }

    /**
     * Creates a named profile from non-secret database metadata.
     *
     *  <p>从非秘密数据库元数据创建命名配置。
     *
     * @param profile connection or provider settings supplied to the operation / 提供给操作的连接或提供者设置
     * @return a named profile from non-secret database metadata / 从非秘密数据库元数据创建命名配置
     */
    public static AiProviderProfile fromStored(StoredAiProviderProfile profile) {
        return new AiProviderProfile(profile.id(), URI.create(profile.endpoint()), profile.model(),
                profile.credentialKey(), CredentialStorageMode.valueOf(profile.credentialMode()));
    }

    /**
     * Reports whether the loopback condition holds for this contract.
     * <p>判断当前契约是否满足回环条件。
     *
     * @param host reviewed server hostname or IP address / 已审阅服务器主机名或 IP 地址
     * @return true when loopback condition holds for this contract, false otherwise / 当前契约是否满足回环条件时为 true，否则为 false
     */
    private static boolean isLoopback(String host) {
        String normalized = host.toLowerCase(java.util.Locale.ROOT);
        return normalized.equals("localhost") || normalized.equals("127.0.0.1") || normalized.equals("::1");
    }
}
