package gold.debug.windowstolinux.app.db.entity;

import java.util.Objects;

/**
 * Named non-secret AI provider metadata; only its opaque credential-store key is persisted.
 *
 *  <p>命名的非秘密 AI 提供者元数据；仅持久化其不透明的凭据存储键。
 *
 * @param id stable profile identifier / 稳定配置标识
 * @param endpoint OpenAI-compatible endpoint / OpenAI 兼容端点
 * @param model selected model name / 所选模型名称
 * @param credentialKey platform-secret-store key / 平台秘密存储键
 * @param credentialMode selected platform-secret-store mode / 已选择的平台秘密存储模式
 */
public record StoredAiProviderProfile(String id, String endpoint, String model, String credentialKey,
        String credentialMode) {
    /**
     * Validates and binds the inputs required by stored ai provider profile.
     * <p>校验并绑定已存储AI提供者配置资料所需输入。
     *
     * @param id stable identifier within the owning registry / 所属登记表内的稳定标识
     * @param endpoint reviewed network endpoint / 已审阅网络端点
     * @param model configured model identifier sent to the provider / 发送给提供者的已配置模型标识
     * @param credentialKey opaque lookup key in the platform secret store / 平台秘密存储中的不透明查找键
     * @param credentialMode selected platform credential-storage mode / 所选平台凭据存储模式
     */
    public StoredAiProviderProfile {
        id = requireIdentifier(id, "id");
        endpoint = requireText(endpoint, "endpoint");
        model = requireText(model, "model");
        credentialKey = requireText(credentialKey, "credentialKey");
        credentialMode = requireText(credentialMode, "credentialMode");
    }

    /**
     * Validates and returns the stable secret identifier and rejects inputs outside the declared constraints.
     * <p>校验并返回稳定的秘密标识并拒绝超出已声明约束的输入。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     * @param name human-readable name or diagnostic field label / 可读名称或诊断字段标签
     * @return require identifier text / 要求标识文本
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     */
    private static String requireIdentifier(String value, String name) {
        value = requireText(value, name);
        if (!value.matches("[a-z][a-z0-9-]{0,63}")) {
            throw new IllegalArgumentException(name + " must be a lowercase identifier");
        }
        return value;
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
