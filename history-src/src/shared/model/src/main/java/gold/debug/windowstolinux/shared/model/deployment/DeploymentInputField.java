package gold.debug.windowstolinux.shared.model.deployment;

import java.util.List;
import java.util.Objects;

/**
 * A non-secret missing deployment parameter with bounded choices and a stable message key. / 非秘密的缺失部署参数，带有界候选和稳定消息键。
 *
 * @param id stable identifier within the owning registry / 所属登记表内的稳定标识
 * @param labelKey label key / 标签键
 * @param helpKey help key / 帮助键
 * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
 * @param choices choices / 选项集合
 */
public record DeploymentInputField(String id, String labelKey, String helpKey, String value, List<String> choices) {
    /**
     * Validates input descriptors before they reach either AI or a desktop form. / 在输入描述到达 AI 或桌面表单前进行校验。
     *
     * @param id stable identifier within the owning registry / 所属登记表内的稳定标识
     * @param labelKey label key / 标签键
     * @param helpKey help key / 帮助键
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     * @param choices choices / 选项集合
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public DeploymentInputField {
        if (id == null || !id.matches("[a-zA-Z0-9._/-]{1,160}"))
            throw new IllegalArgumentException("invalid input identifier");
        Objects.requireNonNull(labelKey);
        Objects.requireNonNull(helpKey);
        Objects.requireNonNull(value);
        choices = List.copyOf(choices);
        int limit = id.equals("applicationDeclaration") || id.endsWith("/applicationDeclaration") ? 65536 : 4096;
        if (value.length() > limit || choices.size() > 64)
            throw new IllegalArgumentException("input descriptor exceeds limits");
    }
}
