package gold.debug.windowstolinux.app.service.ai;

import java.net.URI;
import java.util.Objects;

import gold.debug.windowstolinux.app.db.entity.StoredAiProviderProfile;

/**
 * Named non-secret AI provider metadata; runtime order is owned by the global invocation chain.
 *
 *  <p>命名的非秘密 AI 提供者元数据，运行顺序由全局调用链负责。
 *
 * @param id stable selection identifier / 稳定选择标识
 * @param chatCompletionsEndpoint OpenAI-compatible endpoint / OpenAI 兼容端点
 * @param model selected model / 所选模型
 * @param credentialKey platform-secret-store key / 平台秘密存储键
 */
public record AiProviderProfile(String id, URI chatCompletionsEndpoint, String model, String credentialKey) {
    /**
     * Validates and binds the inputs required by ai provider profile.
     * <p>校验并绑定AI提供者配置资料所需输入。
     *
     * @param id stable identifier within the owning registry / 所属登记表内的稳定标识
     * @param chatCompletionsEndpoint OpenAI-compatible endpoint / OpenAI 兼容端点
     * @param model configured model identifier sent to the provider / 发送给提供者的已配置模型标识
     * @param credentialKey opaque lookup key in the platform secret store / 平台秘密存储中的不透明查找键
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public AiProviderProfile {
        id = Objects.requireNonNull(id, "id").trim();
        if (!id.matches("[a-z][a-z0-9-]{0,63}")) {
            throw new IllegalArgumentException("id must be a lowercase provider identifier");
        }
        gold.debug.windowstolinux.app.ai.transport.ModelHttpCall.validateEndpoint(chatCompletionsEndpoint);
        model = Objects.requireNonNull(model, "model").trim();
        credentialKey = Objects.requireNonNull(credentialKey, "credentialKey").trim();

        if (!model.matches("[A-Za-z0-9._:/-]{1,128}") || !credentialKey.matches("ai/[a-z0-9/_-]{1,127}")) {
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
        return new StoredAiProviderProfile(id, chatCompletionsEndpoint.toASCIIString(), model, credentialKey);
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
                profile.credentialKey());
    }

}
