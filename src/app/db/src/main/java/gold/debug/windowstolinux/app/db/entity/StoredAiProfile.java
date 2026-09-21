package gold.debug.windowstolinux.app.db.entity;

import java.util.Objects;

/**
 * Non-secret OpenAI-compatible endpoint metadata; the API key remains in app/secret.
 *
 *  <p>非秘密 OpenAI 兼容端点元数据；API 密钥保留在 app/secret 中。
 *
 * @param endpoint reviewed network endpoint / 已审阅网络端点
 * @param model configured model identifier sent to the provider / 发送给提供者的已配置模型标识
 * @param credentialKey opaque lookup key in the platform secret store / 平台秘密存储中的不透明查找键
 * @param credentialMode selected platform credential-storage mode / 所选平台凭据存储模式
 */
public record StoredAiProfile(String endpoint, String model, String credentialKey, String credentialMode) {
    /**
     * Validates and binds the inputs required by stored ai profile.
     * <p>校验并绑定已存储AI配置资料所需输入。
     *
     * @param endpoint reviewed network endpoint / 已审阅网络端点
     * @param model configured model identifier sent to the provider / 发送给提供者的已配置模型标识
     * @param credentialKey opaque lookup key in the platform secret store / 平台秘密存储中的不透明查找键
     * @param credentialMode selected platform credential-storage mode / 所选平台凭据存储模式
     */
    public StoredAiProfile {
        endpoint = requireText(endpoint, "endpoint");
        model = requireText(model, "model");
        credentialKey = requireText(credentialKey, "credentialKey");
        credentialMode = requireText(credentialMode, "credentialMode");
    }

    /**
     * Trims required text and rejects missing or invalid content.
     * <p>去除必填文本首尾空白，并拒绝缺失或无效内容。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     * @param name human-readable name or diagnostic field label / 可读名称或诊断字段标签
     * @return require text text / 要求文本文本
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    private static String requireText(String value, String name) {
        value = Objects.requireNonNull(value, name).trim();
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
